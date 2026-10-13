package com.seedsigner.core.qr.ur2

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UR crypto payload tests (JVM, no external input).
 * Byte-identity with the Python reference (urtypes @ 7fb280ea) is proven
 * separately by tools/crosscheck_urtypes.py + :core:crosscheck-urtypes.
 */
class UrTypesTest {

    private fun sampleAccountCbor(): ByteArray = UrTypes.xpubAccountCbor(
        rootFingerprint = byteArrayOf(0xda.toByte(), 0xc5.toByte(), 0x7b.toByte(), 0xbe.toByte()),
        key = ByteArray(33) { (it + 2).toByte() },
        chainCode = ByteArray(32) { it.toByte() },
        parentFingerprint = byteArrayOf(0x96.toByte(), 0x7f.toByte(), 0xba.toByte(), 0x44.toByte()),
        derivation = "m/84'/0'/0'",
        network = "mainnet",
        scriptTags = listOf(listOf(UrTypes.SCRIPT_WPKH))
    )

    @Test
    fun `account cbor round trips through ur2 fountain`() {
        val cbor = sampleAccountCbor()
        val encoder = UREncoder(UR("crypto-account", cbor), maxFragmentLen = 30)
        val parts = (1..encoder.fountainEncoder.seqLen()).map { encoder.nextPart() }
        assertTrue(parts.size >= 1)

        val decoder = URDecoder()
        for (part in parts) assertTrue(decoder.receivePart(part))
        val result = decoder.resultMessage()
        assertTrue(result != null)
        assertEquals("crypto-account", result!!.type)
        assertContentEquals(cbor, result.cbor)
    }

    @Test
    fun `sh wpkh nests two tags around the tagged hdkey`() {
        val cbor = UrTypes.xpubAccountCbor(
            rootFingerprint = byteArrayOf(1, 2, 3, 4),
            key = ByteArray(33) { 0x02.toByte() },
            chainCode = ByteArray(32) { 7 },
            parentFingerprint = byteArrayOf(5, 6, 7, 8),
            derivation = "m/49'/0'/0'",
            network = "mainnet",
            scriptTags = listOf(listOf(UrTypes.SCRIPT_SH, UrTypes.SCRIPT_WPKH))
        )
        // account map(2) | 1: fp | 2: array(1) | tag 400 | tag 404 | tag 303 | hdkey map(4)
        val prefix = listOf(0xA2, 0x01, 0x1A, 0x01, 0x02, 0x03, 0x04,
                            0x02, 0x81, 0xD9, 0x01, 0x90, 0xD9, 0x01, 0x94, 0xD9, 0x01, 0x2F, 0xA4)
        for (i in prefix.indices) {
            assertEquals(prefix[i].toByte(), cbor[i], "byte $i")
        }
    }

    @Test
    fun `psbt cbor is a cbor byte string`() {
        val psbt = byteArrayOf(0x70.toByte(), 0x73.toByte(), 0x62.toByte(), 0x74.toByte(), 0x00, 0x01)
        val cbor = UrTypes.psbtCbor(psbt)
        // byte string header for 6 bytes, then the payload
        assertEquals(0x46.toByte(), cbor[0])
        assertContentEquals(psbt, cbor.copyOfRange(1, 7))
    }
}
