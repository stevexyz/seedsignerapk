package com.seedsigner.core.crypto

import java.io.File

/**
 * Differential-testing harness (docs/spec.md §3b).
 *
 * Reads lines "op arg" from args[0], writes one result line each to args[1].
 * Driven by tools/crosscheck_bip39.py, which compares against upstream Python.
 *
 * Ops:
 *  dice <rolls>            50/99 dice rolls -> mnemonic
 *  coins <flips>           128/256 flips   -> mnemonic
 *  checksum <words>        11/23 words     -> complete mnemonic
 *  partial <flips>         <=11 flips      -> partial final word
 *  ent <hex>               entropy hex     -> mnemonic
 *  mn2ent <mnemonic>       mnemonic        -> entropy hex
 *  seed <mnemonic>\t<pw>   mnemonic,pass   -> seed hex
 */
fun main(args: Array<String>) {
    val inPath = args[0]
    val outPath = args[1]

    val out = File(inPath).readLines().filter { it.isNotBlank() }.joinToString("\n") { line ->
        val sp = line.indexOf(' ')
        val op = line.substring(0, sp)
        val arg = line.substring(sp + 1)
        when (op) {
            "dice" -> Bip39.mnemonicFromDice(arg).joinToString(" ")
            "coins" -> Bip39.mnemonicFromCoinFlips(arg).joinToString(" ")
            "checksum" -> Bip39.calculateChecksum(arg.split(" ")).joinToString(" ")
            "partial" -> Bip39.getPartialFinalWord(arg)
            "ent" -> Bip39.mnemonicFromEntropy(arg.hexToBytes())
            "mn2ent" -> Bip39.entropyFromMnemonic(arg).toHexString()
            "seed" -> {
                val tab = arg.indexOf('\t')
                Bip39.mnemonicToSeed(arg.substring(0, tab), arg.substring(tab + 1)).toHexString()
            }
            else -> "ERR:$op"
        }
    }
    File(outPath).writeText(out + "\n")
}

private fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { i ->
        (((this[i * 2].digitToInt(16)) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
    }

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
