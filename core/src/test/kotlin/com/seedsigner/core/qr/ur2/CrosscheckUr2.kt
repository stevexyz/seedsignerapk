package com.seedsigner.core.qr.ur2

import java.io.File

/**
 * Differential crosscheck entry point (driven by tools/crosscheck_ur2.py).
 * Input lines:  maxfrag \t payload_hex \t part1 \t part2 \t ...
 * Decodes each case (parts fed in REVERSED order to exercise the fountain
 * decoder), verifies the cbor equals payload_hex, re-encodes with the same
 * max_fragment_len, and writes the parts back out unchanged.
 */
fun main(args: Array<String>) {
    val inPath = args[0]
    val outPath = args[1]
    val lines = File(inPath).readLines().filter { it.isNotBlank() }
    val out = lines.joinToString("\n") { line ->
        val cols = line.split("\t")
        val maxFrag = cols[0].toInt()
        val payload = cols[1].hexToBytes()
        val parts = cols.drop(2)

        // Decode: feed parts reversed so mixed frames are processed out of order.
        val decoder = URDecoder()
        for (part in parts.asReversed()) {
            check(decoder.receivePart(part)) { "part rejected: ${part.take(40)}" }
        }
        check(decoder.resultMessage() != null) { "decode did not complete" }
        val ur = decoder.resultMessage()!!
        check(ur.type == "seedsigner") { "type ${ur.type}" }
        check(ur.cbor.contentEquals(payload)) { "cbor != payload (${ur.cbor.size} vs ${payload.size})" }

        // Re-encode with the same parameters; parts must come back byte-identical.
        val encoder = UREncoder(ur, maxFrag)
        val reencoded = (1..parts.size).map { encoder.nextPart() }
        check(reencoded == parts) { "re-encoded parts differ" }

        reencoded.joinToString("\t", prefix = "${maxFrag}\t${payload.hex()}\t")
    }
    File(outPath).writeText(out + "\n")
}

private fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
