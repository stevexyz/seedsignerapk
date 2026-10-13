package com.seedsigner.core.qr.ur2

/**
 * Ports of seedsigner_orig/src/seedsigner/helpers/ur2/ur.py, ur_encoder.py,
 * and ur_decoder.py (Copyright © 2020 Foundation Devices, Inc.,
 * BSD-2-Clause Plus Patent License).
 */
data class UR(val type: String, val cbor: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is UR && type == other.type && cbor.contentEquals(other.cbor)

    override fun hashCode(): Int = 31 * type.hashCode() + cbor.contentHashCode()

    companion object {
        fun isUrType(s: String): Boolean =
            s.isNotEmpty() && s.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }
}

class UREncoder(val ur: UR, maxFragmentLen: Int, firstSeqNum: Long = 0, minFragmentLen: Int = 10) {
    val fountainEncoder = FountainEncoder(ur.cbor, maxFragmentLen, firstSeqNum, minFragmentLen)

    fun lastPartIndexes(): List<Int>? = fountainEncoder.lastPartIndexes()

    fun isComplete(): Boolean = fountainEncoder.isComplete()

    fun isSinglePart(): Boolean = fountainEncoder.isSinglePart()

    fun nextPart(): String =
        if (isSinglePart()) UREncoder.encode(ur)
        else UREncoder.encodePart(ur.type, fountainEncoder.nextPart())

    fun currentPart(): String =
        if (isSinglePart()) UREncoder.encode(ur)
        else UREncoder.encodePart(
            ur.type,
            fountainEncoder.currentPart ?: fountainEncoder.nextPart()
        )

    fun restart() = fountainEncoder.restart()

    companion object {
        /** Encode a single-part UR. */
        fun encode(ur: UR): String {
            val body = Bytewords.encode(Bytewords.StyleMinimal, ur.cbor)
            return encodeUr(listOf(ur.type, body))
        }

        fun encodePart(type: String, part: Part): String {
            val seq = "${part.seqNum}-${part.seqLen}"
            val body = Bytewords.encode(Bytewords.StyleMinimal, part.cbor())
            return encodeUr(listOf(type, seq, body))
        }

        fun encodeUri(scheme: String, pathComponents: List<String>): String =
            "$scheme:${pathComponents.joinToString("/")}"

        fun encodeUr(pathComponents: List<String>): String =
            encodeUri("ur", pathComponents)
    }
}

class URDecoder {
    private val fountainDecoder = FountainDecoder()
    var expectedType: String? = null
        private set
    var result: Any? = null
        private set

    fun isComplete(): Boolean = result != null

    fun isFailure(): Boolean = result is Exception

    fun resultMessage(): UR? = result as? UR

    fun resultError(): Exception? = result as? Exception

    fun expectedPartCount(): Int = fountainDecoder.expectedPartCount()

    fun receivedPartIndexes(): Set<Int> = fountainDecoder.receivedPartIndexes()

    fun lastPartIndexes(): Set<Int>? = fountainDecoder.lastPartIndexes

    fun processedPartsCount(): Int = fountainDecoder.processedPartsCount()

    fun estimatedPercentComplete(weightMixedFrames: Boolean = false): Double =
        fountainDecoder.estimatedPercentComplete(weightMixedFrames)

    /**
     * Feed one QR string (a UR part). Returns true if the part was new/useful.
     * [result] becomes the decoded [UR] on success, an [Exception] on failure.
     */
    fun receivePart(str: String): Boolean {
        if (result != null) return false
        return try {
            val (type, components) = parse(str)
            if (!validatePart(type)) return false

            // Single-part UR: done.
            if (components.size == 1) {
                result = decodeByType(type, components[0])
                return true
            }

            // Multi-part URs have two path components: seq/fragment.
            if (components.size != 2) throw IllegalArgumentException("Invalid path length")
            val (seqNum, seqLen) = parseSequenceComponent(components[0])
            val cbor = Bytewords.decode(Bytewords.StyleMinimal, components[1])
            val part = Part.fromCbor(cbor)
            if (seqNum != part.seqNum || seqLen != part.seqLen) return false

            if (!fountainDecoder.receivePart(part)) return false

            if (fountainDecoder.resultMessage() != null) {
                result = UR(type, fountainDecoder.resultMessage()!!)
            } else if (fountainDecoder.resultError() != null) {
                result = fountainDecoder.resultError()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun validatePart(type: String): Boolean {
        return if (expectedType == null) {
            if (!UR.isUrType(type)) return false
            expectedType = type
            true
        } else {
            type == expectedType
        }
    }

    companion object {
        fun decode(str: String): UR {
            val (type, components) = parse(str)
            require(components.isNotEmpty()) { "Invalid path length" }
            return decodeByType(type, components[0])
        }

        fun decodeByType(type: String, body: String): UR {
            val cbor = Bytewords.decode(Bytewords.StyleMinimal, body)
            return UR(type, cbor)
        }

        fun parse(str: String): Pair<String, List<String>> {
            val lowered = str.lowercase()
            require(lowered.startsWith("ur:")) { "Invalid scheme" }
            val path = lowered.drop(3)
            val components = path.split("/")
            require(components.size >= 2) { "Invalid path length" }
            val type = components[0]
            require(UR.isUrType(type)) { "Invalid type" }
            return type to components.subList(1, components.size)
        }

        fun parseSequenceComponent(str: String): Pair<Long, Long> {
            val comps = str.split("-")
            require(comps.size == 2) { "Invalid sequence component" }
            val seqNum = comps[0].toLong()
            val seqLen = comps[1].toLong()
            require(seqNum >= 1 && seqLen >= 1) { "Invalid sequence component" }
            return seqNum to seqLen
        }
    }
}
