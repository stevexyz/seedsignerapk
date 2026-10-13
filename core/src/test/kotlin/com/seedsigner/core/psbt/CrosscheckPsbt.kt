package com.seedsigner.core.psbt

import com.seedsigner.core.crypto.Bip32
import java.io.File

/**
 * Differential crosscheck entry point (driven by tools/crosscheck_psbt.py).
 * Input lines:  name \t unsigned_hex \t xprv[|xprv...] \t embit_signed_hex
 * Output lines: reserialized_unsigned_hex \t kotlin_signed_hex
 */
fun main(args: Array<String>) {
    val inPath = args[0]
    val outPath = args[1]
    val lines = File(inPath).readLines().filter { it.isNotBlank() }
    val out = lines.joinToString("\n") { line ->
        val (name, unsignedHex, keysHex, _) = line.split("\t")
        try {
            val unsigned = unsignedHex.hexToBytes()
            val keys = keysHex.split("|").map { Bip32.HDKey.parse(it) }
            val p = Psbt.parse(unsigned)
            val reser = p.serialize().toHexString()
            for (k in keys) p.signWith(k)
            reser + "\t" + p.serialize().toHexString()
        } catch (e: Exception) {
            throw RuntimeException("case $name failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }
    File(outPath).writeText(out + "\n")
}

private fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
