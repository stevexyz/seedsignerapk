package com.seedsigner.core.qr.ur2

/**
 * Minimal CBOR writer — 1:1 port of the `urtypes` cbor encoder
 * (commit 7fb280ea, pinned in upstream requirements.txt; MIT).
 * Supports exactly the item types the UR crypto payloads use:
 * unsigned/negative ints, byte strings, text strings, arrays, maps, tags, booleans.
 */
object Cbor {
    private fun encodeIByte(majorType: Int, value: Long): ByteArray {
        val t = majorType shl 5
        return when {
            value < 24L -> byteArrayOf((t + value.toInt()).toByte())
            value < 0x100L -> byteArrayOf((t + 24).toByte(), value.toInt().toByte())
            value < 0x10000L -> byteArrayOf(
                (t + 25).toByte(),
                ((value shr 8) and 0xFF).toInt().toByte(),
                (value and 0xFF).toInt().toByte()
            )
            value < 0x100000000L -> byteArrayOf(
                (t + 26).toByte(),
                ((value shr 24) and 0xFF).toInt().toByte(),
                ((value shr 16) and 0xFF).toInt().toByte(),
                ((value shr 8) and 0xFF).toInt().toByte(),
                (value and 0xFF).toInt().toByte()
            )
            else -> {
                val out = ByteArray(9)
                out[0] = (t + 27).toByte()
                for (i in 0 until 8) {
                    out[1 + i] = ((value shr (56 - 8 * i)) and 0xFF).toInt().toByte()
                }
                out
            }
        }
    }

    fun encodeInt(w: CborWriter, value: Long) {
        if (value < 0) w.bytes(encodeIByte(1, -value - 1)) else w.bytes(encodeIByte(0, value))
    }

    fun encodeBytes(w: CborWriter, value: ByteArray) {
        w.bytes(encodeIByte(2, value.size.toLong()))
        w.bytes(value)
    }

    fun encodeText(w: CborWriter, value: String) {
        val b = value.toByteArray(Charsets.UTF_8)
        w.bytes(encodeIByte(3, b.size.toLong()))
        w.bytes(b)
    }

    fun encodeArrayHeader(w: CborWriter, length: Int) { w.bytes(encodeIByte(4, length.toLong())) }

    fun encodeMapHeader(w: CborWriter, length: Int) { w.bytes(encodeIByte(5, length.toLong())) }

    fun encodeTag(w: CborWriter, tag: Long) { w.bytes(encodeIByte(6, tag)) }

    fun encodeBoolean(w: CborWriter, value: Boolean) { w.bytes(encodeIByte(7, if (value) 21L else 20L)) }
}

/** CBOR output sink (mirrors the `output` of the urtypes Encoder). */
class CborWriter {
    val out = java.io.ByteArrayOutputStream()
    fun bytes(b: ByteArray) { out.write(b) }
}
