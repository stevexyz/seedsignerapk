package com.seedsigner.core.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Hash primitives. SHA/HMAC from the JDK (JDK, not an Android import — this
 * module is pure JVM). RIPEMD-160 is implemented here because the JDK
 * removed it.
 */
object Hashes {

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)

    fun doubleSha256(data: ByteArray): ByteArray = sha256(sha256(data))

    /**
     * BIP-340 tagged hash: `sha256(sha256(tag) || sha256(tag) || data)`.
     * Ported from embit's `hashes.tagged_hash()`.
     */
    fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val t = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(t + t + data)
    }

    /**
     * BIP-340 tagged hash, incremental form: returns a pre-loaded SHA-256 so
     * callers can update() and finalize. Mirrors embit's `tagged_hash_init`.
     */
    fun taggedHashInit(tag: String, data: ByteArray = ByteArray(0)): java.security.MessageDigest {
        val t = sha256(tag.toByteArray(Charsets.UTF_8))
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(t); md.update(t); md.update(data)
        return md
    }

    /** ripemd160(sha256(data)) — the Bitcoin "hash160". */
    fun hash160(data: ByteArray): ByteArray = ripemd160(sha256(data))

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(data)

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(key, "HmacSHA512")) }
            .doFinal(data)

    // ---------- RIPEMD-160 ----------
    //
    // Faithful port of embit's `util/py_ripemd160.py` (Pieter Wuille, MIT
    // license) — the same implementation upstream SeedSigner uses. Tables,
    // constants, and update order are copied from that file, not from memory.

    private val ML = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
        3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
        1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
        4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13
    )
    private val MR = intArrayOf(
        5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
        6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
        15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
        8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
        12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11
    )
    private val RL = intArrayOf(
        11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
        7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
        11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
        11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
        9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6
    )
    private val RR = intArrayOf(
        8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
        9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
        9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
        15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
        8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11
    )
    private val KL = intArrayOf(0, 0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC.toInt(), 0xA953FD4E.toInt())
    private val KR = intArrayOf(0x50A28BE6, 0x5C4DD124, 0x6D703EF3.toInt(), 0x7A6D76E9.toInt(), 0)

    /** f1..f5 from the RIPEMD-160 specification, indexed 0..4. */
    private fun fi(x: Int, y: Int, z: Int, i: Int): Int = when (i) {
        0 -> x xor y xor z
        1 -> (x and y) or (x.inv() and z)
        2 -> (x or y.inv()) xor z
        3 -> (x and z) or (y and z.inv())
        else -> x xor (y or z.inv())
    }

    private fun rol(x: Int, i: Int): Int = (x shl i) or (x ushr (32 - i))

    /** RIPEMD-160 digest (20 bytes). */
    fun ripemd160(data: ByteArray): ByteArray {
        // Padding: same as SHA (0x80, zeros, 64-bit little-endian bit length).
        val bitLen = data.size.toLong() shl 3
        val paddedSize = ((data.size + 8) / 64 + 1) * 64
        val msg = ByteArray(paddedSize)
        System.arraycopy(data, 0, msg, 0, data.size)
        msg[data.size] = 0x80.toByte()
        for (i in 0 until 8) {
            msg[paddedSize - 8 + i] = ((bitLen shr (8 * i)) and 0xff).toByte()
        }

        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()

        val x = IntArray(16)
        for (block in 0 until paddedSize / 64) {
            for (t in 0 until 16) {
                val o = block * 64 + t * 4
                x[t] = (msg[o].toInt() and 0xff) or
                    ((msg[o + 1].toInt() and 0xff) shl 8) or
                    ((msg[o + 2].toInt() and 0xff) shl 16) or
                    ((msg[o + 3].toInt() and 0xff) shl 24)
            }

            var al = h0; var bl = h1; var cl = h2; var dl = h3; var el = h4
            var ar = h0; var br = h1; var cr = h2; var dr = h3; var er = h4

            for (j in 0 until 80) {
                val rnd = j shr 4

                // Left path: T = rol(A + fi(B,C,D,rnd) + x + K, RL) + E;
                // (A,B,C,D,E) = (E, T, B, rol(C,10), D)
                val tl = rol(al + fi(bl, cl, dl, rnd) + x[ML[j]] + KL[rnd], RL[j]) + el
                val (oal, obl, ocl, odl, oel) = Triple5(al, bl, cl, dl, el)
                al = oel; bl = tl; cl = obl; dl = rol(ocl, 10); el = odl

                // Right path: fi index is 4 - rnd (F4 F3 F2 F1 F5 order)
                val tr = rol(ar + fi(br, cr, dr, 4 - rnd) + x[MR[j]] + KR[rnd], RR[j]) + er
                val (oar, obr, ocr, odr, oer) = Triple5(ar, br, cr, dr, er)
                ar = oer; br = tr; cr = obr; dr = rol(ocr, 10); er = odr
            }

            // Compose old state + left + right into new state (embit's order).
            val (n0, n1, n2, n3, n4) = Triple5(h1 + cl + dr, h2 + dl + er, h3 + el + ar, h4 + al + br, h0 + bl + cr)
            h0 = n0; h1 = n1; h2 = n2; h3 = n3; h4 = n4
        }

        return ByteArray(20).apply {
            val hs = intArrayOf(h0, h1, h2, h3, h4)
            for (i in 0 until 5) {
                for (j in 0 until 4) {
                    this[i * 4 + j] = ((hs[i] shr (8 * j)) and 0xff).toByte()
                }
            }
        }
    }
}

/** Local 5-tuple (Kotlin destructures to 3 max). */
private data class Triple5(val a: Int, val b: Int, val c: Int, val d: Int, val e: Int)


