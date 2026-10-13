package com.seedsigner.core.crypto

/**
 * Bech32 (BIP-173) and Bech32m (BIP-350) encode/decode.
 *
 * SegWit v0 addresses use bech32; v1+ (taproot) use bech32m.
 */
object Bech32 {

    enum class Encoding { BECH32, BECH32M }

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    private val INDEX = IntArray(128) { -1 }.also {
        CHARSET.forEachIndexed { i, c -> it[c.code] = i }
    }

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                // BIP-173 reference: chk ^= generator[i] if bit i of top is set
                if (((b ushr i) and 1) != 0) chk = chk xor GENERATOR[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            out[i] = hrp[i].code ushr 5
            out[hrp.length + 1 + i] = hrp[i].code and 31
        }
        out[hrp.length] = 0
        return out
    }

    private fun createChecksum(hrp: String, data: IntArray, encoding: Encoding): IntArray {
        val CONSTANT = if (encoding == Encoding.BECH32) 1 else 0x2bc830a3
        val values = hrpExpand(hrp) + data + intArrayOf(0, 0, 0, 0, 0, 0)
        val mod = polymod(values) xor CONSTANT
        return IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
    }

    /** [hrp, witnessVersion, witnessProgram] -> bech32/bech32m string. */
    fun encodeAddress(hrp: String, witnessVersion: Int, program: ByteArray, encoding: Encoding): String {
        require(witnessVersion in 0..16) { "invalid witness version" }
        require(program.size in 2..40) { "invalid witness program length" }
        val programInts = IntArray(program.size) { program[it].toInt() and 0xff }
        val data = intArrayOf(witnessVersion) +
            convertBits(programInts, 8, 5, true).map { it.toInt() and 31 }.toIntArray()
        val checksum = createChecksum(hrp, data, encoding)
        val sb = StringBuilder(hrp).append('1')
        (data + checksum).forEach { sb.append(CHARSET[it]) }
        return sb.toString()
    }

    data class Address(val hrp: String, val witnessVersion: Int, val program: ByteArray)

    fun decodeAddress(address: String): Address {
        val lower = address.lowercase()
        val upper = address.uppercase()
        require(lower == address || upper == address) { "mixed-case bech32 string" }
        val s = lower
        val pos = s.lastIndexOf('1')
        require(pos >= 1 && pos <= 32) { "invalid separator position" }
        val hrp = s.substring(0, pos)
        val data = s.substring(pos + 1).map {
            val v = if (it.code < 128) INDEX[it.code] else -1
            require(v >= 0) { "invalid character: $it" }
            v
        }.toIntArray()
        require(data.size >= 6) { "checksum too short" }
        val isBech32m = polymod(hrpExpand(hrp) + data) == 0x2bc830a3
        val isBech32 = polymod(hrpExpand(hrp) + data) == 1
        require(isBech32 || isBech32m) { "invalid checksum" }
        val encoding = if (isBech32m) Encoding.BECH32M else Encoding.BECH32
        val payload = data.copyOfRange(0, data.size - 6)
        val witnessVersion = payload[0]
        require(witnessVersion != 0 || encoding == Encoding.BECH32) {
            "v0 addresses must use bech32, not bech32m"
        }
        val program = convertBits(payload.copyOfRange(1, payload.size), 5, 8, false)
        require(program.size in 2..40) { "invalid witness program length" }
        return Address(hrp, witnessVersion, program)
    }

    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>()
        val maxV = (1 shl toBits) - 1
        for (value in data) {
            require(value ushr fromBits == 0) { "input value out of range" }
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.add(((acc ushr bits) and maxV).toByte())
            }
        }
        if (pad) {
            if (bits > 0) out.add(((acc shl (toBits - bits)) and maxV).toByte())
        } else {
            require(bits < fromBits && ((acc shl (toBits - bits)) and maxV) == 0) {
                "illegal zero bits in input"
            }
        }
        return out.toByteArray()
    }
}
