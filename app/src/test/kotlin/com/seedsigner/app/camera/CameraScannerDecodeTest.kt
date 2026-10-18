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

    /**
     * Regression: optimal-segmentation encoders (Python `qrcode`, Specter
     * Desktop's QR writers) split text payloads into BYTE + ALPHANUMERIC
     * segments whenever a long 0-9A-Z run exists. The fixture below is the
     * real 130-byte payload of a Specter-style frame, encoded with the
     * Python `qrcode` library as BYTE(107) + ALPHANUMERIC(23). ZXing's
     * `BYTE_SEGMENTS` then holds only the 107 byte-mode bytes; the payload
     * must be reconstructed from `result.text` (pure ASCII ⇒ lossless).
     * Returns only the first byte segment and silently truncates the frame.
     */
    @Test
    fun `decodes mixed BYTE+ALPHANUMERIC segment payload in full`() {
        val payload = "p3of3 ldbcgGsYjZzO2FQAAIABAACAAAAAgAAAAAAAAAAAAAAiAgO+yolOg+gD8RQbo/zhCk9cjirB32wtbNqHK7EPSmNKPRiNnM7YVAAAgAEAAIAAAACAAQAAAAAAAAAA".toByteArray()
        // 49x49 module matrix, rows as hex (1 = dark). Encoded by Python
        // `qrcode` 8.x (ERROR_CORRECT_M, border 4) — the encoder whose
        // optimal segmentation triggered the original truncation.
        val rows = listOf(
            "fe7cafc444bf8", "821f8f05efa08", "ba06c18e11ae8", "ba3d5480612e8",
            "ba4cc3eed02e8", "828db22126208", "feaaaaaaaabf8", "005efe36d9800",
            "96a34bf85c500", "95116d8089a50", "67b21960f63b8", "5d7b1ef7b5010",
            "3f1c5c99faba8", "d08366c93c9e8", "bbd576ac31d58", "347b35c445570",
            "5f008da7ce6c8", "214984a0d64c8", "cb56ae2eebad0", "19a05b0cc58b0",
            "26aacadb0e600", "71be8441900c8", "dfbe27eb8efd8", "688cfa2566890",
            "5af8aabdfeaa0", "98d53231ad8f0", "df9f77ef8bf88", "605691123e740",
            "2aead9f0b8d68", "0dc3621d168f8", "ef22a1eba4278", "d40f791a163e8",
            "8b4d657582990", "dda6983bbac20", "f73eabfa17768", "d918d442b3b88",
            "3f63833fdc298", "cc78f44065978", "478b345e4eb68", "718db8c13ed18",
            "e22dbbf2faf80", "00986a31938c0", "fe7756a50aad8", "82e0763a448e0",
            "ba3313fb4efd8", "baed932589498", "ba1309c5ce9f8", "8270dd00d6540",
            "febd52aeee3b8",
        )
        val n = rows.size
        require(rows.all { it.length == 13 }) // 49 bits -> 13 hex chars (1 pad)
        val plane = ByteArray(200 * 160) { 255.toByte() }
        val ox = 25; val oy = 10
        rows.forEachIndexed { y, hex ->
            val bits = hex.replace(" ", "")
            for (x in 0 until n) {
                val nibble = bits[x / 4].digitToInt(16)
                if ((nibble shr (3 - x % 4)) and 1 == 1) {
                    plane[(oy + y) * 200 + (ox + x)] = 0
                }
            }
        }
        assertArrayEquals(payload, decodeQr(plane, 200, 160))
    }
}
