package com.seedsigner.core.qr.ur2

/**
 * Port of seedsigner_orig/src/seedsigner/helpers/ur2/fountain_utils.py
 * (Copyright © 2020 Foundation Devices, Inc., BSD-2-Clause Plus Patent License).
 */
object FountainUtils {
    /** Fisher-Yates shuffle. */
    fun shuffled(items: MutableList<Int>, rng: Xoshiro256): List<Int> {
        val remaining = items.toMutableList()
        val result = ArrayList<Int>()
        while (remaining.isNotEmpty()) {
            val index = rng.nextInt(0, (remaining.size - 1).toLong()).toInt()
            result.add(remaining.removeAt(index))
        }
        return result
    }

    fun chooseDegree(seqLen: Int, rng: Xoshiro256): Int {
        val degreeProbabilities = (1..seqLen).map { 1.0 / it }
        val degreeChooser = RandomSampler(degreeProbabilities)
        return degreeChooser.next { rng.nextDouble() } + 1
    }

    /**
     * The first `seqLen` parts are the "pure" fragments, not mixed with any
     * others. This means that if you only generate the first `seqLen` parts,
     * then you have all the parts you need to decode the message.
     */
    fun chooseFragments(seqNum: Long, seqLen: Int, checksum: Long): Set<Int> {
        if (seqNum <= seqLen) return setOf((seqNum - 1).toInt())
        val seed = Xoshiro256.intToBytes(seqNum) + Xoshiro256.intToBytes(checksum)
        val rng = Xoshiro256.fromBytes(seed)
        val degree = chooseDegree(seqLen, rng)
        val indexes = (0 until seqLen).toMutableList()
        val shuffledIndexes = shuffled(indexes, rng)
        return shuffledIndexes.subList(0, degree).toSet()
    }
}
