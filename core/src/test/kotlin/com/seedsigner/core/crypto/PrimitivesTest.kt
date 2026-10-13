package com.seedsigner.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Known-answer tests for the low-level primitives. */
class PrimitivesTest {

    // ---------- RIPEMD-160 (official vectors) ----------

    @Test
    fun `ripemd160 official vectors`() {
        assertEquals(
            "9c1185a5c5e9fc54612808977ee8f548b2258d31",
            Hashes.ripemd160(ByteArray(0)).toHex()
        )
        assertEquals(
            "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc",
            Hashes.ripemd160("abc".toByteArray()).toHex()
        )
        assertEquals(
            "5d0689ef49d2fae572b881b123a85ffa21595f36",
            Hashes.ripemd160("message digest".toByteArray()).toHex()
        )
        assertEquals(
            "12a053384a9c0c88e405a06c27dcf49ada62eb2b",
            Hashes.ripemd160("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toByteArray()).toHex()
        )
        // 1,000,000 x 'a' (multi-block)
        assertEquals(
            "52783243c1697bdbe16d37f97f68f08325dc1528",
            Hashes.ripemd160(ByteArray(1_000_000) { 'a'.code.toByte() }).toHex()
        )
    }

    // ---------- Base58 ----------

    @Test
    fun `base58 known vectors`() {
        // Reference vectors verified against embit.base58.
        assertEquals("StV1DL6CwTryKyV", Base58.encode("hello world".toByteArray()))
        assertContentEquals("hello world".toByteArray(), Base58.decode("StV1DL6CwTryKyV"))
        assertEquals("11111", Base58.encode(ByteArray(5)))
        assertContentEquals(ByteArray(5), Base58.decode("11111"))
        // non-trivial round trip
        val data = (0 until 32).map { (it * 37).toByte() }.toByteArray()
        assertContentEquals(data, Base58.decode(Base58.encode(data)))
    }

    @Test
    fun `base58check round trip`() {
        val data = ByteArray(25) { it.toByte() }
        assertContentEquals(data, Base58.decodeCheck(Base58.encodeCheck(data)))
    }

    // ---------- Bech32 (BIP-173 vectors) ----------

    @Test
    fun `bech32 BIP173 valid vectors`() {
        // BIP-173 valid vector (all-uppercase input, 20-byte program 751e76e8...)
        val addr = Bech32.decodeAddress("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4")
        assertEquals("bc", addr.hrp)
        assertEquals(0, addr.witnessVersion)
        assertEquals(
            "751e76e8199196d454941c45d1b3a323f1433bd6",
            addr.program.joinToString("") { "%02x".format(it) }
        )
        // round trip: lowercase canonical form
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            Bech32.encodeAddress(addr.hrp, addr.witnessVersion, addr.program, Bech32.Encoding.BECH32)
        )
    }

    @Test
    fun `bech32m taproot vector BIP350`() {
        // BIP-350 valid example: v1, 32-byte program
        val s = "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"
        val addr = Bech32.decodeAddress(s)
        assertEquals("bc", addr.hrp)
        assertEquals(1, addr.witnessVersion)
        assertEquals(32, addr.program.size)
        assertEquals(s, Bech32.encodeAddress("bc", 1, addr.program, Bech32.Encoding.BECH32M))
    }

    // ---------- secp256k1 ----------

    @Test
    fun `group law basics`() {
        val g = Secp256k1.G
        // 2G has the known x-coordinate
        assertEquals(
            java.math.BigInteger("C6047F9441ED7D6D3045406E95C07CD85C778E4B8CEF3CA7ABAC09B95C709EE5", 16),
            Secp256k1.pointDouble(g).x
        )
        // 3G via repeated addition == 3G via scalar mul
        assertEquals(Secp256k1.scalarMul(java.math.BigInteger.valueOf(3)),
            Secp256k1.pointAdd(g, Secp256k1.pointAdd(g, g)))
        // associativity: (2G)+G == G+(2G)
        val twoG = Secp256k1.pointDouble(g)
        assertEquals(Secp256k1.pointAdd(twoG, g), Secp256k1.pointAdd(g, twoG))
        // G + (-G) = infinity
        val negG = Secp256k1.Point(g.x, Secp256k1.P.subtract(g.y))
        assertTrue(Secp256k1.pointAdd(g, negG).isInfinity)
        // N*G = infinity
        assertTrue(Secp256k1.scalarMul(Secp256k1.N).isInfinity)
    }

    @Test
    fun `ecdsa sign and verify`() {
        val priv = ByteArray(32) { 7 }
        val pub = Secp256k1.publicFromPrivate(priv)
        val hash = ByteArray(32) { 1 }
        val sig = Secp256k1.signWithGrind(hash, priv)
        assertTrue(Secp256k1.verify(hash, sig, pub))
        // low-s
        assertTrue(sig.s <= Secp256k1.N.shiftRight(1))
        // determinism (RFC6979)
        assertContentEquals(Secp256k1.signatureToDer(sig), Secp256k1.signatureToDer(Secp256k1.signWithGrind(hash, priv)))
        // DER round trip
        val parsed = Secp256k1.parseDer(Secp256k1.signatureToDer(sig))
        assertEquals(sig.r, parsed.r)
        assertEquals(sig.s, parsed.s)
    }

    @Test
    fun `compressed pubkey round trip`() {
        val pub = Secp256k1.publicFromPrivate(ByteArray(32) { 42 })
        val restored = Secp256k1.pointFromBytes(pub.toBytes())
        assertEquals(pub.x, restored.x)
        assertEquals(pub.y, restored.y)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
