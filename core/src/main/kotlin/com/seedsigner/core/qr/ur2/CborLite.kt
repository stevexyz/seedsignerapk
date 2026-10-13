package com.seedsigner.core.qr.ur2

import java.io.ByteArrayOutputStream

/**
 * Port of seedsigner_orig/src/seedsigner/helpers/ur2/cbor_lite.py
 * (from CBOR-lite by Isode Limited, MIT; wrapper Copyright © 2020 Foundation Devices, Inc.).
 */
private const val FlagNone = 0
private const val FlagRequireMinimalEncoding = 1

private const val TagMajorUnsignedInteger = 0
private const val TagMajorNegativeInteger = 1 shl 5
private const val TagMajorByteString = 2 shl 5
private const val TagMajorTextString = 3 shl 5
private const val TagMajorArray = 4 shl 5
private const val TagMajorMap = 5 shl 5
private const val TagMajorSemantic = 6 shl 5
private const val TagMajorSimple = 7 shl 5
private const val TagMajorMask = 0xe0

private const val TagMinorLength1 = 24
private const val TagMinorLength2 = 25
private const val TagMinorLength4 = 26
private const val TagMinorLength8 = 27

private const val TagMinorFalse = 20
private const val TagMinorTrue = 21
private const val TagMinorCborEncodedData = 24
private const val TagMinorMask = 0x1f

class CborEncoder {
    private val buf = ByteArrayOutputStream()

    fun getBytes(): ByteArray = buf.toByteArray()

    private fun encodeTagAndAdditional(tag: Int, additional: Int) {
        buf.write(tag + additional)
    }

    private fun encodeTagAndValue(tag: Int, value: Long): Int {
        val length = getByteLength(value)
        when {
            length >= 5 && length <= 8 -> {
                encodeTagAndAdditional(tag, TagMinorLength8)
                buf.write(((value ushr 56) and 0xFF).toInt())
                buf.write(((value ushr 48) and 0xFF).toInt())
                buf.write(((value ushr 40) and 0xFF).toInt())
                buf.write(((value ushr 32) and 0xFF).toInt())
                buf.write(((value ushr 24) and 0xFF).toInt())
                buf.write(((value ushr 16) and 0xFF).toInt())
                buf.write(((value ushr 8) and 0xFF).toInt())
                buf.write((value and 0xFF).toInt())
            }
            length == 3 || length == 4 -> {
                encodeTagAndAdditional(tag, TagMinorLength4)
                buf.write(((value ushr 24) and 0xFF).toInt())
                buf.write(((value ushr 16) and 0xFF).toInt())
                buf.write(((value ushr 8) and 0xFF).toInt())
                buf.write((value and 0xFF).toInt())
            }
            length == 2 -> {
                encodeTagAndAdditional(tag, TagMinorLength2)
                buf.write(((value ushr 8) and 0xFF).toInt())
                buf.write((value and 0xFF).toInt())
            }
            length == 1 -> {
                encodeTagAndAdditional(tag, TagMinorLength1)
                buf.write((value and 0xFF).toInt())
            }
            length == 0 -> encodeTagAndAdditional(tag, value.toInt())
            else -> throw IllegalArgumentException("Unsupported byte length of $length")
        }
        return 1 + length
    }

    fun encodeUnsigned(value: Long) = encodeTagAndValue(TagMajorUnsignedInteger, value)

    fun encodeNegative(value: Long) = encodeTagAndValue(TagMajorNegativeInteger, value)

    fun encodeInteger(value: Long): Int =
        if (value >= 0) encodeUnsigned(value) else encodeNegative(value)

    fun encodeBool(value: Boolean) =
        encodeTagAndValue(TagMajorSimple, (if (value) TagMinorTrue else TagMinorFalse).toLong())

    fun encodeBytes(value: ByteArray): Int {
        val length = encodeTagAndValue(TagMajorByteString, value.size.toLong())
        buf.write(value)
        return length + value.size
    }

    fun encodeEncodedBytes(value: ByteArray): Int {
        val length = encodeTagAndValue(TagMajorSemantic, TagMinorCborEncodedData.toLong())
        return length + encodeBytes(value)
    }

    fun encodeText(value: String): Int {
        val strLen = value.length
        val length = encodeTagAndValue(TagMajorTextString, strLen.toLong())
        buf.write(value.toByteArray(Charsets.UTF_8))
        return length + strLen
    }

    fun encodeArraySize(value: Long) = encodeTagAndValue(TagMajorArray, value)

    fun encodeMapSize(value: Long) = encodeTagAndValue(TagMajorMap, value)

    companion object {
        private fun getByteLength(value: Long): Int {
            if (value < 24) return 0
            return (64 - value.countLeadingZeroBits() + 7) / 8
        }
    }
}

class CborDecoder(buf: ByteArray) {
    private val buf = buf
    var pos = 0
        private set

    private fun decodeTagAndAdditional(flags: Int = FlagNone): Triple<Int, Int, Int> {
        if (pos == buf.size) throw IllegalArgumentException("Not enough input")
        val octet = buf[pos].toInt() and 0xFF
        pos += 1
        val tag = octet and TagMajorMask
        val additional = octet and TagMinorMask
        return Triple(tag, additional, 1)
    }

    private fun decodeTagAndValue(flags: Int): Triple<Int, Long, Int> {
        val end = buf.size
        if (pos == end) throw IllegalArgumentException("Not enough input")
        val (tag, additional, _) = decodeTagAndAdditional(flags)
        if (additional < TagMinorLength1) {
            return Triple(tag, additional.toLong(), 1)
        }
        var value = 0L
        when (additional) {
            TagMinorLength8 -> {
                if (end - pos < 8) throw IllegalArgumentException("Not enough input")
                for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
                    value = value or ((buf[pos].toLong() and 0xFF) shl shift)
                    pos += 1
                }
                if ((flags and FlagRequireMinimalEncoding) != 0 && value == 0L)
                    throw IllegalArgumentException("Encoding not minimal")
                return Triple(tag, value, pos)
            }
            TagMinorLength4 -> {
                if (end - pos < 4) throw IllegalArgumentException("Not enough input")
                for (shift in intArrayOf(24, 16, 8, 0)) {
                    value = value or ((buf[pos].toLong() and 0xFF) shl shift)
                    pos += 1
                }
                if ((flags and FlagRequireMinimalEncoding) != 0 && value == 0L)
                    throw IllegalArgumentException("Encoding not minimal")
                return Triple(tag, value, pos)
            }
            TagMinorLength2 -> {
                if (end - pos < 2) throw IllegalArgumentException("Not enough input")
                for (shift in intArrayOf(8, 0)) {
                    value = value or ((buf[pos].toLong() and 0xFF) shl shift)
                    pos += 1
                }
                if ((flags and FlagRequireMinimalEncoding) != 0 && value == 0L)
                    throw IllegalArgumentException("Encoding not minimal")
                return Triple(tag, value, pos)
            }
            TagMinorLength1 -> {
                if (end - pos < 1) throw IllegalArgumentException("Not enough input")
                value = buf[pos].toLong() and 0xFF
                pos += 1
                if ((flags and FlagRequireMinimalEncoding) != 0 && value == 0L)
                    throw IllegalArgumentException("Encoding not minimal")
                return Triple(tag, value, pos)
            }
        }
        throw IllegalArgumentException("Bad additional value")
    }

    fun decodeUnsigned(flags: Int = FlagNone): Pair<Long, Int> {
        val (tag, value, length) = decodeTagAndValue(flags)
        require(tag == TagMajorUnsignedInteger) { "Expected Tag_Major_unsignedInteger, found $tag" }
        return value to length
    }

    fun decodeInteger(flags: Int = FlagNone): Pair<Long, Int> {
        val (tag, value, length) = decodeTagAndValue(flags)
        return when (tag) {
            TagMajorUnsignedInteger -> value to length
            TagMajorNegativeInteger -> -1L - value to length
            else -> throw IllegalArgumentException("Not an integer")
        }
    }

    fun decodeBool(flags: Int = FlagNone): Pair<Boolean, Int> {
        val (tag, value, length) = decodeTagAndValue(flags)
        require(tag == TagMajorSimple) { "Not Simple/Boolean" }
        return when (value) {
            TagMinorTrue.toLong() -> true to length
            TagMinorFalse.toLong() -> false to length

            else -> throw IllegalArgumentException("Not a Boolean")
        }
    }

    fun decodeBytes(flags: Int = FlagNone): Pair<ByteArray, Int> {
        val (tag, byteLength, sizeLength) = decodeTagAndValue(flags)
        require(tag == TagMajorByteString) { "Not a byteString" }
        if (buf.size - pos < byteLength) throw IllegalArgumentException("Not enough input")
        val value = buf.copyOfRange(pos, pos + byteLength.toInt())
        pos += byteLength.toInt()
        return value to (sizeLength + byteLength.toInt())
    }

    fun decodeEncodedBytes(flags: Int = FlagNone): Pair<ByteArray, Int> {
        val (tag, minorTag, tagLength) = decodeTagAndValue(flags)
        require(tag == TagMajorSemantic && minorTag == TagMinorCborEncodedData.toLong()) { "Not CBOR Encoded Data" }
        val (value, length) = decodeBytes(flags)
        return value to (tagLength + length)
    }

    fun decodeText(flags: Int = FlagNone): Pair<String, Int> {
        val (tag, byteLength, sizeLength) = decodeTagAndValue(flags)
        require(tag == TagMajorTextString) { "Not a textString" }
        if (buf.size - pos < byteLength) throw IllegalArgumentException("Not enough input")
        val value = String(buf, pos, byteLength.toInt(), Charsets.UTF_8)
        pos += byteLength.toInt()
        return value to (sizeLength + byteLength.toInt())
    }

    fun decodeArraySize(flags: Int = FlagNone): Pair<Long, Int> {
        val (tag, value, length) = decodeTagAndValue(flags)
        require(tag == TagMajorArray) { "Expected Tag_Major_array, found $tag" }
        return value to length
    }

    fun decodeMapSize(flags: Int = FlagNone): Pair<Long, Int> {
        val (tag, value, length) = decodeTagAndValue(flags)
        require(tag == TagMajorMap) { "Expected Tag_Major_map, found $tag" }
        return value to length
    }

}
