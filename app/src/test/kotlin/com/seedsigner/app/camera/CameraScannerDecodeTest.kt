package com.seedsigner.app.camera

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.HashMap

/**
 * Proves the full frame→payload pipeline (packing is upstream of this
 * function, so the plane is already packed) on synthetic Y planes:
 * ZXing binarization + 4-rotation sweep + the text/rawBytes discriminator
 * — without a device.
 */
class CameraScannerDecodeTest {

    private fun qrMatrix(data: ByteArray, size: Int): com.google.zxing.common.BitMatrix {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.ERROR_CORRECTION] = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
        return QRCodeWriter().encodeBytes(data, BarcodeFormat.QR_CODE, size, size, hints)
    }

    /** Puts a QR at (ox,oy) into a white `w×h` Y plane (black=0, white=255). */
    private fun planeWithQr(data: ByteArray, qr: Int, w: Int, h: Int, ox: Int, oy: Int): ByteArray {
        val m = qrMatrix(data, qr)
        val plane = ByteArray(w * h) { 255.toByte() }
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                if (m.get(x, y)) plane[(oy + y) * w + (ox + x)] = 0
            }
        }
        return plane
    }

    @Test
    fun `decodes text payload at orientation 0`() {
        val payload = "086910621520136006461847124715781761112009001287".toByteArray()
        val plane = planeWithQr(payload, 200, 320, 240, 60, 20)
        assertArrayEquals(payload, decodeQr(plane, 320, 240))
    }

    @Test
    fun `decodes non-ascii byte payload via rawBytes`() {
        val payload = ByteArray(64) { (it * 37).toByte() } // includes >0x7F and 0x00
        payload[0] = 0xFF.toByte() // guarantee the non-ASCII path
        val plane = planeWithQr(payload, 250, 360, 280, 55, 15)
        assertArrayEquals(payload, decodeQr(plane, 360, 280))
    }

    @Test
    fun `decodes QR rotated 90 degrees`() {
        val payload = "hole luggage safe present".toByteArray()
        val plane = planeWithQr(payload, 200, 240, 320, 20, 60)
        // rotate the whole plane 90° CW: original 240x320 → 320x240
        val rotated = rotatePlane(plane, 240, 320, 1)
        assertArrayEquals(payload, decodeQr(rotated.first, rotated.second, rotated.third))
    }

    @Test
    fun `returns null on blank frame`() {
        assertNull(decodeQr(ByteArray(320 * 240) { 200.toByte() }, 320, 240))
    }
}
