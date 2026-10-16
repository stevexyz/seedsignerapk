package com.seedsigner.core.psbt

import com.seedsigner.core.crypto.Bip32
import com.seedsigner.core.crypto.Networks
import com.seedsigner.core.crypto.Secp256k1
import com.seedsigner.core.tx.Script
import java.util.logging.Logger

/**
 * 1:1 port of seedsigner_orig/src/seedsigner/models/psbt_parser.py.
 *
 * Reads a psbt on behalf of one seed and works out everything the signing
 * flow shows the user before they approve: the wallet policy, the amount
 * coming in, what is being spent, what comes back as change, the fee, where
 * the spend is going, and any OP_RETURN payload.
 *
 * A psbt is written by an untrusted coordinator. The metadata it carries
 * about keys (fingerprints, derivation paths, xpubs) is a claim, not a fact.
 * The onus is on us to verify by re-deriving from the signing seed.
 *
 * Naming mirrors the upstream source:
 *   claimed_...   coordinator-supplied metadata. Safe to read; never a decision input.
 *   verified_...  a fact this device proved by re-deriving from the signing seed.
 */

// ---------------------------------------------------------------------------
// Exceptions (upstream PSBTVerificationError hierarchy)
// ---------------------------------------------------------------------------

open class PsbtVerificationError(message: String? = null) : Exception(message)

/** An output claims this seed's fingerprint on a key the seed does not derive. An attack. */
class PsbtOutputOwnershipClaimError(message: String? = null) : PsbtVerificationError(message)

/** An input claims this seed's fingerprint on a key the seed does not derive. Unsignable. */
class PsbtInputOwnershipClaimError(message: String? = null) : PsbtVerificationError(message)

/** Output claims more derivation path entries than its script can use. */
class PsbtSurplusDerivationPathsError(message: String? = null) : PsbtVerificationError(message)

/** A scope declares derivation paths in both the ecdsa and the taproot key maps. */
class PsbtMixedDerivationPathTypesError(message: String? = null) : PsbtVerificationError(message)

/** The psbt's account of who an output pays contradicts the script it commits to. An attack. */
class PsbtOutputOwnershipContradictionError(message: String? = null) : PsbtVerificationError(message)

/** The selected seed holds no key that could sign any input. A mismatch, not an attack. */
class PsbtSeedCannotSignError(message: String? = null) : PsbtVerificationError(message)

/**
 * Derivation cache: (id(parent), path so far) -> (parent, derived child).
 * Kotlin analogue of the upstream dict keyed on Python's id(). The parent is
 * stored in the value so its identity hash can't be reused while the entry is
 * alive.
 */
typealias DerivationCache = MutableMap<Pair<Int, List<Int>>, Pair<Bip32.HDKey, Bip32.HDKey>>

/** Policy map: "type" (String), plus "m"/"n" (Int) and "cosigners" (List<String>) for multisig. */
typealias Policy = MutableMap<String, Any?>

class PsbtParser(
    val psbt: Psbt,
    val seedBytes: ByteArray,
    val network: Networks.Network,
) {
    companion object {
        private val log = Logger.getLogger("PsbtParser")

        const val OP_RETURN = 106

        // Upper bound on how many levels of derivation a single parse will cache.
        const val MAX_CACHED_DERIVATIONS = 1000

        // Warn when the fee exceeds this percentage of what is being sent.
        const val HIGH_FEES_WARNING_THRESHOLD = 25

        fun trim(psbt: Psbt): Psbt {
            val trimmedPsbt = Psbt(tx = psbt.tx)
            for (i in psbt.inputs.indices) {
                val inp = psbt.inputs[i]
                if (inp.finalScriptWitness != null) {
                    // Taproot sign; trim to only final_scriptwitness (BIP-371/BIP-174)
                    trimmedPsbt.inputs[i].finalScriptWitness = inp.finalScriptWitness
                } else {
                    trimmedPsbt.inputs[i].partialSigs.putAll(inp.partialSigs)
                }
            }
            return trimmedPsbt
        }

        fun sigCount(psbt: Psbt): Int {
            var cnt = 0
            for (inp in psbt.inputs) {
                if (inp.finalScriptWitness != null) {
                    cnt += 1
                } else {
                    cnt += inp.partialSigs.size
                }
            }
            return cnt
        }

        private fun getPolicy(scope: PsbtScope, scriptpubkey: Script, xpubs: Map<ByteArray, DerivationPath>, cache: DerivationCache?, network: Networks.Network): Policy {
            var scriptType = scriptpubkey.scriptType()
            // p2sh can be either legacy multisig, nested segwit multisig, or nested segwit singlesig
            if (scriptType == "p2sh") {
                if (scope.witnessScript != null) {
                    scriptType = "p2sh-p2wsh"
                } else if (scope.redeemScript != null && scope.redeemScript!!.scriptType() == "p2wpkh") {
                    scriptType = "p2sh-p2wpkh"
                }
            }
            val policy = mutableMapOf<String, Any?>()
            policy["type"] = scriptType

            var script: Script? = null
            if (scriptType != null) {
                if (scriptType.contains("p2wsh") && scope.witnessScript != null) {
                    script = scope.witnessScript
                } else if (scriptType == "p2sh" && scope.redeemScript != null) {
                    script = scope.redeemScript
                }

                script?.let { s ->
                    val (m, n, pubkeys) = parseMultisig(s)
                    try {
                        val cosigners = getCosigners(pubkeys, scope.bip32Derivations, xpubs, cache, network)
                        policy["m"] = m
                        policy["n"] = n
                        policy["cosigners"] = cosigners
                    } catch (e: Exception) {
                        policy["m"] = m
                        policy["n"] = n
                    }
                }
            }

            return policy
        }

        private fun buildSinglesigScript(policyType: String, publicKey: ByteArray): Script = when (policyType) {
            "p2pkh" -> Script.p2pkh(publicKey)
            "p2sh-p2wpkh" -> Script.p2sh(Script.p2wpkh(publicKey).data)
            "p2wpkh" -> Script.p2wpkh(publicKey)
            "p2tr" -> Script.p2tr(publicKey)
            else -> throw RuntimeException("Not a single-sig script type: $policyType")
        }

        /** Takes a script and extracts m, n and pubkeys from it. */
        private fun parseMultisig(multisigScript: Script): Triple<Int, Int, List<ByteArray>> {
            val d = multisigScript.data
            // OP_m <len:pubkey> ... <len:pubkey> OP_n OP_CHECKMULTISIG
            if (d.size < 37 || (d.last().toInt() and 0xFF) != 0xAE) {
                throw IllegalArgumentException("Not a multisig script")
            }
            val m = (d[0].toInt() and 0xFF) - 0x50
            if (m < 1 || m > 16) throw IllegalArgumentException("Invalid multisig script")
            val n = (d[d.size - 2].toInt() and 0xFF) - 0x50
            if (n < m || n > 16) throw IllegalArgumentException("Invalid multisig script")

            var pos = 1 // drop first byte
            val pubkeys = mutableListOf<ByteArray>()
            for (i in 0 until n) {
                if ((d[pos].toInt() and 0xFF) != 0x21) throw IllegalArgumentException("Invalid pubkey")
                pos++
                pubkeys.add(d.copyOfRange(pos, pos + 33))
                pos += 33
            }
            // check that nothing left
            val tail = d.copyOfRange(pos, d.size)
            val expected = d.copyOfRange(d.size - 2, d.size)
            if (!tail.contentEquals(expected)) throw IllegalArgumentException("Invalid multisig script")
            return Triple(m, n, pubkeys)
        }

        private fun multisigScriptContainsKey(multisigScript: Script, publicKey: ByteArray): Boolean {
            val (_, _, pubkeys) = parseMultisig(multisigScript)
            return pubkeys.any { it.contentEquals(publicKey) }
        }

        private fun deriveWithCacheViaIndices(parentKey: Bip32.HDKey, derivationPath: List<Int>, cache: DerivationCache? = null): Bip32.HDKey {
            if (cache == null) {
                var key = parentKey
                for (index in derivationPath) key = Bip32.child(key, index)
                return key
            }

            var derivedKey = parentKey
            var pathSoFar: List<Int> = emptyList()

            for (index in derivationPath) {
                pathSoFar = pathSoFar + index
                val cacheKey = Pair(System.identityHashCode(parentKey), pathSoFar)
                val next = cache[cacheKey]?.second ?: run {
                    val derived = Bip32.child(derivedKey, index)
                    if (cache.size < MAX_CACHED_DERIVATIONS) {
                        // Parent must also be stored to keep its id() from being reused
                        cache[cacheKey] = Pair(parentKey, derived)
                    }
                    derived
                }
                derivedKey = next
            }
            return derivedKey
        }

        private fun deriveWithCacheViaDerivationPath(parentKey: Bip32.HDKey, derivationPath: DerivationPath, cache: DerivationCache? = null): Bip32.HDKey {
            // The DerivationPath.fingerprint is completely ignored; this allows deriving
            // a key even when it's known that the fingerprint doesn't match.
            return deriveWithCacheViaIndices(parentKey, derivationPath.derivation, cache)
        }

        private fun getCosigners(
            pubkeys: List<ByteArray>,
            derivations: Map<ByteArray, DerivationPath>,
            xpubs: Map<ByteArray, DerivationPath>,
            cache: DerivationCache?,
            network: Networks.Network,
        ): List<String> {
            if (xpubs.isEmpty()) throw IllegalArgumentException("No global xpubs supplied")
            if (derivations.isEmpty()) throw IllegalArgumentException("No derivation paths supplied")

            val cosigners = mutableListOf<String>()
            for (pubkey in pubkeys) {
                val der = derivations.entries.firstOrNull { it.key.contentEquals(pubkey) }?.value
                    ?: throw IllegalArgumentException("Missing derivation")

                // Scan the xpubs for one whose derivation path matches the claim.
                for ((xpubBytes, originDer) in xpubs) {
                    // The full derivation path goes two indices deeper than the xpub's.
                    if (originDer.derivation == der.derivation.dropLast(2)) {
                        val xpub = Bip32.HDKey.parseBytes(xpubBytes)
                        val derivedKey = deriveWithCacheViaIndices(xpub, der.derivation.takeLast(2), cache)
                        if (derivedKey.publicKey.contentEquals(pubkey)) {
                            cosigners.add(xpub.serialize(network, "xpub"))
                            break
                        }
                    }
                }
            }

            if (cosigners.size != pubkeys.size) throw RuntimeException("Can't get all cosigners")
            return cosigners.sorted()
        }

        fun getInputFingerprints(psbt: Psbt): List<String> {
            val fingerprints = mutableSetOf<String>()
            for (input in psbt.inputs) {
                for ((_, derivationPath) in input.bip32Derivations) {
                    fingerprints.add(derivationPath.fingerprint.hex())
                }
                for ((_, pair) in input.taprootBip32Derivations) {
                    val (leafHashes, derivationPath) = pair
                    if (leafHashes.size > 0) {
                        throw Exception("Signing script path spends is not yet implemented")
                    }
                    fingerprints.add(derivationPath.fingerprint.hex())
                }
            }
            return fingerprints.toList()
        }

        fun hasMatchingInputFingerprint(psbt: Psbt, seedBytes: ByteArray, network: Networks.Network): Boolean {
            val root = Bip32.HDKey.fromSeed(seedBytes, version = network.versions["xprv"]!!)
            val seedFingerprint = intToBe4(root.myFingerprint)

            fun checkFingerprintMatch(publicKey: ByteArray, derivationPathObj: DerivationPath, isTaproot: Boolean): Boolean {
                // If exact fingerprint match
                if (derivationPathObj.fingerprint.contentEquals(seedFingerprint)) return true
                // Missing fingerprint fallback
                if (derivationPathObj.fingerprint.contentEquals(ByteArray(4))) {
                    return try {
                        seedOwnsPubkey(root, derivationPathObj.derivation, publicKey, null, isTaproot)
                    } catch (e: Exception) {
                        log.fine("Fingerprint fallback derive failed: $e")
                        false
                    }
                }
                return false
            }

            for (input in psbt.inputs) {
                for ((publicKey, derivationPathObj) in input.bip32Derivations) {
                    if (checkFingerprintMatch(publicKey, derivationPathObj, false)) return true
                }
                for ((publicKey, pair) in input.taprootBip32Derivations) {
                    val (_, derivationPathObj) = pair
                    if (checkFingerprintMatch(publicKey, derivationPathObj, true)) return true
                }
            }
            return false
        }

        /**
         * Returns True if the signing seed (root) really does derive public_key at
         * claimed_derivation_path. The canonical ownership check.
         */
        fun seedOwnsPubkey(
            root: Bip32.HDKey,
            claimedDerivationPath: List<Int>,
            publicKey: ByteArray,
            cache: DerivationCache?,
            isTaproot: Boolean = false,
        ): Boolean {
            val derivedPublicKey = deriveWithCacheViaIndices(root, claimedDerivationPath, cache).publicKey

            if (isTaproot) {
                // Only the x coordinate is real information: compare x-only.
                return derivedPublicKey.copyOfRange(1, 33).contentEquals(publicKey)
            }
            // For ecdsa the parity byte IS part of the identity: compare the full key.
            return derivedPublicKey.contentEquals(publicKey)
        }

        private fun getSeedDerivationPaths(scope: PsbtScope, root: Bip32.HDKey, cache: DerivationCache): List<DerivationPath> {
            val seedFingerprint = intToBe4(root.myFingerprint)
            val verifiedDerivationPaths = mutableListOf<DerivationPath>()

            fun checkClaim(publicKey: ByteArray, derivationPathObj: DerivationPath, isTaproot: Boolean) {
                if (!derivationPathObj.fingerprint.contentEquals(seedFingerprint)) {
                    // Claims to belong to some other key. Nothing to prove or disprove.
                    return
                }
                if (!seedOwnsPubkey(root, derivationPathObj.derivation, publicKey, cache, isTaproot)) {
                    val msg = "Key at ${Bip32.pathToStr(derivationPathObj.derivation)} claims this seed's fingerprint but does not derive from it"
                    if (scope is InputScope) throw PsbtInputOwnershipClaimError(msg)
                    throw PsbtOutputOwnershipClaimError(msg)
                }
                verifiedDerivationPaths.add(derivationPathObj)
            }

            for ((publicKey, derivationPathObj) in scope.bip32Derivations) {
                checkClaim(publicKey, derivationPathObj, false)
            }
            for ((publicKey, pair) in scope.taprootBip32Derivations) {
                val (leafHashes, derivationPathObj) = pair
                checkClaim(publicKey, derivationPathObj, true)
            }

            if (scope.bip32Derivations.isNotEmpty() && scope.taprootBip32Derivations.isNotEmpty()) {
                throw PsbtMixedDerivationPathTypesError("Scope declares both ecdsa and taproot derivation paths")
            }

            return verifiedDerivationPaths
        }

        fun isChangeBranch(derivationPath: List<Int>): Boolean {
            return derivationPath.size >= 2 && derivationPath[derivationPath.size - 2] == 1
        }

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }

    var policy: Policy? = null
    var spendAmount = 0L
    var changeAmount = 0L
    val changeData = mutableListOf<ChangeData>()
    var feeAmount = 0L
    var inputAmount = 0L
    var numInputs = 0
    val destinationAddresses = mutableListOf<String>()
    val destinationAmounts = mutableListOf<Long>()
    var opReturnData: ByteArray? = null
    var isHighFee = false
    val verifiedInputDerivationPaths = mutableListOf<List<DerivationPath>>()
    val verifiedOutputDerivationPaths = mutableListOf<List<DerivationPath>>()
    var root: Bip32.HDKey? = null

    data class ChangeData(
        val outputIndex: Int,
        val address: String,
        val amount: Long,
        val verifiedDerivationPath: List<Int>,
    )

    val numChangeOutputs: Int get() = changeData.size
    val isMultisig: Boolean get() = policy?.containsKey("m") == true
    val numDestinations: Int get() = destinationAddresses.size

    fun getChangeData(changeNum: Int): ChangeData? =
        if (changeNum < changeData.size) changeData[changeNum] else null

    /**
     * 1:1 port of upstream PSBTParser.verify_multisig_output: does the
     * known-good descriptor own this change output?
     */
    fun verifyMultisigOutput(descriptor: com.seedsigner.core.descriptor.Descriptor, changeNum: Int): Boolean {
        val i = getChangeData(changeNum)!!.outputIndex
        val output = psbt!!.outputs[i]
        return descriptor.owns(output.scriptPubkey?.data, output.bip32Derivations)
    }

    init {
        parse()
    }

    private fun setRoot(seedBytes: ByteArray) {
        root = Bip32.HDKey.fromSeed(seedBytes, version = network.versions["xprv"]!!)
    }

    fun parse(): Boolean {
        setRoot(seedBytes)

        val childKeyDerivationCache = mutableMapOf<Pair<Int, List<Int>>, Pair<Bip32.HDKey, Bip32.HDKey>>()

        // Try to fix missing fingerprints before parsing
        fillMissingFingerprints(childKeyDerivationCache)

        // Work out what this seed actually owns before anything below reads the psbt's claims.
        verifyClaimedDerivationPaths(childKeyDerivationCache)
        rejectIfSeedCannotSign()

        if (!parseInputs(childKeyDerivationCache)) return false
        if (!parseOutputs(childKeyDerivationCache)) return false

        // Every total is known now, so settle this once rather than per view.
        isHighFee = hasHighFee()

        return true
    }

    private fun parseInputs(cache: DerivationCache): Boolean {
        inputAmount = 0
        numInputs = psbt.inputs.size
        for (inp in psbt.inputs) {
            if (inp.witnessUtxo != null) {
                inputAmount += inp.witnessUtxo!!.value
            } else if (inp.nonWitnessUtxo != null) {
                inputAmount += inp.utxo!!.value
            }
            val scriptPubkey = inp.scriptPubkey ?: Script()
            val inpPolicy = getPolicy(inp, scriptPubkey, psbt.xpubs, cache, network)
            if (policy == null) {
                policy = inpPolicy
            } else if (policy!! != inpPolicy) {
                throw RuntimeException("Mixed inputs in the transaction")
            }
        }
        return true
    }

    private fun parseOutputs(cache: DerivationCache): Boolean {
        spendAmount = 0
        changeAmount = 0
        changeData.clear()
        feeAmount = 0
        destinationAddresses.clear()
        destinationAmounts.clear()

        // Grab the outputs once (upstream notes psbt.tx rebuilds per request).
        val vout = psbt.tx.vout

        for (i in vout.indices) {
            val out = psbt.outputs[i]
            val outPolicy = getPolicy(out, vout[i].scriptPubkey, psbt.xpubs, cache, network)
            var isPresumedChange = false

            if (isChangeCandidate(out, outPolicy)) {
                var singlesigDerivationPath: DerivationPath? = null
                var multisigScript: Script? = null
                var rebuiltScriptPubkey = Script()

                when (policy!!["type"] as String) {
                    "p2wsh" -> {
                        multisigScript = out.witnessScript
                        rebuiltScriptPubkey = Script.p2wsh(multisigScript!!.data)
                    }
                    "p2sh-p2wsh" -> {
                        multisigScript = out.witnessScript
                        rebuiltScriptPubkey = Script.p2sh(Script.p2wsh(multisigScript!!.data).data)
                    }
                    "p2sh" -> {
                        multisigScript = out.redeemScript
                        rebuiltScriptPubkey = Script.p2sh(multisigScript!!.data)
                    }
                    "p2pkh", "p2sh-p2wpkh", "p2wpkh" -> {
                        // Sanity check; a single sig output shouldn't have multiple
                        // derivation paths.
                        if (out.bip32Derivations.size > 1) {
                            throw PsbtSurplusDerivationPathsError("Single-key output claims more than one derivation path")
                        }
                        if (out.bip32Derivations.size == 1) {
                            singlesigDerivationPath = out.bip32Derivations.values.first()
                            val seedPublicKey = deriveWithCacheViaDerivationPath(root!!, singlesigDerivationPath, cache).publicKey
                            rebuiltScriptPubkey = buildSinglesigScript(policy!!["type"] as String, seedPublicKey)
                        }
                        // else: nothing to verify against -> external spend
                    }
                    "p2tr" -> {
                        val taprootEntries = out.taprootBip32Derivations.values.toList()
                        if (taprootEntries.size > 0) {
                            // Count just the internal key claims (no leaf hashes)
                            val internalKeyClaims = taprootEntries.count { it.first.isEmpty() }
                            if (internalKeyClaims > 1) {
                                throw PsbtSurplusDerivationPathsError("Taproot output claims more than one internal key")
                            }
                            if (taprootEntries.size == 1 && internalKeyClaims == 1) {
                                val (_, dp) = taprootEntries[0]
                                singlesigDerivationPath = dp
                                val seedPublicKey = deriveWithCacheViaDerivationPath(root!!, singlesigDerivationPath, cache).publicKey
                                // embit's script.p2tr tweaks the internal key with the
                                // (empty) script tree before building the scriptPubKey.
                                rebuiltScriptPubkey = Script.p2tr(Secp256k1.taprootTweakPub(seedPublicKey.copyOfRange(1, 33)))
                            }
                            // else: script tree leaves; we cannot rebuild the correct
                            // scriptPubKey, so it fails to match below and the output is
                            // considered an external spend.
                        }
                    }
                    else -> throw RuntimeException("Unsupported policy type: ${policy!!["type"]}")
                }

                val verifiedDerivationPaths = verifiedOutputDerivationPaths[i]

                if (rebuiltScriptPubkey.data.contentEquals(vout[i].scriptPubkey.data)) {
                    if (singlesigDerivationPath != null) {
                        if (verifiedDerivationPaths.isEmpty()) {
                            // The output pays this seed but the psbt claimed a different
                            // fingerprint here. We treat this deception as an attack.
                            throw PsbtOutputOwnershipContradictionError(
                                "Output pays this seed at ${Bip32.pathToStr(singlesigDerivationPath.derivation)} but does not claim it there"
                            )
                        }
                        if (verifiedDerivationPaths != listOf(singlesigDerivationPath)) {
                            throw RuntimeException("Output $i verified at a path it does not pay")
                        }
                        isPresumedChange = true
                    } else if (multisigScript != null) {
                        if (verifiedDerivationPaths.isEmpty()) {
                            for (derivationPathObj in out.bip32Derivations.values) {
                                val seedPublicKey = deriveWithCacheViaDerivationPath(root!!, derivationPathObj, cache).publicKey
                                if (multisigScriptContainsKey(multisigScript!!, seedPublicKey)) {
                                    throw PsbtOutputOwnershipContradictionError(
                                        "Output's committed script holds this seed's key at ${Bip32.pathToStr(derivationPathObj.derivation)} but the psbt claims another fingerprint and/or public key there"
                                    )
                                }
                            }
                            // None of our keys in this output's script: external spend.
                        } else {
                            for (verifiedDerivationPath in verifiedDerivationPaths) {
                                val seedPublicKey = deriveWithCacheViaDerivationPath(root!!, verifiedDerivationPath, cache).publicKey
                                if (!multisigScriptContainsKey(multisigScript!!, seedPublicKey)) {
                                    throw PsbtOutputOwnershipContradictionError(
                                        "Output claims this seed at ${Bip32.pathToStr(verifiedDerivationPath.derivation)} but its committed script does not hold that key"
                                    )
                                }
                            }
                            if (out.bip32Derivations.size > (policy!!["n"] as Int)) {
                                throw PsbtSurplusDerivationPathsError("Multisig output claims more derivation paths than its script has keys")
                            }
                            isPresumedChange = true

                            // Compare this output's cosigners to the inputs' cosigners.
                            val inputCosigners = policy!!["cosigners"] as? List<String>
                            val outputCosigners = outPolicy["cosigners"] as? List<String>
                            if (inputCosigners != null && inputCosigners != outputCosigners) {
                                isPresumedChange = false
                            }
                        }
                    }
                } else if (verifiedDerivationPaths.isNotEmpty() && policy!!["type"] != "p2tr") {
                    // The psbt claims one of this seed's keys on this output, yet the
                    // output does NOT pay what that claim describes. An attack.
                    // (Taproot is exempt: a script tree tweaks our internal key, so an
                    // honest taproot change output fails the rebuild too.)
                    throw PsbtOutputOwnershipContradictionError(
                        "Output claims this seed at ${Bip32.pathToStr(verifiedDerivationPaths[0].derivation)} but its committed script contradicts that"
                    )
                }
            }

            if (vout[i].scriptPubkey.data[0].toInt() == OP_RETURN) {
                // The data is written as: OP_RETURN + OP_PUSHDATA1 + len(payload) + payload
                opReturnData = vout[i].scriptPubkey.data.copyOfRange(3, vout[i].scriptPubkey.data.size)
            } else if (isPresumedChange) {
                // "change" here is ANY output coming back to our seed, receive
                // addresses included. The View layer uses the derivation path to tell
                // the two apart.
                val addr = vout[i].scriptPubkey.address(network)
                changeData.add(
                    ChangeData(
                        outputIndex = i,
                        address = addr,
                        amount = vout[i].value,
                        verifiedDerivationPath = verifiedOutputDerivationPaths[i][0].derivation,
                    )
                )
                changeAmount += vout[i].value
            } else {
                val addr = vout[i].scriptPubkey.address(network)
                destinationAddresses.add(addr)
                destinationAmounts.add(vout[i].value)
                spendAmount += vout[i].value
            }
        }

        feeAmount = psbt.fee()
        return true
    }

    private fun isChangeCandidate(out: OutputScope, outPolicy: Policy): Boolean {
        // The outlier: a single sig p2sh output when the inputs are p2sh-p2wpkh.
        if (
            policy!!["type"] == "p2sh-p2wpkh" &&
            outPolicy["type"] == "p2sh" &&
            out.redeemScript == null
        ) {
            return true
        }

        // All other outputs must have the same policy shape as the inputs
        for (field in listOf("type", "m", "n")) {
            if (outPolicy[field] != policy!![field]) return false
        }
        return true
    }

    private fun verifyClaimedDerivationPaths(cache: DerivationCache) {
        verifiedOutputDerivationPaths.clear()
        for (out in psbt.outputs) {
            verifiedOutputDerivationPaths.add(getSeedDerivationPaths(out, root!!, cache))
        }
        verifiedInputDerivationPaths.clear()
        for (inp in psbt.inputs) {
            verifiedInputDerivationPaths.add(getSeedDerivationPaths(inp, root!!, cache))
        }
    }

    private fun rejectIfSeedCannotSign() {
        for (verifiedDerivationPaths in verifiedInputDerivationPaths) {
            if (verifiedDerivationPaths.isNotEmpty()) return
        }
        throw PsbtSeedCannotSignError()
    }

    /**
     * Upstream `verify_multisig_output` checks a change output against a
     * user-supplied "known good" embit Descriptor. The Descriptor port is a
     * separate chunk; until then the APK offers no multisig change verification
     * (documented in docs/feature_roadmap.md).
     */

    private fun fillMissingFingerprints(cache: DerivationCache) {
        if (root == null) return

        fun getUpdatedFingerprint(publicKey: ByteArray, derivationPathObj: DerivationPath, isTaproot: Boolean): DerivationPath? {
            if (!derivationPathObj.fingerprint.contentEquals(ByteArray(4))) return null
            if (seedOwnsPubkey(root!!, derivationPathObj.derivation, publicKey, cache, isTaproot)) {
                return DerivationPath(
                    fingerprint = intToBe4(root!!.myFingerprint),
                    derivation = derivationPathObj.derivation,
                )
            }
            return null
        }

        fun fillScope(scope: PsbtScope) {
            // Rebuild the maps (ByteArray keys have no content equality, so
            // in-place value replacement by key lookup is not possible).
            val newBip32 = LinkedHashMap<ByteArray, DerivationPath>()
            for ((k, v) in scope.bip32Derivations) {
                newBip32[k] = getUpdatedFingerprint(k, v, false) ?: v
            }
            scope.bip32Derivations.clear()
            scope.bip32Derivations.putAll(newBip32)

            val newTaproot = LinkedHashMap<ByteArray, Pair<List<ByteArray>, DerivationPath>>()
            for ((k, pair) in scope.taprootBip32Derivations) {
                val (leafHashes, derivationPathObj) = pair
                newTaproot[k] = Pair(leafHashes, getUpdatedFingerprint(k, derivationPathObj, true) ?: derivationPathObj)
            }
            scope.taprootBip32Derivations.clear()
            scope.taprootBip32Derivations.putAll(newTaproot)
        }

        for (inp in psbt.inputs) fillScope(inp)
        for (out in psbt.outputs) fillScope(out)
    }

    fun getTotalOutputValue(includeChange: Boolean = false): Long {
        val total = psbt.tx.vout.sumOf { it.value }
        if (includeChange) return total
        val trueChange = changeData
            .filter { isChangeBranch(it.verifiedDerivationPath) }
            .sumOf { it.amount }
        return total - trueChange
    }

    fun hasHighFee(): Boolean {
        val totalOutputValueExcludingChange = getTotalOutputValue()
        if (totalOutputValueExcludingChange <= 0) return false
        return feeAmount > (HIGH_FEES_WARNING_THRESHOLD / 100.0) * totalOutputValueExcludingChange
    }
}
