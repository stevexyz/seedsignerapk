package com.seedsigner.core.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.seedsigner.core.qr.ur2.UR
import com.seedsigner.core.qr.ur2.UREncoder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test of the vendored ZXing decoder:
 * UR2 string -> QR matrix -> PNG bytes -> pixels -> RGBLuminanceSource ->
 * GlobalHistogramBinarizer -> QRCodeReader -> same string.
 * The PNG round-trip goes through ImageIO (JDK), so the test exercises the
 * same path a camera frame (pixel buffer) takes in the app.
 */
class ZxingDecodeTest {

    private fun urString(payloadSize: Int): String {
        val payload = ByteArray(payloadSize) { (it * 17 + 3).toByte() }
        return UREncoder.encode(UR("seedsigner", payload))
    }

    /**
     * EC level M, matching the Python `qrcode` library's default (what upstream
     * SeedSigner renders). ZXing 3.5.4's default is L, and its encoder/decoder
     * pair has a known checksum edge case at L for some contents.
     */
    private val encoderHints = mapOf(EncodeHintType.ERROR_CORRECTION to "M")

    /** 4-module white quiet zone, as a displayed QR has. */
    private fun toPngBytes(matrix: com.google.zxing.common.BitMatrix, scale: Int): ByteArray {
        val quiet = 4
        val dim = (matrix.width + quiet * 2) * scale
        val img = BufferedImage(dim, dim, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until dim) for (x in 0 until dim) img.setRGB(x, y, 0xFFFFFF)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (!matrix.get(x, y)) continue
                for (dx in 0 until scale) for (dy in 0 until scale) {
                    img.setRGB((x + quiet) * scale + dx, (y + quiet) * scale + dy, 0x000000)
                }
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun decodePng(png: ByteArray): String? {
        val img = ImageIO.read(ByteArrayInputStream(png)) ?: error("PNG did not decode")
        val pixels = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, pixels, 0, img.width)
        val source = RGBLuminanceSource(img.width, img.height, pixels)
        val bitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
        return try {
            QRCodeReader().decode(bitmap).text
        } catch (e: Exception) {
            null
        }
    }

    private fun noisyCameraLike(png: ByteArray, scale: Int, noise: Int): ByteArray {
        val img = ImageIO.read(ByteArrayInputStream(png)) ?: error("PNG did not decode")
        // Rescale (camera frames are a different resolution than the display)
        val w = img.width * scale / 100
        val h = img.height * scale / 100
        val resized = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) for (y in 0 until h) {
            val sx = x * 100 / scale
            val sy = y * 100 / scale
            var c = img.getRGB(sx, sy)
            // Uniform noise on each channel
            val n = { ch: Int ->
                val v = ((c shr ch) and 0xFF) + Random.nextInt(-noise, noise + 1)
                (v.coerceIn(0, 255) shl ch)
            }
            c = (c and 0xFF000000.toInt()) or n(16) or n(8) or n(0)
            resized.setRGB(x, y, c)
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(resized, "png", out)
        return out.toByteArray()
    }

    /**
     * Upstream's real fragment sizes (encode_qr.py qr_max_fragment_size):
     * 10 / 30 / 120 cbor bytes for low / medium / high density.
     */
    /**
     * Smoke test using the vendored ZXing *encoder* (test-only — the real
     * encoder is the Python `qrcode` library, covered by the
     * `:core:crosscheck-qr` crosscheck). Kept small: ZXing 3.5.4's encoder
     * has a checksum edge case on ~2% of contents, so a large fixed set of
     * parts is not a stable test.
     */
    @Test
    fun decodesUpstreamDensityFragments() {
        val payload = ByteArray(1000) { (it * 7 + 1).toByte() }
        val ur = UR("seedsigner", payload)
        for (maxFrag in listOf(10, 30, 120)) {
            val encoder = UREncoder(ur, maxFrag)
            repeat(25) {
                val part = encoder.nextPart()
                val png = toPngBytes(QRCodeWriter().encode(part, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, encoderHints), scale = 4)
                assertEquals(part, decodePng(png), "maxFrag=$maxFrag part=$it")
            }
        }
    }

    @Test
    fun decodesSinglePartUr() {
        val s = urString(200)
        val png = toPngBytes(QRCodeWriter().encode(s, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, encoderHints), scale = 6)
        assertEquals(s, decodePng(png))
    }

    @Test
    fun decodesNoisyRescaledFrame() {
        val s = urString(200)
        val clean = toPngBytes(QRCodeWriter().encode(s, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, encoderHints), scale = 8)
        for (scale in listOf(70, 130)) {
            for (noise in listOf(15, 40)) {
                val frame = noisyCameraLike(clean, scale = scale, noise = noise)
                assertEquals(s, decodePng(frame), "scale=$scale noise=$noise")
            }
        }
    }

    @Test
    fun rejectsGarbageImage() {
        val img = BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 120) for (y in 0 until 120) img.setRGB(x, y, 0x808080)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        val pixels = IntArray(120 * 120)
        img.getRGB(0, 0, 120, 120, pixels, 0, 120)
        val bitmap = BinaryBitmap(GlobalHistogramBinarizer(RGBLuminanceSource(120, 120, pixels)))
        val result = try {
            QRCodeReader().decode(bitmap)
            false
        } catch (e: com.google.zxing.NotFoundException) {
            true
        }
        assertTrue(result, "expected NotFoundException on a blank frame")
    }
}
