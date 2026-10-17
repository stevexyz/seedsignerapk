package com.seedsigner.core.qr

import java.util.Base64
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitQrDecodersTest {

    private val psbt = byteArrayOf(
        0x70.toByte(), 0x73.toByte(), 0x62.toByte(), 0x74.toByte(), 0xff.toByte(),
        0x01, 0x00, 0x71, 0x02, 0x00, 0x00, 0x00, 0x01, 0xf8.toByte(), 0x34, 0xe7.toByte()
    )

    // --- Specter pNofM ---

    @Test
    fun `specter segment classification`() {
        val d = SpecterPsbtDecoder()
        assertTrue(d.isSpecterPsbtSegment("p1of2 ABC+DEF/ghi="))
        assertTrue(d.isSpecterPsbtSegment("P2OF2 ABC")) // case-insensitive like upstream
        assertFalse(d.isSpecterPsbtSegment("p1of2 ABC DEF")) // trailing token: not base64-only
        assertFalse(d.isSpecterPsbtSegment("p1of2 {\"json\": 1}"))
    }

    @Test
    fun `specter accumulates and assembles in order`() {
        val full = Base64.getEncoder().encodeToString(psbt)
        val mid = full.length / 2
        val d1 = full.substring(0, mid)
        val d2 = full.substring(mid)
        val d = SpecterPsbtDecoder()
        assertEquals(SegmentStatus.PART_COMPLETE, d.add("p2of2 $d2"))
        assertEquals(SegmentStatus.COMPLETE, d.add("p1of2 $d1"))
        assertContentEquals(psbt, d.data()!!)
        // duplicates are no-ops, not errors
        assertEquals(SegmentStatus.PART_EXISTING, d.add("p1of2 $d1"))
    }

    @Test
    fun `specter rejects a changed total`() {
        val full = Base64.getEncoder().encodeToString(psbt)
        val d = SpecterPsbtDecoder()
        d.add("p1of2 ${full.take(10)}")
        assertFailsWith<SplitQrException> { d.add("p2of3 ${full.take(10)}") }
    }

    // --- BBQr ---

    /**
     * BBQr frame: B$ {encoding} {type} {total:2 base36} {current:2 base36}
     * {data}. [segNum] is 1-based (1..total); the frame index is 0-based
     * (upstream does `int(YY,36) + 1`).
     */
    private fun bbqrFrame(enc: Char, segNum: Int, total: Int, data: String): String =
        "B$$enc" + 'P' +
            total.toString(36).uppercase().padStart(2, '0') +
            (segNum - 1).toString(36).uppercase().padStart(2, '0') + data

    /**
     * BBQr splits base32 at 8-char (5-byte) boundaries; per-segment padding
     * only works at those boundaries, so the tests chunk accordingly.
     */
    private fun b32Chunks(data: ByteArray, chunk: Int = 8): List<String> {
        val b32 = base32Encode(data)
        return b32.chunked(chunk)
    }

    /** RFC 4648 base32 encode (no padding) — Java has no base32 API. */
    private fun base32Encode(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var bits = 0
        var value = 0
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(alphabet[(value shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(alphabet[(value shl (5 - bits)) and 31])
        return sb.toString()
    }

    @Test
    fun `bbqr hex encoding assembles`() {
        val hex = psbt.joinToString("") { "%02x".format(it) }
        val half = hex.length / 2
        val d = BbqrDecoder()
        assertTrue(d.isBbqrSegment(bbqrFrame('H', 1, 2, hex.take(half))))
        assertEquals(SegmentStatus.PART_COMPLETE, d.add(bbqrFrame('H', 1, 2, hex.take(half))))
        assertEquals(SegmentStatus.COMPLETE, d.add(bbqrFrame('H', 2, 2, hex.substring(half))))
        assertContentEquals(psbt, d.data()!!)
    }

    @Test
    fun `bbqr base32 encoding assembles`() {
        // Per-segment base32 padding is lossless only when the encoded
        // length is a multiple of 8 (payload a multiple of 5 bytes).
        val payload = psbt + byteArrayOf(0, 0, 0, 0)
        val chunks = b32Chunks(payload)
        val d = BbqrDecoder()
        val total = chunks.size
        chunks.forEachIndexed { i, c ->
            val expected = if (i < total - 1) SegmentStatus.PART_COMPLETE else SegmentStatus.COMPLETE
            assertEquals(expected, d.add(bbqrFrame('P', i + 1, total, c)))
        }
        assertContentEquals(payload, d.data()!!)
    }

    @Test
    fun `bbqr zlib encoding assembles`() {
        // psbt + 2 zero bytes deflates (raw) to a multiple of 5 bytes in
        // this JVM, so the per-segment base32 split is lossless.
        val payload = psbt + byteArrayOf(0, 0)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(payload)
        deflater.finish()
        val out = ByteArray(payload.size + 64)
        val n = deflater.deflate(out)
        deflater.end()
        val raw = out.copyOf(n)
        assertTrue(raw.size % 5 == 0, "precondition: compressed length ${raw.size} must be a multiple of 5")
        val chunks = b32Chunks(raw)
        val d = BbqrDecoder()
        val total = chunks.size
        chunks.forEachIndexed { i, c ->
            val expected = if (i < total - 1) SegmentStatus.PART_COMPLETE else SegmentStatus.COMPLETE
            assertEquals(expected, d.add(bbqrFrame('Z', i + 1, total, c)))
        }
        assertContentEquals(payload, d.data()!!)
    }

    @Test
    fun `incomplete decoder has no data`() {
        val d = BbqrDecoder()
        d.add(bbqrFrame('H', 1, 2, "0011"))
        assertNull(d.data())
    }

}
