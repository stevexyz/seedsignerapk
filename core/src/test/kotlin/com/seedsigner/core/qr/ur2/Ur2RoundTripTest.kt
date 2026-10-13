package com.seedsigner.core.qr.ur2

import com.seedsigner.core.crypto.Hashes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-contained UR2 round-trip tests (JVM, no external input).
 * Byte-identity with the Python reference is proven separately by
 * tools/crosscheck_ur2.py + :core:crosscheck-ur2.
 */
class Ur2RoundTripTest {

    private fun payload(size: Int): ByteArray =
        ByteArray(size) { i -> (i * 31 + 7).toByte() }

    @Test
    fun singlePartRoundTrip() {
        val cbor = payload(120)
        val ur = UREncoder.encode(UR("seedsigner", cbor))
        assertTrue(ur.startsWith("ur:seedsigner/"))
        val decoded = URDecoder.decode(ur)
        assertEquals("seedsigner", decoded.type)
        assertTrue(decoded.cbor.contentEquals(cbor))
    }

    @Test
    fun multiPartRoundTripInOrder() {
        val cbor = payload(6000)
        val encoder = UREncoder(UR("seedsigner", cbor), maxFragmentLen = 1500)
        val parts = (1..encoder.fountainEncoder.seqLen()).map { encoder.nextPart() }
        assertTrue(parts.size > 1)

        val decoder = URDecoder()
        for (part in parts) assertTrue(decoder.receivePart(part))
        val result = decoder.resultMessage()
        assertTrue(result != null)
        assertTrue(result!!.cbor.contentEquals(cbor))
    }

    @Test
    fun multiPartRoundTripReversed() {
        val cbor = payload(15000)
        val encoder = UREncoder(UR("seedsigner", cbor), maxFragmentLen = 1500)
        val parts = (1..encoder.fountainEncoder.seqLen()).map { encoder.nextPart() }

        val decoder = URDecoder()
        for (part in parts.asReversed()) assertTrue(decoder.receivePart(part))
        val result = decoder.resultMessage()
        assertTrue(result != null)
        assertTrue(result!!.cbor.contentEquals(cbor))
    }

    @Test
    fun fountainRecoversFromMissingParts() {
        // Generate more parts than the minimum; dropping the first (a pure
        // data frame) must still decode, because the fountain code carries
        // the same information in mixed frames.
        val cbor = payload(6000)
        val n = UREncoder(UR("seedsigner", cbor), maxFragmentLen = 1500).let {
            it.fountainEncoder.seqLen()
        }
        val encoder = UREncoder(UR("seedsigner", cbor), maxFragmentLen = 1500)
        val parts = (1..(n + 5)).map { encoder.nextPart() }
        val dropped = parts.drop(1) // lose the first pure data frame

        val decoder = URDecoder()
        var processed = 0
        for (part in dropped) if (decoder.receivePart(part)) processed++
        val result = decoder.resultMessage()
        assertTrue(result != null, "fountain recovery failed after $processed parts")
        assertTrue(result!!.cbor.contentEquals(cbor))
    }

    @Test
    fun duplicatePartsAreIgnored() {
        val cbor = payload(2000)
        val encoder = UREncoder(UR("seedsigner", cbor), maxFragmentLen = 1500)
        val parts = (1..encoder.fountainEncoder.seqLen()).map { encoder.nextPart() }

        val decoder = URDecoder()
        for (part in parts + parts) decoder.receivePart(part)
        assertTrue(decoder.resultMessage()!!.cbor.contentEquals(cbor))
    }

    @Test
    fun crc32MatchesKnownVector() {
        // "123456789" -> 0xcbf43926 (standard CRC-32/ISO-HDLC check value)
        assertEquals(0xCBF43926L, Crc32.crc32("123456789".toByteArray()))
    }

    @Test
    fun cborRoundTrip() {
        val e = CborEncoder()
        e.encodeArraySize(3)
        e.encodeInteger(300)
        e.encodeBytes(Hashes.sha256("x".toByteArray()))
        e.encodeBool(true)
        val buf = e.getBytes()

        val d = CborDecoder(buf)
        assertEquals(3L, d.decodeArraySize().first)
        assertEquals(300L, d.decodeUnsigned().first)
        assertEquals(32, d.decodeBytes().first.size)
        assertEquals(true, d.decodeBool().first)
        assertEquals(buf.size, d.pos)
    }

    @Test
    fun negativeIntegerEncodingMatchesReference() {
        // Upstream quirk (cbor_lite.py): get_byte_length(-5) == 0, so a
        // negative integer is written as the single byte (tag + value).
        // UR2 parts never contain negative integers; we mirror the
        // reference bytes exactly for byte-identity.
        val e = CborEncoder()
        e.encodeInteger(-5)
        assertContentEquals(byteArrayOf(0x1B), e.getBytes())
    }
}
