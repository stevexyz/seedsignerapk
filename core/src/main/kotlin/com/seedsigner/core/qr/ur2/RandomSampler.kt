package com.seedsigner.core.qr.ur2

/**
 * Port of seedsigner_orig/src/seedsigner/helpers/ur2/random_sampler.py
 * (Copyright © 2020 Foundation Devices, Inc., BSD-2-Clause Plus Patent License).
 * Alias-method sampler (Schwartz).
 */
class RandomSampler(input: List<Double>) {
    val probs: DoubleArray
    val aliases: IntArray

    init {
        val probs = input
        require(probs.all { it > 0 })
        val total = probs.sum()
        require(total > 0)
        val n = probs.size
        val p = DoubleArray(n) { (probs[it] * n) / total }
        val s = ArrayList<Int>()
        val l = ArrayList<Int>()
        // Set separate index lists for small and large probabilities
        // (at variance from Schwartz, we reverse the index order)
        for (i in n - 1 downTo 0) {
            if (p[i] < 1) s.add(i) else l.add(i)
        }
        val probsOut = DoubleArray(n)
        val aliasesOut = IntArray(n)
        while (s.isNotEmpty() && l.isNotEmpty()) {
            val a = s.removeAt(s.size - 1)   // Schwartz's l
            val g = l.removeAt(l.size - 1)   // Schwartz's g
            probsOut[a] = p[a]
            aliasesOut[a] = g
            p[g] += p[a] - 1
            if (p[g] < 1) s.add(g) else l.add(g)
        }
        while (l.isNotEmpty()) probsOut[l.removeAt(l.size - 1)] = 1.0
        while (s.isNotEmpty()) probsOut[s.removeAt(s.size - 1)] = 1.0
        this.probs = probsOut
        this.aliases = aliasesOut
    }

    fun next(rngFunc: () -> Double): Int {
        val r1 = rngFunc()
        val r2 = rngFunc()
        val n = probs.size
        val i = (n * r1).toInt()
        return if (r2 < probs[i]) i else aliases[i]
    }
}
