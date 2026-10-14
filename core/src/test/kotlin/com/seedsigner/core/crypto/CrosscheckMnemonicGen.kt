package com.seedsigner.core.crypto

import java.io.File
import java.util.Base64

/**
 * Differential crosscheck of MnemonicGeneration.kt (driven by
 * tools/crosscheck_mnemonic_gen.py). The Python side runs the upstream
 * seedsigner helpers/mnemonic_generation.py over the same inputs and dumps
 * the results to a TSV; this re-runs them through the Kotlin port and
 * compares field-for-field.
 *
 * Input lines:  name \t input_b64 \t is_bytes \t result
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
        val input = if (f[2] == "1") Base64.getDecoder().decode(f[1])
                    else Base64.getDecoder().decode(f[1]).toString(Charsets.UTF_8)
        val expected = f[3]

        val actual = when {
            name.startsWith("partial") -> MnemonicGeneration.getPartialFinalWord(input as String)
            name.startsWith("checksum") -> MnemonicGeneration.calculateChecksum(
                (input as String).split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
            ).joinToString(" ")
            name.startsWith("bytes") -> MnemonicGeneration.generateMnemonicFromBytes(input as ByteArray)
                .joinToString(" ")
            name.startsWith("dice") -> MnemonicGeneration.generateMnemonicFromDice(input as String)
                .joinToString(" ")
            else -> MnemonicGeneration.generateMnemonicFromCoinFlips(input as String)
                .joinToString(" ")
        }

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
