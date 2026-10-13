package com.seedsigner.core.tx

/**
 * Bitcoin compact (variable-length) integers, ported from embit's `compact.py`
 * (BIP-70 varint). Byte-identical by construction.
 */
object Compact {

    fun toBytes(i: Long): ByteArray {
        require(i >= 0) { "integer can't be negative: $i" }
        var order = 0
        while (i shr (8 * (1 shl order)) != 0L) order++
        if (order == 0) {
            if (i < 0xFD) return byteArrayOf(i.toInt().toByte())
            order = 1
        }
        if (order > 3) throw IllegalArgumentException("integer too large: $i")
        val nBytes = 1 shl order
        val out = ByteArray(1 + nBytes)
        out[0] = (0xFC + order).toByte()
        var v = i
        for (k in 0 until nBytes) {
            out[1 + k] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return out
    }

    fun readFrom(data: ByteArray, pos: Int): Pair<Int, Int> { // (value, newPos)
        val i = data[pos].toInt() and 0xFF
        if (i < 0xFD) return i to (pos + 1)
        val nBytes = 1 shl (i - 0xFC)
        var v = 0L
        for (k in 0 until nBytes) v = v or ((data[pos + 1 + k].toLong() and 0xFF) shl (8 * k))
        return v.toInt() to (pos + 1 + nBytes)
    }

    fun fromBytes(b: ByteArray): Int {
        val (v, p) = readFrom(b, 0)
        require(p == b.size) { "Too many bytes" }
        return v
    }
}
