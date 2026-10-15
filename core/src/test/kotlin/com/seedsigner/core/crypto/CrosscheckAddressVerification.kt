package com.seedsigner.core.crypto

import java.io.File

/**
 * Address-verification crosscheck: replays tools/out/address_verification_cases.tsv
 * through the Kotlin ports and compares every field byte-for-byte:
 *
 *  - "addr" rows: derive the receive/change address for a seed/script-type/
 *    network/index via Address.singleSigAddress (upstream
 *    embit_utils.get_single_sig_address).
 *  - "det" rows: recognize/classify the QR text as an address
 *    (upstream decode_qr.py BitcoinAddressQrDecoder).
 */
class CrosscheckAddressVerification(private val tsv: File) {

    // Upstream settings codes -> our core names.
    private val scriptMap = mapOf("leg" to "p2pkh", "nes" to "p2sh-p2wpkh", "nat" to "p2wpkh", "tr" to "p2tr")
    private val networkCode = mapOf("main" to "M", "test" to "T", "regtest" to "R")
    private val scriptCode = scriptMap.entries.associate { (k, v) -> v to k }

    fun run(): List<String> {
        // Deterministic mnemonics mirroring the Python generator.
        // Third phrase: embit bip39.mnemonic_from_bytes(bytes(range(32)))
        // output (trusted source, session-verified).
        val phrases = listOf(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            "abandon amount liar amount expire adjust cage candy arch gather drum bullet absurd math era live bid rhythm alien crouch range attend journey unaware"
        )

        val results = mutableListOf<String>()
        tsv.readLines().forEachIndexed { rowIdx, line ->
            if (line.isBlank()) return@forEachIndexed
            val parts = line.split("\t")
            check(parts.size == 3) { "bad row $rowIdx: $line" }
            val (kind, arg, expected) = parts
            val actual = when (kind) {
                "addr" -> addrRow(phrases, arg)
                "det" -> detRow(arg)
                else -> throw IllegalStateException("unknown kind '$kind' row $rowIdx")
            }
            if (actual != expected) {
                throw AssertionError("row $rowIdx [$kind] $arg:\n  expected: $expected\n  actual:   $actual")
            }
            results.add("row $rowIdx OK")
        }
        return results
    }

    private fun addrRow(phrases: List<String>, arg: String): String {
        val f = arg.split("|")
        val seedIdx = f[0].toInt()
        val net = f[1]
        val script = f[2]
        val idx = f[3].toInt()
        val isChange = f[4].toInt()
        val phrase = phrases[seedIdx]
        val seed = Bip39.mnemonicToSeed(phrase)
        val network = Networks.fromName(net)
        // Upstream builds the xpub from the xprv-versioned root (embit
        // bip32.HDKey.from_seed(seed, version=NETWORKS[net]["xprv"])).
        val root = Bip32.HDKey.fromSeed(seed, version = network.versions["xprv"]!!)
        // Standard paths per upstream get_standard_derivation_path
        // (regtest shares the testnet path 1').
        val networkPath = if (net == "main") "0'" else "1'"
        val path = when (script) {
            "leg" -> "m/44'/$networkPath/0'"
            "nes" -> "m/49'/$networkPath/0'"
            "nat" -> "m/84'/$networkPath/0'"
            else -> "m/86'/$networkPath/0'"
        }
        val xpub = Bip32.derive(root, path)
        return Address.singleSigAddress(xpub, scriptMap.getValue(script), idx, isChange == 1, network)
    }

    private fun detRow(segment: String): String {
        val triple = AddressType.detectWithAddress(segment) ?: return "none"
        val (addr, script, net) = triple
        return "$addr;${scriptCode.getValue(script)};${networkCode.getValue(net)}"
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val tsv = File(args.getOrNull(0) ?: "tools/out/address_verification_cases.tsv")
            check(tsv.isFile) { "TSV not found: ${tsv.absolutePath}" }
            val results = CrosscheckAddressVerification(tsv).run()
            check(results.isNotEmpty()) { "TSV had no rows" }
            println("Address-verification crosscheck: ${results.size}/${results.size} rows byte-identical")
        }
    }
}
