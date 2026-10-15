package com.seedsigner.core.descriptor

import com.seedsigner.core.crypto.Bip32
import com.seedsigner.core.crypto.Networks
import com.seedsigner.core.psbt.DerivationPath
import java.io.File
import java.util.HexFormat

/**
 * Descriptor crosscheck: replays tools/out/descriptor_cases.tsv and compares
 * every field to the embit 0.8.0 results (parse/to_string, policy,
 * get_multisig_address over networks/indices/branches, owns(), rejects).
 */
class CrosscheckDescriptor(private val tsv: File) {

    companion object {
        // Deterministic xpubs at m/48h/0h/2h, mirroring the Python generator:
        //   seeds bytes(1..32), bytes(2..33), bytes(3..34), mainnet version.
        fun makeXpub(offset: Int): Pair<Bip32.HDKey, ByteArray> {
            val seed = ByteArray(32) { ((offset + 1 + it) % 256).toByte() }
            val root = Bip32.HDKey.fromSeed(seed, version = Networks.MAINNET.versions["xprv"]!!)
            val account = Bip32.derive(root, "m/48h/0h/2h").toPublic()
            // Parent node's fingerprint (BIP-32 origin convention).
            val parentFp = intToBe4(Bip32.derive(root, "m/48h/0h").let { Bip32.child(it, 0) }.fingerprintOfChild())
            return account to parentFp
        }

        fun intToBe4(i: Int): ByteArray = byteArrayOf(
            (i shr 24).toByte(), (i shr 16).toByte(), (i shr 8).toByte(), i.toByte()
        )

        fun keyStr(xpub: Bip32.HDKey, fp: ByteArray): String =
            "[${HexFormat.of().formatHex(fp)}/48h/0h/2h]${xpub.serialize(Networks.MAINNET, "xpub")}/{0,1}/*"

        val XPUB_STRS: List<String> = (0 until 3).map { keyStr(makeXpub(it).first, makeXpub(it).second) }

        val DESCRIPTORS = mapOf(
            "wsh" to "wsh(sortedmulti(2," + XPUB_STRS.joinToString(",") + "))",
            "shwsh" to "sh(wsh(sortedmulti(2," + XPUB_STRS.joinToString(",") + ")))",
            "shmulti" to "sh(multi(2," + XPUB_STRS.joinToString(",") + "))",
            "wsh21" to "wsh(sortedmulti(1," + XPUB_STRS.take(2).joinToString(",") + "))"
        )
    }

    // Top-level key origins for the "wsh" descriptor (owns cases).
    private val wshKeyOrigins: List<Pair<ByteArray, List<Int>>> =
        (0 until 3).map {
            val (node, fp) = makeXpub(it)
            // Origin derivation = the account path the descriptor records.
            val H = Int.MIN_VALUE
            val der = listOf(H + 48, H, H + 2)
            fp to der
        }

    fun run(): List<String> {
        val results = mutableListOf<String>()
        tsv.readLines().forEachIndexed { rowIdx, line ->
            if (line.isBlank()) return@forEachIndexed
            val parts = line.split("\t")
            check(parts.size == 3) { "bad row $rowIdx: $line" }
            val (kind, arg, expected) = parts
            val actual = when (kind) {
                "parse" -> parseRow(arg)
                "policy" -> policyRow(arg)
                "addr" -> addrRow(arg)
                "owns" -> ownsRow(arg)
                "reject" -> rejectRow(arg)
                else -> throw IllegalStateException("unknown kind '$kind' row $rowIdx")
            }
            if (actual != expected) {
                throw AssertionError("row $rowIdx [$kind] ${arg.take(80)}:\n  expected: $expected\n  actual:   $actual")
            }
            results.add("row $rowIdx OK")
        }
        return results
    }

    // embit's to_string() re-emits branch sets in the multipart <0;1> form.
    private fun parseRow(descStr: String): String {
        val d = Descriptor.fromString(descStr)
        val keys = d.keys.joinToString(",") {
            ("[${HexFormat.of().formatHex(it.fingerprint!!)}" +
                "/" + it.derivation.joinToString("/") { d -> if (d < 0) (d - Int.MIN_VALUE).toString() + "h" else d.toString() } +
                "]" +
                it.hdkey.serialize(Networks.MAINNET, "xpub") +
                Key.toStringAd(it.allowedDerivation))
        }
        val multi = (if (d.isSorted) "sortedmulti" else "multi") + "(${d.threshold}," + keys + ")"
        return when {
            d.sh && d.wsh -> "sh(wsh($multi))"
            d.wsh -> "wsh($multi)"
            d.sh -> "sh($multi)"
            else -> multi
        }
    }

    private fun policyRow(name: String): String {
        val (m, n) = getMultisigPolicy(Descriptor.fromString(DESCRIPTORS.getValue(name)))
        return "$m,$n"
    }

    private fun addrRow(arg: String): String {
        val f = arg.split("|")
        val name = f[0]; val net = f[1]; val index = f[2]; val isChange = f[3]
        val d = Descriptor.fromString(DESCRIPTORS.getValue(name))
        return getMultisigAddress(
            descriptor = d,
            index = index.toInt(),
            isChange = isChange == "1",
            embitNetwork = Networks.fromName(net)
        )
    }

    private fun ownsRow(arg: String): String {
        val (name, index, isChange, mode) = arg.split("|")
        val d = Descriptor.fromString(DESCRIPTORS.getValue(name))
        val i = index.toInt()
        val branch = if (isChange == "1") 1 else 0
        val spk = d.derivedScriptPubkey(i, branch)

        // Middle key's honest full origin path (top-level origin).
        val (fp1, der1) = wshKeyOrigins[1]
        val honestDer = DerivationPath(fp1, der1 + listOf(branch, i))

        val derivs: Map<ByteArray, DerivationPath> = when (mode) {
            "honest" -> mapOf(ByteArray(33) to honestDer)
            "mismatch" -> mapOf(ByteArray(33) to honestDer) // pub bytes unused by owns() (1:1)
            "wrongscript" -> mapOf(ByteArray(33) to honestDer)
            else -> throw IllegalStateException("unknown mode $mode")
        }
                val script = if (mode == "wrongscript") {
            com.seedsigner.core.tx.Script.p2sh(
                com.seedsigner.core.tx.Script.p2wsh(spk).data
            ).data
        } else {
            spk
        }
        return d.owns(script, derivs).toString()
    }

    private fun rejectRow(s: String): String =
        try {
            Descriptor.fromString(s)
            "accepted"
        } catch (e: Exception) {
            "rejected"
        }
}

fun main(args: Array<String>) {
    val tsv = File(args.getOrNull(0) ?: "tools/out/descriptor_cases.tsv")
    check(tsv.isFile) { "TSV not found: ${tsv.absolutePath}" }
    val results = CrosscheckDescriptor(tsv).run()
    check(results.isNotEmpty()) { "TSV had no rows" }
    println("Descriptor crosscheck: ${results.size}/${results.size} rows byte-identical")
}
