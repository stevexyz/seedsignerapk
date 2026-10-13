package com.seedsigner.core.qr.ur2

/**
 * Port of seedsigner_orig/src/seedsigner/helpers/ur2/fountain_decoder.py
 * (Copyright © 2020 Foundation Devices, Inc., BSD-2-Clause Plus Patent License).
 */
class FountainDecoder {

    class Part(val indexes: Set<Int>, val data: ByteArray) {
        companion object {
            fun fromEncoderPart(p: com.seedsigner.core.qr.ur2.Part): Part =
                Part(FountainUtils.chooseFragments(p.seqNum, p.seqLen.toInt(), p.checksum), p.data.copyOf())
        }

        fun isSimple(): Boolean = indexes.size == 1

        fun index(): Int = indexes.first()
    }

    private val receivedPartIndexes = HashSet<Int>()
    fun receivedPartIndexes(): Set<Int> = HashSet(receivedPartIndexes)
    var lastPartIndexes: Set<Int>? = null
        private set
    private var processedPartsCount = 0
        private set
    private var result: Any? = null
    fun processedPartsCount(): Int = processedPartsCount
    private var expectedPartIndexes: Set<Int>? = null
    var expectedFragmentLen: Int? = null
        private set
    var expectedMessageLen: Long? = null
        private set
    var expectedChecksum: Long? = null
        private set
    private val simpleParts = LinkedHashMap<Set<Int>, Part>()
    private val mixedParts = LinkedHashMap<Set<Int>, Part>()
    private val queuedParts = ArrayDeque<Part>()

    fun expectedPartCount(): Int = expectedPartIndexes?.size ?: 0

    fun isComplete(): Boolean = result != null

    fun resultMessage(): ByteArray? = result as? ByteArray

    fun resultError(): Exception? = result as? Exception

    fun isFailure(): Boolean = result is Exception

    /**
     * Original estimation method: [weightMixedFrames] = false;
     * weighted mixed frame method: [weightMixedFrames] = true.
     */
    fun estimatedPercentComplete(weightMixedFrames: Boolean = false): Double {
        if (isComplete()) return 1.0
        if (expectedPartIndexes == null) return 0.0
        if (!weightMixedFrames) {
            val estimatedInputParts = expectedPartCount() * 1.75
            return minOf(0.99, processedPartsCount / estimatedInputParts)
        }
        val parts = expectedPartCount().toDouble()
        val mixedIndexScoring = HashMap<Int, Double>()
        val mixedSet = HashSet<Int>()
        for ((indexes, _) in mixedParts) {
            if (indexes.isEmpty()) continue
            mixedSet.addAll(indexes)
            val score = 1.0 / indexes.size
            for (index in indexes) {
                mixedIndexScoring[index] = (mixedIndexScoring[index] ?: 0.0) + score
            }
        }
        var mixedScore = 0.0
        for ((_, score) in mixedIndexScoring) {
            // Ceiling so a mixed frame never weighs as much as a fully decoded frame.
            mixedScore += minOf(score, 0.75)
        }
        val numComplete = receivedPartIndexes.size
        return (numComplete + mixedScore) / parts
    }

    fun receivePart(encoderPart: com.seedsigner.core.qr.ur2.Part): Boolean {
        if (isComplete()) return false
        if (!validatePart(encoderPart)) return false

        val p = Part.fromEncoderPart(encoderPart)
        lastPartIndexes = p.indexes
        queuedParts.addLast(p)

        val numComplete = receivedPartIndexes.size
        val numMixedFrames = mixedParts.size

        while (!isComplete() && queuedParts.isNotEmpty()) {
            processQueueItem()
        }

        processedPartsCount = processedPartsCount + 1

        if (numComplete == receivedPartIndexes.size && numMixedFrames == mixedParts.size) {
            // This part didn't add any new info
            return false
        }
        return true
    }

    private fun enqueue(p: Part) {
        queuedParts.addLast(p)
    }

    private fun processQueueItem() {
        val part = queuedParts.removeFirst()
        if (part.isSimple()) processSimplePart(part) else processMixedPart(part)
    }

    private fun reduceMixedBy(p: Part) {
        val reducedParts = mixedParts.values.map { reducePartByPart(it, p) }
        val newMixed = LinkedHashMap<Set<Int>, Part>()
        for (reducedPart in reducedParts) {
            if (reducedPart.isSimple()) {
                enqueue(reducedPart)
            } else {
                newMixed[reducedPart.indexes] = reducedPart
            }
        }
        mixedParts.clear()
        mixedParts.putAll(newMixed)
    }

    private fun reducePartByPart(a: Part, b: Part): Part {
        // If the fragments mixed into `b` are a strict (proper) subset of those in `a`...
        if (b.indexes.size < a.indexes.size && b.indexes.all { it in a.indexes }) {
            val newIndexes = a.indexes - b.indexes
            val newData = ByteArray(a.data.size) {
                (a.data[it].toInt() xor b.data[it].toInt()).toByte()
            }
            return Part(newIndexes, newData)
        }
        return a
    }

    private fun processSimplePart(p: Part) {
        val fragmentIndex = p.index()
        if (fragmentIndex in receivedPartIndexes) return

        simpleParts[p.indexes] = p
        receivedPartIndexes.add(fragmentIndex)

        if (receivedPartIndexes == expectedPartIndexes) {
            val sortedParts = simpleParts.values.sortedBy { it.index() }
            val message = sortedParts.flatMap { it.data.toList() }.toByteArray()
                .copyOf((expectedMessageLen ?: 0L).toInt())
            val checksum = Crc32.crc32(message)
            result = if (checksum == expectedChecksum) message
            else IllegalStateException("Invalid checksum")
        } else {
            reduceMixedBy(p)
        }
    }

    private fun processMixedPart(p: Part) {
        for (r in mixedParts.values) {
            if (r.indexes == p.indexes) return
        }
        var p2 = p
        for (r in simpleParts.values) p2 = reducePartByPart(p2, r)
        for (r in mixedParts.values) p2 = reducePartByPart(p2, r)

        if (p2.isSimple()) {
            enqueue(p2)
        } else {
            reduceMixedBy(p2)
            mixedParts[p2.indexes] = p2
        }
    }

    private fun validatePart(p: com.seedsigner.core.qr.ur2.Part): Boolean {
        if (expectedPartIndexes == null) {
            expectedPartIndexes = (0 until p.seqLen.toInt()).toSet()
            expectedMessageLen = p.messageLen
            expectedChecksum = p.checksum
            expectedFragmentLen = p.data.size
        } else {
            if (expectedPartCount() != p.seqLen.toInt()) return false
            if (expectedMessageLen != p.messageLen) return false
            if (expectedChecksum != p.checksum) return false
            if (expectedFragmentLen != p.data.size) return false
        }
        return true
    }
}
