package com.seedsigner.core.psbt

import com.seedsigner.core.crypto.Bip39
import com.seedsigner.core.crypto.Networks
import java.io.File
import java.util.Base64

/**
 * Differential crosscheck of the PSBT *parser* (driven by
 * tools/crosscheck_psbt_parser.py). The Python side runs the upstream
 * seedsigner PSBTParser over a battery of psbts built from the upstream test
 * fixtures and dumps each result (or the exception class raised) to a TSV.
 *
 * Input lines:  name \t psbt_b64 \t seed_mnemonic \t status \t policy_type \t m \t n \t
 *               cosigners \t spend \t change \t fee \t input \t num_inputs \t
 *               is_high_fee \t op_return \t destinations \t change_data
 * Output lines: name \t ok|FAIL <field>
 */
fun main(args: Array<String>) {
    val inPath = args[0]
    val outPath = if (args.size > 1) args[1] else System.getProperty("java.io.tmpdir") + "/psbt_parser_out.tsv"
    val lines = File(inPath).readLines().filter { it.isNotBlank() }
    var failures = 0

    val out = lines.drop(1).map { line ->
        val f = line.split("\t")
        val name = f[0]; val psbtB64 = f[1]; val seedMnemonic = f[2]; val status = f[3]
        val policyType = f[4]; val m = f[5]; val n = f[6]; val cosigners = f[7]
        val spend = f[8]; val change = f[9]; val fee = f[10]; val input = f[11]
        val numInputs = f[12]; val isHighFee = f[13]; val opReturn = f[14]
        val destinations = f[15]; val changeData = f[16]
        val constructionErrors = mutableListOf<String>()
        try {
            val psbt = Psbt.parse(Base64.getDecoder().decode(psbtB64))
            val seedBytes = Bip39.mnemonicToSeed(seedMnemonic)
            // Upstream's __init__ runs parse() itself: the exception, when expected,
            // comes out of the constructor, not of a later call.
            val parser = try {
                PsbtParser(psbt, seedBytes, Networks.REGTEST)
            } catch (e: Exception) {
                if (status == "ok") throw e
                val expectedKotlin = "Psbt" + status.removePrefix("PSBT")
                checkEq(name, "status", expectedKotlin, e.javaClass.simpleName, constructionErrors)
                null
            }

            val errors = constructionErrors
            when {
                status == "ok" -> {
                    checkEq(name, "policy_type", policyType, parser!!.policy?.get("type")?.toString() ?: "", errors)
                    checkEq(name, "m", m, (parser.policy?.get("m") as? Int)?.toString() ?: "", errors)
                    checkEq(name, "n", n, (parser.policy?.get("n") as? Int)?.toString() ?: "", errors)
                    checkEq(name, "cosigners", cosigners,
                        (parser.policy?.get("cosigners") as? List<String>)?.joinToString(";") ?: "", errors)
                    checkEq(name, "spend", spend, parser.spendAmount.toString(), errors)
                    checkEq(name, "change", change, parser.changeAmount.toString(), errors)
                    checkEq(name, "fee", fee, parser.feeAmount.toString(), errors)
                    checkEq(name, "input", input, parser.inputAmount.toString(), errors)
                    checkEq(name, "num_inputs", numInputs, parser.numInputs.toString(), errors)
                    checkEq(name, "is_high_fee", isHighFee, parser.isHighFee.toString(), errors)
                    checkEq(name, "op_return", opReturn,
                        parser.opReturnData?.joinToString("") { "%02x".format(it) } ?: "", errors)
                    checkEq(name, "destinations", destinations,
                        parser.destinationAddresses.zip(parser.destinationAmounts)
                            .joinToString(";") { "${it.first}|${it.second}" }, errors)
                    checkEq(name, "change_data", changeData,
                        parser.changeData.joinToString(";") {
                            "${it.address}|${it.amount}|${it.verifiedDerivationPath.joinToString(",") { p -> "0x${(p.toLong() and 0xFFFFFFFFL).toString(16)}" }}"
                        }, errors)
                }
                else -> {
                    if (parser != null) {
                        checkEq(name, "status", "Psbt" + status.removePrefix("PSBT"), "NoneThrown", errors)
                    }
                }
            }
            val row = if (errors.isEmpty()) "ok" else errors.first()
            if (row != "ok") failures++
            name + "\t" + row
        } catch (e: Exception) {
            failures++
            name + "\tFAIL ${e.javaClass.simpleName}: ${e.message}"
        }
    }.joinToString("\n")

    File(outPath).writeText(out + "\n")
    if (failures > 0) {
        System.err.println("$failures case(s) FAILED")
        kotlin.system.exitProcess(1)
    }
}

private fun checkEq(name: String, field: String, expected: String, actual: String, errors: MutableList<String>) {
    if (expected != actual) errors.add("FAIL $name.$field: expected=<$expected> actual=<$actual>")
}
