package com.seedsigner.core.qr.ur2

import java.io.File

/**
 * Crosscheck reader for `tools/crosscheck_urtypes.py`.
 *
 * Input TSV rows:
 *   xpub<i> \t xpub \t root_fp \t key \t chain_code \t parent_fp \t derivation \t network \t tags \t <expected>
 *   psbt<i> \t psbt \t <psbt_hex> \t <expected>
 *
 * Writes: case_id \t <actual_cbor_hex>
 */
fun main(args: Array<String>) {
    val inFile = File(args.getOrNull(0) ?: "/tmp/urtypes_in.tsv")
    val outFile = File(args.getOrNull(1) ?: "/tmp/urtypes_out.tsv")

    val sb = StringBuilder()
    for (line in inFile.readLines()) {
        if (line.isBlank()) continue
        val p = line.split("\t")
        val caseId = p[0]
        val kind = p[1]
        val hex = when (kind) {
            "xpub" -> {
                val outputs: List<List<Long>> = if (p[8].isEmpty()) emptyList()
                    else p[8].split("|").map { group -> group.split(",").map { t -> t.toLong() } }
                UrTypes.xpubAccountCbor(
                    rootFingerprint = p[2].hexToBytes(),
                    key = p[3].hexToBytes(),
                    chainCode = p[4].hexToBytes(),
                    parentFingerprint = p[5].hexToBytes(),
                    derivation = p[6],
                    network = p[7],
                    scriptTags = outputs
                ).toHex()
            }
            "psbt" -> UrTypes.psbtCbor(p[2].hexToBytes()).toHex()
            else -> throw IllegalArgumentException("unknown kind $kind")
        }
        sb.append(caseId).append('\t').append(hex).append('\n')
    }
    outFile.writeText(sb.toString())
    println("Wrote ${outFile}")
}

private fun String.hexToBytes(): ByteArray {
    val b = ByteArray(length / 2)
    for (i in b.indices) b[i] = ((this[2*i].digitToInt(16) shl 4) + this[2*i+1].digitToInt(16)).toByte()
    return b
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
