package com.seedsigner.core.qr.ur2

import com.seedsigner.core.crypto.Hashes

/**
 * Port of seedsigner_orig/src/seedsigner/helpers/ur2/xoshiro256.py
 * (xoshiro256** 1.0 by David Blackman and Sebastiano Vigna, public domain;
 * wrapper Copyright © 2020 Foundation Devices, Inc., BSD-2-Clause Plus Patent License).
 */
class Xoshiro256(arr: LongArray? = null) {
    val s = longArrayOf(0, 0, 0, 0).also {
        if (arr != null) {
            it[0] = arr[0]; it[1] = arr[1]; it[2] = arr[2]; it[3] = arr[3]
        }
    }

    private fun setS(arr: ByteArray) {
        for (i in 0 until 4) {
            val o = i * 8
            var v = 0L
            for (n in 0 until 8) {
                v = v shl 8
                v = v or (arr[o + n].toLong() and 0xFFL)
            }
            s[i] = v
        }
    }

    fun next(): Long {
        val result = rotl((s[1] * 5) and MAX_UINT64, 7) * 9
        val t = (s[1] shl 17) and MAX_UINT64
        s[2] = s[2] xor s[0]
        s[3] = s[3] xor s[1]
        s[1] = s[1] xor s[2]
        s[0] = s[0] xor s[3]
        s[2] = s[2] xor t
        s[3] = rotl(s[3], 45)
        return result
    }

    // Unsigned conversion: Python's `nxt` is an unsigned 64-bit int, so a
    // high-bit-set word must map to a value in [2^63, 2^64), not a negative.
    fun nextDouble(): Double = next().toULong().toDouble() / (Math.pow(2.0, 64.0))

    fun nextInt(low: Long, high: Long): Long {
        return (Math.floor(nextDouble() * (high - low + 1) + low).toLong() and MAX_UINT64)
    }

    fun nextByte(): Long = nextInt(0, 255)

    fun nextData(count: Int): ByteArray = ByteArray(count) { (nextByte() and 0xFF).toInt().toByte() }

    fun jump(table: LongArray) {
        var s0 = 0L; var s1 = 0L; var s2 = 0L; var s3 = 0L
        for (j in table) {
            for (b in 0 until 64) {
                if ((j ushr b) and 1L == 1L) {
                    s0 = s0 xor s[0]; s1 = s1 xor s[1]; s2 = s2 xor s[2]; s3 = s3 xor s[3]
                }
                next()
            }
        }
        s[0] = s0; s[1] = s1; s[2] = s2; s[3] = s3
    }

    companion object {
        const val MAX_UINT64 = -1L

        // Bit-identical to the source's unsigned constants, written as signed Kotlin Longs.
        private val JUMP = longArrayOf(0x180ec6d33cfd0abaL, -3051731464161248980L, -6244198995065845334L, 0x39abdc4529b1661cL)
        private val LONG_JUMP = longArrayOf(0x76e15d3efefdcbbfL, -4251311993797857357L, 0x77710069854ee241L, 0x39109bb02acbe635L)

        private fun rotl(x: Long, k: Int): Long = (x shl k) or (x ushr (64 - k))

        fun fromInt8Array(arr: ByteArray): Xoshiro256 {
            val x = Xoshiro256()
            x.setS(arr)
            return x
        }

        fun fromBytes(buf: ByteArray): Xoshiro256 {
            val x = Xoshiro256()
            x.setS(Hashes.sha256(buf))
            return x
        }

        fun fromCrc32(crc32: Long): Xoshiro256 =
            fromBytes(intToBytes(crc32))

        fun fromString(str: String): Xoshiro256 =
            fromBytes(str.toByteArray(Charsets.UTF_8))

        /** Python `int_to_bytes`: fixed 4-byte big-endian. */
        fun intToBytes(n: Long): ByteArray = byteArrayOf(
            ((n ushr 24) and 0xFF).toInt().toByte(),
            ((n ushr 16) and 0xFF).toInt().toByte(),
            ((n ushr 8) and 0xFF).toInt().toByte(),
            (n and 0xFF).toInt().toByte(),
        )
    }
}
