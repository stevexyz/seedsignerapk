package com.seedsigner.core.qr.ur2

/**
 * Port of seedsigner_orig/src/seedsigner/helpers/ur2/fountain_encoder.py
 * (Copyright © 2020 Foundation Devices, Inc., BSD-2-Clause Plus Patent License).
 */
class Part(val seqNum: Long, val seqLen: Long, val messageLen: Long, val checksum: Long, val data: ByteArray) {

    fun cbor(): ByteArray {
        val encoder = CborEncoder()
        encoder.encodeArraySize(5)
        encoder.encodeInteger(seqNum)
        encoder.encodeInteger(seqLen)
        encoder.encodeInteger(messageLen)
        encoder.encodeInteger(checksum)
        encoder.encodeBytes(data)
        return encoder.getBytes()
    }

    companion object {
        fun fromCbor(cborBuf: ByteArray): Part {
            val decoder = CborDecoder(cborBuf)
            val (arraySize, _) = decoder.decodeArraySize()
            require(arraySize == 5L) { "Invalid header" }
            val (seqNum, _) = decoder.decodeUnsigned()
            val (seqLen, _) = decoder.decodeUnsigned()
            val (messageLen, _) = decoder.decodeUnsigned()
            val (checksum, _) = decoder.decodeUnsigned()
            val (data, _) = decoder.decodeBytes()
            return Part(seqNum, seqLen, messageLen, checksum, data)
        }
    }
}

class FountainEncoder(message: ByteArray, private val maxFragmentLen: Int,
                      firstSeqNum: Long = 0, minFragmentLen: Int = 10) {
    val messageLen: Int = message.size
    val checksum: Long = Crc32.crc32(message)
    private val fragmentLen: Int =
        findNominalFragmentLength(message.size, minFragmentLen, maxFragmentLen)
    private val fragments: List<ByteArray> = partitionMessage(message, fragmentLen)
    var seqNum: Long = firstSeqNum
        private set
    var currentPart: Part? = null
        private set

    fun lastPartIndexes(): List<Int>? = null

    fun seqLen(): Int = fragments.size

    /** True when the minimum number of parts to relay the complete message have been generated. */
    fun isComplete(): Boolean = seqNum >= seqLen()

    fun isSinglePart(): Boolean = seqLen() == 1

    fun nextPart(): Part {
        seqNum += 1
        seqNum = seqNum % 0x100000000L // wrap at period 2^32
        val indexes = FountainUtils.chooseFragments(seqNum, seqLen(), checksum)
        val mixed = mix(indexes)
        currentPart = Part(seqNum, seqLen().toLong(), messageLen.toLong(), checksum, mixed)
        return currentPart!!
    }

    /** Restart from the beginning; each cycle's first n frames are full data frames. */
    fun restart() {
        seqNum = 0
    }

    private fun mix(indexes: Set<Int>): ByteArray {
        val result = ByteArray(fragmentLen)
        for (index in indexes) {
            val f = fragments[index]
            for (i in result.indices) result[i] = (result[i].toInt() xor f[i].toInt()).toByte()
        }
        return result
    }

    companion object {
        private fun findNominalFragmentLength(messageLen: Int, minFragmentLen: Int, maxFragmentLen: Int): Int {
            require(messageLen > 0 && minFragmentLen > 0 && maxFragmentLen >= minFragmentLen)
            val maxFragmentCount = messageLen / minFragmentLen
            var fragmentLen = 0
            for (fragmentCount in 1..maxFragmentCount) {
                fragmentLen = Math.ceil(messageLen.toDouble() / fragmentCount).toInt()
                if (fragmentLen <= maxFragmentLen) break
            }
            return fragmentLen
        }

        private fun partitionMessage(message: ByteArray, fragmentLen: Int): List<ByteArray> {
            val fragments = ArrayList<ByteArray>()
            var offset = 0
            while (offset < message.size) {
                val end = minOf(offset + fragmentLen, message.size)
                val fragment = message.copyOfRange(offset, end)
                val padded = ByteArray(fragmentLen)
                fragment.copyInto(padded)
                fragments.add(padded)
                offset = end
            }
            return fragments
        }
    }
}
