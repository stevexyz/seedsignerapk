package com.seedsigner.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

/**
 * Camera2 QR scanner: back camera → YUV_420_888 frames → vendored ZXing
 * `QRCodeReader` with 4-rotation retry (the pinned ZXing 3.5.4 detector
 * limitation, see VENDORED.md, is mitigated by the rotation sweep).
 *
 * Decoding happens on a background HandlerThread; [onQrBytes] is invoked
 * there, so the caller hops to the main thread for UI.
 */
class CameraScanner(
    private val context: Context,
    private val preview: TextureView,
    private val onQrBytes: (ByteArray) -> Unit
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var cameraId: String? = null
    private var running = false

    /**
     * Image-entropy mode: when set, frames are forwarded as packed Y planes
     * (no QR decode) — the upstream image-entropy flows' frame source.
     */
    private var entropyFrameListener: ((ByteArray) -> Unit)? = null
    fun setEntropyFrameListener(cb: ((ByteArray) -> Unit)?) {
        entropyFrameListener = cb
    }

    /**
     * One-shot still capture: full-res square YUV frame, packed Y plane
     * delivered to [cb] (called on the main thread). Upstream takes the
     * final full-res image at 2x the display's max dimension.
     */
    fun captureStill(cb: (ByteArray) -> Unit) {
        val handler = bgHandler ?: return
        val size = Size(1600, 1600)
        val stillReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 1).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage()
                r.close()
                val y = packY(img)
                img?.close()
                Handler(android.os.Looper.getMainLooper()).post { cb(y) }
            }, handler)
        }
        val device = cameraDevice ?: run { stillReader.close(); return }
        val session = captureSession ?: run { stillReader.close(); return }
        session.capture(
            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(stillReader.surface)
            }.build(),
            object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {},
            handler
        )
    }

    private val previewSize: Size
        get() = Size(1280, 960)

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // back-facing camera
        val id = manager.cameraIdList.firstOrNull { camId ->
            manager.getCameraCharacteristics(camId)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.first()
        cameraId = id

        val thread = HandlerThread("qr-camera").also { it.start() }
        bgThread = thread
        bgHandler = Handler(thread.looper)

        imageReader = ImageReader.newInstance(
            previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 3
        ).apply {
            setOnImageAvailableListener({ reader -> processFrame(reader.acquireLatestImage()) }, bgHandler)
        }

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                createSession(device)
            }

            override fun onDisconnected(device: CameraDevice) = device.close()
            override fun onError(device: CameraDevice, error: Int) = device.close()
            override fun onClosed(device: CameraDevice) {}
        }, bgHandler)

        running = true
    }

    /**
     * The platform stub in this SDK build lacks `View.getSurface()`, so the
     * preview surface is built from the TextureView's SurfaceTexture
     * (equivalent, API 14+).
     */
    private fun previewSurface(): Surface? {
        val st = preview.surfaceTexture ?: return null
        return Surface(st)
    }

    private fun createSession(device: CameraDevice) {
        val handler = bgHandler ?: return
        val surfaces = mutableListOf<Surface>(imageReader!!.surface)
        previewSurface()?.let { surfaces.add(it) }

        device.createCaptureSession(surfaces, object :
            android.hardware.camera2.CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                captureSession = session
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(imageReader!!.surface)
                    previewSurface()?.let { addTarget(it) }
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }.build()
                session.setRepeatingRequest(request, null, handler)
            }

            override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {}
        }, handler)
    }

    private fun processFrame(image: Image?) {
        image ?: return
        val listener = entropyFrameListener
        if (listener != null) {
            // entropy mode: forward the Y plane, skip QR decode
            listener(packY(image))
            image.close()
            return
        }
        val bytes = try {
            image.use { decode(it) }
        } catch (e: Exception) {
            android.util.Log.w("CameraScanner", "frame decode failed", e)
            null
        }
        if (bytes != null) {
            onQrBytes(bytes)
        }
    }

    /** Pack the Y plane of a YUV_420_888 frame contiguously. */
    private fun packY(image: Image): ByteArray {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val buf = plane.buffer
        val y = ByteArray(width * height)
        for (row in 0 until height) {
            buf.position(row * rowStride)
            buf.get(y, row * width, width)
        }
        return y
    }

    /** Pack the Y plane and run the pure [decodeQr] on it. */
    private fun decode(image: Image): ByteArray? =
        decodeQr(packY(image), image.width, image.height)

    fun stop() {
        running = false
        try {
            captureSession?.close()
        } catch (e: Exception) {
        }
        try {
            cameraDevice?.close()
        } catch (e: Exception) {
        }
        imageReader?.close()
        bgHandler?.let {
            it.removeCallbacksAndMessages(null)
        }
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
        captureSession = null
        cameraDevice = null
        imageReader = null
    }
}

/**
 * Pure Y-plane → QR payload decode, unit-testable on the JVM.
 *
 * Runs a 4-rotation sweep (VENDORED.md mitigation for the pinned ZXing
 * detector limitation). Returns the payload exactly as encoded in the QR:
 *  - byte mode: `BYTE_SEGMENTS` metadata — the raw payload bytes. The
 *    vendored reader exposes them there (result.text goes through charset
 *    detection and would corrupt binary payloads, result.rawBytes is the
 *    EC-processed codeword stream, NOT the payload);
 *  - text modes (numeric/alphanumeric): `result.text` (pure ASCII; in
 *    numeric mode rawBytes is the packed bit stream, not the payload).
 */
fun decodeQr(yPlane: ByteArray, width: Int, height: Int): ByteArray? {
    val reader = QRCodeReader()
    for (rotation in 0..3) {
        val (rot, w, h) = if (rotation == 0) {
            Triple(yPlane, width, height)
        } else {
            rotatePlane(yPlane, width, height, rotation)
        }
        val source = PlanarYUVLuminanceSource(rot, w, h, 0, 0, w, h, false)
        try {
            val result = reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source))) ?: continue
            val segments = result.resultMetadata[com.google.zxing.ResultMetadataType.BYTE_SEGMENTS]
            return if (segments is List<*> && segments.isNotEmpty()) {
                (segments[0] as ByteArray).copyOf()
            } else {
                result.text.toByteArray(Charsets.ISO_8859_1)
            }
        } catch (e: Exception) {
            // no QR at this orientation — try the next rotation
        } finally {
            reader.reset()
        }
    }
    return null
}

/**
 * Pure quarter-turn rotation of a row-major pixel plane (top-level so the
 * arithmetic is unit-testable on the JVM; [CameraScanner] uses it for the
 * 4-rotation detector sweep, VENDORED.md). Returns (rotated, newW, newH).
 */
fun rotatePlane(src: ByteArray, w: Int, h: Int, quarterTurns: Int): Triple<ByteArray, Int, Int> {
    val q = ((quarterTurns % 4) + 4) % 4
    if (q == 0) return Triple(src.copyOf(), w, h)
    val (nw, nh) = if (q % 2 == 0) w to h else h to w
    val dst = ByteArray(nw * nh)
    for (row in 0 until h) {
        for (col in 0 until w) {
            val v = src[row * w + col]
            // destination row must stay < nh, destination col must stay < nw
            val (dr, dc) = when (q) {
                1 -> col to (h - 1 - row)        // 90° CW
                2 -> (h - 1 - row) to (w - 1 - col)
                else -> (w - 1 - col) to row     // 270° CW
            }
            dst[dr * nw + dc] = v
        }
    }
    return Triple(dst, nw, nh)
}
