package com.seedsigner.core.crypto

import java.io.File
import java.util.Base64

/**
 * Differential crosscheck of Bip85.kt (driven by tools/crosscheck_bip85.py).
 * The Python side runs embit.bip85 (the module upstream SeedSigner calls)
 * plus the official BIP-85 spec vectors; this re-runs the same derivations
 * through the Kotlin port and compares.
 *
 * Input lines:  name \t root_kind \t root \t num_words \t index \t result
 *   root_kind: "seed" (b64 seed bytes) or "xprv" (utf-8 b64 base58 string)
 * Output lines: name \t ok|FAIL <expected> != <actual>
 */
fun main(args: Array<String>) {
    val inPath = args[0]
    val outPath = args[1]
    val lines = File(inPath).readLines().filter { it.isNotBlank() }
    var failures = 0

    val out = lines.drop(1).map { line ->
        val f = line.split("\t")
        val name = f[0]
        val root = if (f[1] == "xprv") {
            Bip32.HDKey.parse(Base64.getDecoder().decode(f[2]).toString(Charsets.UTF_8))
        } else {
            Bip32.HDKey.fromSeed(Base64.getDecoder().decode(f[2]))
        }
        val numWords = f[3].toInt()
        val index = f[4].toInt()
        val expected = f[5]

        val actual = Bip85.deriveMnemonic(root, numWords, index).joinToString(" ")

        if (actual == expected) "$name\tok" else {
            failures++
            "$name\tFAIL expected=<$expected> actual=<$actual>"
        }
    }

    File(outPath).writeText(out.joinToString("\n") + "\n")
    out.filter { it.contains("FAIL") }.forEach { println(it) }
    println(if (failures == 0) "ALL ${out.size} CASES MATCH" else "$failures/${out.size} FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}
