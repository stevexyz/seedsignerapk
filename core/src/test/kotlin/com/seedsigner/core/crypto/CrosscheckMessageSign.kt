package com.seedsigner.core.crypto

import java.io.File
import java.util.Base64

/**
 * Differential crosscheck of MessageSigner.kt (driven by
 * tools/crosscheck_message_sign.py). The Python side runs the upstream
 * seedsigner helpers/embit_utils.py sign_message; this re-runs the same
 * derivations through the Kotlin port and compares base64 signatures.
 *
 * Input lines:  name \t seed_b64 \t derivation \t msg_b64 \t network \t signature
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
        val seed = Base64.getDecoder().decode(f[1])
        val derivation = f[2]
        val msg = Base64.getDecoder().decode(f[3])
        val network = Networks.fromName(f[4])
        val expected = f[5]

        val actual = MessageSigner.signMessage(seed, derivation, msg, compressed = true, network = network)

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
