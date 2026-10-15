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
 * Decoding happens on a background HandlerThread; [onQrText] is invoked
 * there, so the caller hops to the main thread for UI.
 */
class CameraScanner(
    private val context: Context,
    private val preview: TextureView,
    private val onQrText: (String) -> Unit
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var cameraId: String? = null
    private var running = false
    private val reader = QRCodeReader()

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
        val text = try {
            image.use { decode(it) }
        } catch (e: Exception) {
            null
        }
        if (text != null) onQrText(text)
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

    /**
     * 4-rotation sweep (VENDORED.md mitigation for the pinned ZXing detector
     * limitation). The vendored `PlanarYUVLuminanceSource` has no rotation
     * parameter (it takes `reverseHorizontal`), so rotations are applied by
     * re-packing the Y plane.
     */
    private fun decode(image: Image): String? {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val buf = plane.buffer

        // pack the Y plane contiguously
        val y = ByteArray(width * height)
        for (row in 0 until height) {
            buf.position(row * rowStride)
            buf.get(y, row * width, width)
        }

        for (rotation in 0..3) {
            val (rot, w, h) = when (rotation) {
                0 -> Triple(y, width, height)
                else -> rotate(y, width, height, rotation)
            }
            try {
                val source = PlanarYUVLuminanceSource(
                    rot, w, h, 0, 0, w, h, false
                )
                return reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source))).text
            } catch (e: Exception) {
                // try next rotation
            } finally {
                reader.reset()
            }
        }
        return null
    }

    private fun rotate(src: ByteArray, w: Int, h: Int, quarterTurns: Int): Triple<ByteArray, Int, Int> {
        val (nw, nh) = if (quarterTurns % 2 == 0) w to h else h to w
        val dst = ByteArray(nw * nh)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val v = src[row * w + col]
                val (dc, dr) = when (quarterTurns) {
                    1 -> col to (w - 1 - row)   // 90° CW
                    2 -> (w - 1 - col) to (h - 1 - row)
                    else -> (h - 1 - col) to row // 270° CW
                }
                dst[dr * nw + dc] = v
            }
        }
        return Triple(dst, nw, nh)
    }

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
