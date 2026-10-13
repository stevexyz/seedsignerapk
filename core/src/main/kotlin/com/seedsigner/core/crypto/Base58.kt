package com.seedsigner.core.crypto

/** Base58 (Bitcoin alphabet) + Base58Check (double-SHA256 checksum). */
object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX: IntArray = IntArray(128) { -1 }.also {
        ALPHABET.forEachIndexed { i, c -> it[c.code] = i }
    }

    /**
     * Standard long division: interpret [data] as a big-endian base-256
     * integer, repeatedly divide by 58; each remainder is a base58 digit.
     */
    /**
     * Textbook: interpret [data] as a big-endian big integer, repeatedly
     * divide by 58 (long division over the byte array); each remainder is
     * the next base58 digit (least significant first). Leading zero bytes
     * each map to one leading '1'.
     */
    fun encode(data: ByteArray): String {
        var zeros = 0
        while (zeros < data.size && data[zeros] == 0.toByte()) zeros++

        // working copy, big-endian, trimmed of leading zeros (zeros kept aside)
        var buf = data.copyOfRange(zeros, data.size)
        while (buf.size > 1 && buf[0] == 0.toByte()) buf = buf.copyOfRange(1, buf.size)

        val sb = StringBuilder()
        while (buf.isNotEmpty() && !(buf.size == 1 && buf[0] == 0.toByte())) {
            // long division of buf (big-endian) by 58
            var rem = 0
            val out = ByteArray(buf.size)
            var leading = 0
            for (i in buf.indices) {
                val cur = rem * 256 + (buf[i].toInt() and 0xff)
                out[i] = (cur / 58).toByte()
                rem = cur % 58
            }
            sb.append(ALPHABET[rem])
            // strip leading zeros of the quotient
            while (leading < out.size && out[leading] == 0.toByte()) leading++
            buf = out.copyOfRange(leading, out.size)
        }

        return "1".repeat(zeros) + sb.reverse().toString()
    }

    fun decode(s: String): ByteArray {
        var zeros = 0
        while (zeros < s.length && s[zeros] == '1') zeros++

        val bytes = ByteArray(s.length * 3)
        var length = 0
        for (c in s) {
            val v = if (c.code < 128) INDEX[c.code] else -1
            require(v >= 0) { "invalid base58 character: $c" }
            var carry = v
            var i = 0
            while (i < length) {
                // acc = acc * 58 + v, little-endian byte array
                val prod = (bytes[i].toInt() and 0xff) * 58 + carry
                bytes[i] = (prod and 0xff).toByte()
                carry = prod ushr 8
                i++
            }
            while (carry > 0) {
                bytes[length++] = (carry and 0xff).toByte()
                carry = carry ushr 8
            }
        }
        // bytes[] holds the accumulator little-endian; base58 output is big-endian
        val out = ByteArray(zeros + length)
        for (i in 0 until length) out[zeros + i] = bytes[length - 1 - i]
        return out
    }

    /** data || double-sha256(data)[0..3], base58-encoded. */
    fun encodeCheck(data: ByteArray): String {
        val checksum = Hashes.doubleSha256(data).copyOfRange(0, 4)
        return encode(data + checksum)
    }

    /** Decodes and verifies the 4-byte checksum. */
    fun decodeCheck(s: String): ByteArray {
        val raw = decode(s)
        require(raw.size >= 4) { "base58check input too short" }
        val payload = raw.copyOfRange(0, raw.size - 4)
        val checksum = raw.copyOfRange(raw.size - 4, raw.size)
        val expected = Hashes.doubleSha256(payload).copyOfRange(0, 4)
        require(checksum.contentEquals(expected)) { "invalid base58check checksum" }
        return payload
    }
}
