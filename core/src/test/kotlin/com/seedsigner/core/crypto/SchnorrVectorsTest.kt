package com.seedsigner.core.crypto

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * BIP-340 Schnorr test vectors, taken from the official
 * `bip-0340/test-vectors.csv` in github.com/bitcoin/bips (fetched 2025,
 * trusted source). Signing is deterministic (32 zero aux bytes for rows 0/1).
 */
class SchnorrVectorsTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    data class Vector(val secret: String?, val pubkey: String, val aux: String, val message: String, val signature: String)

    // rows 0, 1, 2 of bip340_test_vectors.csv
    private val vectors = listOf(
        Vector("0000000000000000000000000000000000000000000000000000000000000003",
            "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0000000000000000000000000000000000000000000000000000000000000000",
            "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0"),
        Vector("B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF",
            "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            "0000000000000000000000000000000000000000000000000000000000000001",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE33418906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A"),
        Vector("C90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B14E5C9",
            "DD308AFEC5777E13121FA72B9CC1B7CC0139715309B086C960E18FD969774EB8",
            "C87AA53824B4D7AE2EB035A2B5BBBCCC080E76CDC6D1692C4B0B62D798E6D906",
            "7E2D58D8B3BCDF1ABADEC7829054F90DDA9805AAB56C77333024B9D0A508B75C",
            "5831AAEED7B44BB74E5EAB94BA9D4294C49BCF2A60728D8B4C200F50DD313C1BAB745879A5AD954A72C45A91C3A51D3C7ADEA98D82F8481E0E1E03674A6F3FB7"),
    )

    @Test
    fun `signature verification against official BIP-340 vectors`() {
        for (v in vectors) {
            assertTrue(Secp256k1.schnorrVerify(hex(v.message), hex(v.signature), hex(v.pubkey)),
                "verify failed for pubkey ${v.pubkey.take(8)}")
        }
    }

    @Test
    fun `signing matches official BIP-340 vectors`() {
        for (v in vectors) {
            val sig = Secp256k1.schnorrSign(hex(v.message), hex(v.secret!!), hex(v.aux))
            assertEqualsHex(v.signature, sig.toHexString(), "sig mismatch for pubkey ${v.pubkey.take(8)}")
        }
    }

    private fun assertEqualsHex(expected: String, actual: String, msg: String) {
        assertTrue(expected.equals(actual, ignoreCase = true), "$msg: expected $expected got $actual")
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
