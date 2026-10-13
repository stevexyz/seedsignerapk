package com.seedsigner.core.qr.ur2

/**
 * Port of seedsigner_orig/src/seedsigner/helpers/ur2/crc32.py
 * (Copyright © 2020 Foundation Devices, Inc., BSD-2-Clause Plus Patent License).
 */
object Crc32 {
    const val MAX_UINT32 = 0xFFFFFFFFL

    private val TABLE: LongArray = LongArray(256).also { table ->
        for (i in 0 until 256) {
            var c = i.toLong()
            repeat(8) {
                c = if (c % 2 == 0L) c ushr 1 else 0xEDB88320L xor (c ushr 1)
            }
            table[i] = c
        }
    }

    fun crc32(buf: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (byte in buf) {
            crc = (crc ushr 8) xor TABLE[((crc xor (byte.toLong() and 0xFFL)) and 0xFFL).toInt()]
        }
        return crc.inv() and 0xFFFFFFFFL
    }

    /** UR Bytewords checksum: fixed-width 4-byte big-endian CRC32. */
    fun crc32n(buf: ByteArray): ByteArray {
        val n = crc32(buf)
        return byteArrayOf(
            (n ushr 24).toByte(),
            (n ushr 16).toByte(),
            (n ushr 8).toByte(),
            n.toByte(),
        )
    }
}
