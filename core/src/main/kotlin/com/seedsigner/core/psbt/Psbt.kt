package com.seedsigner.core.psbt

import com.seedsigner.core.crypto.*
import com.seedsigner.core.tx.*
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * BIP-174 Partially Signed Bitcoin Transaction, ported 1:1 from embit's
 * `psbt.py` (v0 and v2). Upstream SeedSigner does all its PSBT byte-level
 * work through embit, so this is the module that must stay byte-identical.
 */

object CompressMode {
    const val KEEP_ALL = 0
    const val CLEAR_ALL = 1
    const val PARTIAL = 2
}

class DerivationPath(val fingerprint: ByteArray, val derivation: List<Int>) {
    fun serialize(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fingerprint)
        for (idx in derivation) out.write(intToLe4(idx))
        return out.toByteArray()
    }

    companion object {
        fun readFrom(stream: ByteArray, pos: Int): Pair<DerivationPath, Int> {
            val fingerprint = stream.copyOfRange(pos, pos + 4)
            var p = pos + 4
            val derivation = mutableListOf<Int>()
            while (true) {
                if (p + 4 > stream.size) break
                derivation += leLong(stream, p, 4).toInt()
                p += 4
            }
            return DerivationPath(fingerprint, derivation) to p
        }
    }

    override fun equals(other: Any?) = other is DerivationPath &&
        other.fingerprint.contentEquals(fingerprint) && other.derivation == derivation
    override fun hashCode() = fingerprint.contentHashCode() * 31 + derivation.hashCode()
}

/**
 * BIP-174 master_fingerprint encoding: the first 4 bytes of the hash160 in their
 * natural order, which is the big-endian encoding of the int value.
 */
fun intToBe4(v: Int): ByteArray = byteArrayOf(
    ((v ushr 24) and 0xFF).toByte(),
    ((v ushr 16) and 0xFF).toByte(),
    ((v ushr 8) and 0xFF).toByte(),
    (v and 0xFF).toByte()
)

fun intToLe4(v: Int): ByteArray = byteArrayOf(
    (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
    ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte()
)

/**
 * Common read surface for an input or output scope, used by the parser
 * (upstream `_get_policy` accepts either InputScope or OutputScope).
 */
interface PsbtScope {
    val witnessScript: Script?
    val redeemScript: Script?
    val bip32Derivations: MutableMap<ByteArray, DerivationPath>
    val taprootBip32Derivations: MutableMap<ByteArray, Pair<List<ByteArray>, DerivationPath>>
}

class InputScope(
    var txid: ByteArray? = null,          // internal order
    var vout: Long? = null,
    var sequence: Long? = null,
    val compress: Int = CompressMode.KEEP_ALL,
) : PsbtScope {
    val unknown = LinkedHashMap<ByteArray, ByteArray>()
    var nonWitnessUtxo: Transaction? = null
    var witnessUtxo: TransactionOutput? = null
    var _utxo: TransactionOutput? = null
    var _txhash: ByteArray? = null
    var _verified = false
    val partialSigs = LinkedHashMap<ByteArray, ByteArray>()   // 33-byte pubkey -> sig+sighash
    var sighashType: Int? = null
    override var redeemScript: Script? = null
    override var witnessScript: Script? = null
    override val bip32Derivations = LinkedHashMap<ByteArray, DerivationPath>()
    override val taprootBip32Derivations = LinkedHashMap<ByteArray, Pair<List<ByteArray>, DerivationPath>>()
    var taprootInternalKey: ByteArray? = null    // 32-byte x-only
    var taprootMerkleRoot: ByteArray? = null
    val taprootSigs = LinkedHashMap<String, ByteArray>()      // key = hex(xonly|leaf)
    val taprootScripts = LinkedHashMap<ByteArray, ByteArray>()
    var finalScriptSig: Script? = null
    var finalScriptWitness: Witness? = null

    val utxo: TransactionOutput?
        get() = _utxo ?: witnessUtxo ?: nonWitnessUtxo?.vout?.get(vout!!.toInt())

    val scriptPubkey: Script? get() = utxo?.scriptPubkey
    val isVerified: Boolean get() = _verified
    val isTaproot: Boolean get() = utxo?.scriptPubkey?.scriptType() == "p2tr"

    fun verify(ignoreMissing: Boolean = false): Boolean {
        if (nonWitnessUtxo != null || _txhash != null) {
            val txid = _txhash?.let { it.reversedArray() } ?: nonWitnessUtxo!!.txid()
            if (txid.contentEquals(this.txid)) {
                _verified = true
                return true
            }
            throw PSBTException("Previous txid doesn't match non_witness_utxo txid")
        }
        if (!ignoreMissing) throw PSBTException("Missing non_witness_utxo")
        return false
    }

    fun readValue(stream: ByteArray, pos: Int, key: ByteArray): Int {
        if (key.isEmpty()) return pos
        // non-witness utxo
        if (key[0] == 0x00.toByte()) {
            require(key.size == 1) { "Invalid non-witness utxo key" }
            if (nonWitnessUtxo != null) throw PSBTException("Duplicated utxo value")
            val (l, p1) = Compact.readFrom(stream, pos)
            if (compress != CompressMode.KEEP_ALL && txid != null && vout != null) {
                val (txout, txhash, p2) = Transaction.readVout(stream, p1, vout!!)
                _txhash = txhash
                _utxo = txout
                return p2
            } else {
                val (tx, p2) = Transaction.readFrom(stream, p1)
                nonWitnessUtxo = tx
                return p2
            }
        }

        val (v, p1) = readString(stream, pos)
        when {
            key[0] == 0x01.toByte() -> {
                require(key.size == 1) { "Invalid witness utxo key" }
                if (witnessUtxo != null) throw PSBTException("Duplicated utxo value")
                witnessUtxo = TransactionOutput.readFrom(v, 0).first
            }
            key[0] == 0x02.toByte() -> {
                if (compress != CompressMode.KEEP_ALL) return p1
                val pub = key.copyOfRange(1, key.size)
                if (partialSigs.containsKey(pub)) throw PSBTException("Duplicated partial sig")
                partialSigs[pub] = v
            }
            key[0] == 0x03.toByte() -> {
                require(key.size == 1) { "Invalid sighash type key" }
                if (sighashType == null) {
                    require(v.size == 4) { "Sighash type should be 4 bytes long" }
                    sighashType = leLong(v, 0, 4).toInt()
                } else throw PSBTException("Duplicated sighash type")
            }
            key[0] == 0x04.toByte() -> {
                require(key.size == 1) { "Invalid redeem script key" }
                if (redeemScript == null) redeemScript = Script(v)
                else throw PSBTException("Duplicated redeem script")
            }
            key[0] == 0x05.toByte() -> {
                require(key.size == 1) { "Invalid witness script key" }
                if (witnessScript == null) witnessScript = Script(v)
                else throw PSBTException("Duplicated witness script")
            }
            key[0] == 0x06.toByte() -> {
                val pub = key.copyOfRange(1, key.size)
                if (bip32Derivations.containsKey(pub)) throw PSBTException("Duplicated derivation path")
                bip32Derivations[pub] = DerivationPath.readFrom(v, 0).first
            }
            key[0] == 0x07.toByte() -> {
                if (compress != CompressMode.KEEP_ALL) return p1
                require(key.size == 1) { "Invalid final scriptsig key" }
                if (finalScriptSig == null) finalScriptSig = Script(v)
                else throw PSBTException("Duplicated final scriptsig")
            }
            key[0] == 0x08.toByte() -> {
                if (compress != CompressMode.KEEP_ALL) return p1
                require(key.size == 1) { "Invalid final scriptwitness key" }
                if (finalScriptWitness == null) finalScriptWitness = Witness.readFrom(v, 0).first
                else throw PSBTException("Duplicated final scriptwitness")
            }
            key.size == 1 && key[0] == 0x0e.toByte() -> txid = v.reversedArray()
            key.size == 1 && key[0] == 0x0f.toByte() -> vout = leLong(v, 0, 4)
            key.size == 1 && key[0] == 0x10.toByte() -> sequence = leLong(v, 0, 4)
            key[0] == 0x14.toByte() -> {
                require(key.size == 65) { "Invalid key length" }
                val pub = key.copyOfRange(1, 33)
                val leaf = key.copyOfRange(33, 65)
                val k = hex(pub) + hex(leaf)
                if (taprootSigs.containsKey(k)) throw PSBTException("Duplicated taproot sig")
                taprootSigs[k] = v
            }
            key[0] == 0x15.toByte() -> {
                val controlBlock = key.copyOfRange(1, key.size)
                if (taprootScripts.containsKey(controlBlock)) throw PSBTException("Duplicated taproot script")
                taprootScripts[controlBlock] = v
            }
            key[0] == 0x16.toByte() -> {
                val pub = key.copyOfRange(1, key.size)
                if (!taprootBip32Derivations.containsKey(pub)) {
                    var p = 0
                    val (numLeaf, p1) = Compact.readFrom(v, p); p = p1
                    val leafHashes = (0 until numLeaf).map {
                        val h = v.copyOfRange(p, p + 32); p += 32
                        require(h.size == 32) { "Invalid length of taproot leaf hashes" }
                        h
                    }
                    val (der, _) = DerivationPath.readFrom(v, p)
                    taprootBip32Derivations[pub] = leafHashes to der
                }
            }
            key[0] == 0x17.toByte() -> taprootInternalKey = v
            key[0] == 0x18.toByte() -> taprootMerkleRoot = v
            else -> {
                if (unknown.containsKey(key)) throw PSBTException("Duplicated key")
                unknown[key] = v
            }
        }
        return p1
    }

    fun writeTo(out: ByteArrayOutputStream, version: Int? = null): Int {
        var r = 0
        nonWitnessUtxo?.let {
            out.write(byteArrayOf(0x01, 0x00)); r += 2
            r += serString(out, it.serialize())
        }
        witnessUtxo?.let {
            out.write(byteArrayOf(0x01, 0x01)); r += 2
            r += serString(out, it.serialize())
        }
        for (pub in partialSigs.keys) {
            r += serString(out, byteArrayOf(0x02) + pub)
            r += serString(out, partialSigs[pub]!!)
        }
        sighashType?.let {
            out.write(byteArrayOf(0x01, 0x03)); r += 2
            r += serString(out, intToLe4(it))
        }
        redeemScript?.let {
            out.write(byteArrayOf(0x01, 0x04)); r += 2
            out.write(it.serialize()); r += it.serialize().size
        }
        witnessScript?.let {
            out.write(byteArrayOf(0x01, 0x05)); r += 2
            out.write(it.serialize()); r += it.serialize().size
        }
        for (pub in bip32Derivations.keys) {
            r += serString(out, byteArrayOf(0x06) + pub)
            r += serString(out, bip32Derivations[pub]!!.serialize())
        }
        finalScriptSig?.let {
            out.write(byteArrayOf(0x01, 0x07)); r += 2
            out.write(it.serialize()); r += it.serialize().size
        }
        finalScriptWitness?.let {
            out.write(byteArrayOf(0x01, 0x08)); r += 2
            r += serString(out, it.serialize())
        }

        if (version == 2) {
            txid?.let {
                r += serString(out, byteArrayOf(0x0e))
                r += serString(out, it.reversedArray())
            }
            vout?.let {
                r += serString(out, byteArrayOf(0x0f))
                r += serString(out, intToLe4(it.toInt()))
            }
            sequence?.let {
                r += serString(out, byteArrayOf(0x10))
                r += serString(out, intToLe4(it.toInt()))
            }
        }

        for ((k, v) in taprootSigs) {
            val (pub, leaf) = k.chunked(64).map { it.hexToBytes() }
            r += serString(out, byteArrayOf(0x14) + pub[0] + leaf[0])
            r += serString(out, v)
        }
        for ((controlBlock, sc) in taprootScripts) {
            r += serString(out, byteArrayOf(0x15) + controlBlock)
            r += serString(out, sc)
        }
        for (pub in taprootBip32Derivations.keys) {
            val (leafHashes, derivation) = taprootBip32Derivations[pub]!!
            r += serString(out, byteArrayOf(0x16) + pub)
            r += serString(out, Compact.toBytes(leafHashes.size.toLong()) +
                leafHashes.fold(ByteArray(0)) { a, b -> a + b } +
                derivation.serialize())
        }
        taprootInternalKey?.let {
            r += serString(out, byteArrayOf(0x17))
            r += serString(out, it)
        }
        taprootMerkleRoot?.let {
            r += serString(out, byteArrayOf(0x18))
            r += serString(out, it)
        }

        for ((k, v) in unknown) {
            r += serString(out, k)
            r += serString(out, v)
        }
        out.write(0); r += 1   // separator
        return r
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}

class OutputScope(
    var value: Long? = null,
    var scriptPubkey: Script? = null,
    val compress: Int = CompressMode.KEEP_ALL,
) : PsbtScope {
    val unknown = LinkedHashMap<ByteArray, ByteArray>()
    override var redeemScript: Script? = null
    override var witnessScript: Script? = null
    override val bip32Derivations = LinkedHashMap<ByteArray, DerivationPath>()
    override val taprootBip32Derivations = LinkedHashMap<ByteArray, Pair<List<ByteArray>, DerivationPath>>()
    var taprootInternalKey: ByteArray? = null

    fun readValue(stream: ByteArray, pos: Int, key: ByteArray): Int {
        if (key.isEmpty()) return pos
        val (v, p1) = readString(stream, pos)
        when {
            key[0] == 0x00.toByte() -> {
                require(key.size == 1) { "Invalid redeem script key" }
                if (redeemScript == null) redeemScript = Script(v)
                else throw PSBTException("Duplicated redeem script")
            }
            key[0] == 0x01.toByte() -> {
                require(key.size == 1) { "Invalid witness script key" }
                if (witnessScript == null) witnessScript = Script(v)
                else throw PSBTException("Duplicated witness script")
            }
            key[0] == 0x02.toByte() -> {
                val pub = key.copyOfRange(1, key.size)
                if (bip32Derivations.containsKey(pub)) throw PSBTException("Duplicated derivation path")
                bip32Derivations[pub] = DerivationPath.readFrom(v, 0).first
            }
            key.size == 1 && key[0] == 0x03.toByte() -> value = leLong(v, 0, 8)
            key.size == 1 && key[0] == 0x04.toByte() -> scriptPubkey = Script(v)
            key[0] == 0x05.toByte() -> taprootInternalKey = v
            key[0] == 0x07.toByte() -> {
                val pub = key.copyOfRange(1, key.size)
                if (!taprootBip32Derivations.containsKey(pub)) {
                    var p = 0
                    val (numLeaf, p1) = Compact.readFrom(v, p); p = p1
                    val leafHashes = (0 until numLeaf).map {
                        val h = v.copyOfRange(p, p + 32); p += 32
                        require(h.size == 32) { "Invalid length of taproot leaf hashes" }
                        h
                    }
                    val (der, _) = DerivationPath.readFrom(v, p)
                    taprootBip32Derivations[pub] = leafHashes to der
                }
            }
            else -> {
                if (unknown.containsKey(key)) throw PSBTException("Duplicated key")
                unknown[key] = v
            }
        }
        return p1
    }

    fun writeTo(out: ByteArrayOutputStream, version: Int? = null): Int {
        var r = 0
        redeemScript?.let {
            out.write(byteArrayOf(0x01, 0x00)); r += 2
            out.write(it.serialize()); r += it.serialize().size
        }
        witnessScript?.let {
            out.write(byteArrayOf(0x01, 0x01)); r += 2
            out.write(it.serialize()); r += it.serialize().size
        }
        for (pub in bip32Derivations.keys) {
            r += serString(out, byteArrayOf(0x02) + pub)
            r += serString(out, bip32Derivations[pub]!!.serialize())
        }

        if (version == 2) {
            value?.let {
                r += serString(out, byteArrayOf(0x03))
                r += serString(out, longToLeBytes(it, 8))
            }
            scriptPubkey?.let {
                r += serString(out, byteArrayOf(0x04))
                out.write(it.serialize()); r += it.serialize().size
            }
        }

        taprootInternalKey?.let {
            r += serString(out, byteArrayOf(0x05))
            r += serString(out, it)
        }
        for (pub in taprootBip32Derivations.keys) {
            val (leafHashes, derivation) = taprootBip32Derivations[pub]!!
            r += serString(out, byteArrayOf(0x07) + pub)
            r += serString(out, Compact.toBytes(leafHashes.size.toLong()) +
                leafHashes.fold(ByteArray(0)) { a, b -> a + b } +
                derivation.serialize())
        }

        for ((k, v) in unknown) {
            r += serString(out, k)
            r += serString(out, v)
        }
        out.write(0); r += 1   // separator
        return r
    }
}

class Psbt(
    tx: Transaction? = null,
    unknownMap: Map<ByteArray, ByteArray> = emptyMap(),
    var version: Int? = null,      // null for v0
) {
    val inputs = mutableListOf<InputScope>()
    val outputs = mutableListOf<OutputScope>()
    var txVersion: Long? = null
    var locktime: Long? = null
    val unknown = LinkedHashMap<ByteArray, ByteArray>()
    val xpubs = LinkedHashMap<ByteArray, DerivationPath>()

    init {
        if (tx != null) {
            txVersion = tx.version
            locktime = tx.locktime
            inputs += tx.vin.map { InputScope(txid = it.txid, vout = it.vout, sequence = it.sequence) }
            outputs += tx.vout.map { OutputScope(value = it.value, scriptPubkey = it.scriptPubkey) }
        }
        unknown.putAll(unknownMap)
        parseUnknowns()
    }

    val tx: Transaction
        get() = Transaction(
            version = txVersion ?: 2L,
            vin = inputs.map { TransactionInput(it.txid ?: ByteArray(32), it.vout ?: 0L, sequence = it.sequence ?: 0xFFFFFFFFL) }.toMutableList(),
            vout = outputs.map { TransactionOutput(it.value ?: 0L, it.scriptPubkey ?: Script()) }.toMutableList(),
            locktime = locktime ?: 0L,
        )

    val isVerified: Boolean get() = inputs.all { it.isVerified }

    fun verify(ignoreMissing: Boolean = false): Boolean {
        for (inp in inputs) inp.verify(ignoreMissing)
        return isVerified
    }

    fun utxo(i: Int): TransactionOutput {
        val inp = inputs[i]
        if (inp.isVerified) return inp.utxo!!
        if (inp.witnessUtxo == null && inp.nonWitnessUtxo == null)
            throw PSBTException("Missing previous utxo on input $i")
        return inp.witnessUtxo ?: inp.nonWitnessUtxo!!.vout[inp.vout!!.toInt()]
    }

    fun fee(): Long {
        var fee = (0 until inputs.size).sumOf { utxo(it).value }
        fee -= tx.vout.sumOf { it.value }
        return fee
    }

    fun serialize(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        if (version != 2) {
            out.write(byteArrayOf(0x01, 0x00))
            serString(out, tx.serialize())
        }
        for (xpub in xpubs.keys) {
            serString(out, byteArrayOf(0x01) + xpub)
            serString(out, xpubs[xpub]!!.serialize())
        }
        if (version == 2) {
            txVersion?.let { serString(out, byteArrayOf(0x02)); serString(out, intToLe4(it.toInt())) }
            locktime?.let { serString(out, byteArrayOf(0x03)); serString(out, intToLe4(it.toInt())) }
            serString(out, byteArrayOf(0x04)); serString(out, Compact.toBytes(inputs.size.toLong()))
            serString(out, byteArrayOf(0x05)); serString(out, Compact.toBytes(outputs.size.toLong()))
            serString(out, byteArrayOf(0xfb.toByte())); serString(out, intToLe4(version!!))
        }
        for ((k, v) in unknown) {
            serString(out, k)
            serString(out, v)
        }
        out.write(0)
        for (inp in inputs) inp.writeTo(out, version)
        for (o in outputs) o.writeTo(out, version)
        return out.toByteArray()
    }

    fun toBase64(): String = Base64.getEncoder().encodeToString(serialize())

    private fun parseUnknowns() {
        for (k in unknown.keys.toList()) {
            when {
                k[0] == 0x01.toByte() -> {
                    val xpub = k.copyOfRange(1, k.size)
                    xpubs[xpub] = DerivationPath.readFrom(unknown.remove(k)!!, 0).first
                }
                k.size == 1 && k[0] == 0x02.toByte() ->
                    txVersion = leLong(unknown.remove(k)!!, 0, 4)
                k.size == 1 && k[0] == 0x03.toByte() ->
                    locktime = leLong(unknown.remove(k)!!, 0, 4)
                k.size == 1 && k[0] == 0x04.toByte() -> {
                    if (inputs.isNotEmpty()) throw PSBTException("Inputs already initialized")
                    val n = Compact.fromBytes(unknown.remove(k)!!)
                    repeat(n) { inputs += InputScope() }
                }
                k.size == 1 && k[0] == 0x05.toByte() -> {
                    if (outputs.isNotEmpty()) throw PSBTException("Outputs already initialized")
                    val n = Compact.fromBytes(unknown.remove(k)!!)
                    repeat(n) { outputs += OutputScope() }
                }
            }
        }
    }

    /** Dispatches to legacy / segwit / taproot sighash like embit's PSBT.sighash(). */
    fun sighash(i: Int, sighash: Int = SIGHASH.ALL): ByteArray {
        val inp = inputs[i]
        if (inp.isTaproot) {
            val values = inputs.map { it.utxo!!.value }
            val scripts = inputs.map { it.utxo!!.scriptPubkey }
            return tx.sighashTaproot(i, scripts, values, sighash = sighash)
        }
        val value = inp.utxo!!.value
        val sc = inp.witnessScript ?: inp.redeemScript ?: inp.utxo!!.scriptPubkey
        val isSegwit = inp.witnessScript != null ||
            inp.witnessUtxo != null ||
            inp.utxo!!.scriptPubkey.scriptType() in setOf("p2wpkh", "p2wsh") ||
            (inp.redeemScript != null && inp.redeemScript!!.scriptType() in setOf("p2wpkh", "p2wsh"))
        // BIP-143: for P2WPKH inputs the scriptCode is P2PKH of the key (same as embit)
        val scFinal = if (sc.scriptType() == "p2wpkh") Script.p2pkhFromP2wpkh(sc) else sc
        return if (isSegwit) tx.sighashSegwit(i, scFinal, value, sighash = sighash)
        else tx.sighashLegacy(i, scFinal, sighash = sighash)
    }

    /**
     * Signs this PSBT with [root] (an extended key). Mirrors embit's
     * `PSBT.sign_with`. Returns the number of signatures added.
     */
    fun signWith(root: Bip32.HDKey, sighash: Int = SIGHASH.DEFAULT): Int {
        if (!root.isPrivate) return 0
        var counter = 0
        // BIP-174 fingerprints are big-endian (NOT the little-endian child-number encoding)
        val fpInt = root.fingerprintOfChild()
        val fingerprint = byteArrayOf((fpInt ushr 24).toByte(), (fpInt ushr 16).toByte(), (fpInt ushr 8).toByte(), fpInt.toByte())

        for (i in inputs.indices) {
            val inp = inputs[i]
            var requiredSighash = sighash
            if (!inp.isTaproot && requiredSighash == SIGHASH.DEFAULT) requiredSighash = SIGHASH.ALL

            var inpSighash = inp.sighashType
            if (inpSighash == null) inpSighash = requiredSighash ?: SIGHASH.DEFAULT
            if (!inp.isTaproot && inpSighash == SIGHASH.DEFAULT) inpSighash = SIGHASH.ALL

            if (requiredSighash != null && inpSighash != requiredSighash) {
                val bothAllish = inpSighash in intArrayOf(SIGHASH.DEFAULT, SIGHASH.ALL) &&
                    requiredSighash in intArrayOf(SIGHASH.DEFAULT, SIGHASH.ALL)
                if (!bothAllish) continue
            }

            // collect derivations whose fingerprint matches
            val bip32Derivations = LinkedHashMap<ByteArray, DerivationPath>()
            if (fingerprint.isNotEmpty()) {
                for (pub in inp.taprootBip32Derivations.keys) {
                    val (_, derivation) = inp.taprootBip32Derivations[pub]!!
                    if (derivation.fingerprint.contentEquals(fingerprint))
                        bip32Derivations[pub] = derivation   // 32-byte x-only key
                }
                for (pub in inp.bip32Derivations.keys) {
                    val derivation = inp.bip32Derivations[pub]!!
                    if (derivation.fingerprint.contentEquals(fingerprint))
                        bip32Derivations[pub] = derivation
                }
            }

            // derive the keypairs
            data class KeyPair(val secret: ByteArray, val pub: ByteArray)
            val derivedKeypairs = linkedMapOf<String, KeyPair>()
            for ((pub, derivation) in bip32Derivations) {
                val hdkey = deriveList(root, derivation.derivation)
                // compare xonly-to-xonly: segwit map keys are 33-byte, taproot 32-byte
                val mapXonly = if (pub.size == 33) pub.copyOfRange(1, 33) else pub
                if (!hdkey.publicKey.copyOfRange(1, 33).contentEquals(mapXonly))
                    throw PSBTException("Derivation path doesn't look right")
                derivedKeypairs[hex(hdkey.publicKey)] = KeyPair(hdkey.privateKey!!, hdkey.publicKey)
            }

            if (inp.isTaproot) {
                counter += signInputWithTapkey(root.privateKey!!, i, inp, inpSighash)
                for (kp in derivedKeypairs.values) counter += signInputWithTapkey(kp.secret, i, inp, inpSighash)
                continue
            }

            val h = sighash(i, inpSighash)
            val sc = inp.witnessScript ?: inp.redeemScript ?: inp.utxo!!.scriptPubkey
            val rootPub = root.publicKey
            val pkh = Hashes.hash160(rootPub)
            if (sc.data.containsSubsequence(rootPub) || sc.data.containsSubsequence(pkh)) {
                val sig = Secp256k1.signWithGrind(h, root.privateKey!!)
                inp.partialSigs[rootPub] = Secp256k1.signatureToDer(sig) + byteArrayOf(inpSighash.toByte())
                counter++
            }
            for (kp in derivedKeypairs.values) {
                val sig = Secp256k1.signWithGrind(h, kp.secret)
                inp.partialSigs[kp.pub] = Secp256k1.signatureToDer(sig) + byteArrayOf(inpSighash.toByte())
                counter++
            }
        }
        return counter
    }

    /** Signs a taproot input with [secret] (internal or leaf key). Port of embit's `sign_input_with_tapkey`. */
    private fun signInputWithTapkey(secret: ByteArray, inputIndex: Int, inp: InputScope, sighash: Int): Int {
        if (!inp.isTaproot) return 0
        // check if key is internal key
        val tweaked = Secp256k1.taprootTweakSec(secret, inp.taprootMerkleRoot ?: ByteArray(0))
        val x = Secp256k1.publicFromPrivate(tweaked).toXOnlyBytes()
        if (inp.utxo!!.scriptPubkey.data.containsSubsequence(x)) {
            val h = sighash(inputIndex, sighash)
            val sig = Secp256k1.schnorrSign(h, tweaked)
            var wit = sig
            if (sighash != SIGHASH.DEFAULT) wit = sig + byteArrayOf(sighash.toByte())
            inp.finalScriptWitness = Witness(listOf(wit))
            return 1
        }
        var counter = 0
        val pub = Secp256k1.publicFromPrivate(secret).toXOnlyBytes()
        for ((ctrl, sc) in inp.taprootScripts) {
            if (!sc.containsSubsequence(pub)) continue
            val leafVersion = sc[sc.size - 1].toInt() and 0xFF
            val script = Script(sc.copyOfRange(0, sc.size - 1))
            val h = tx.sighashTaproot(
                inputIndex,
                scriptPubkeys = inputs.map { it.utxo!!.scriptPubkey },
                values = inputs.map { it.utxo!!.value },
                sighash = sighash,
                extFlag = 1,
                script = script,
                leafVersion = leafVersion,
            )
            val sig = Secp256k1.schnorrSign(h, secret)
            val leaf = Hashes.taggedHash("TapLeaf", byteArrayOf(leafVersion.toByte()) + script.serialize())
            var sigData = sig
            if (sighash != SIGHASH.DEFAULT) sigData = sig + byteArrayOf(sighash.toByte())
            val k = hex(pub) + hex(leaf)
            inp.taprootSigs[k] = sigData
            counter++
        }
        return counter
    }

    companion object {
        val MAGIC: ByteArray = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte())

        fun fromBase64(b64: String, compress: Int = CompressMode.KEEP_ALL): Psbt =
            parse(Base64.getDecoder().decode(b64), compress)

        fun parse(data: ByteArray, compress: Int = CompressMode.KEEP_ALL): Psbt {
            var pos = 0
            if (!data.copyOfRange(0, 5).contentEquals(MAGIC)) throw PSBTException("Invalid PSBT magic")
            pos = 5
            var tx: Transaction? = null
            val unknown = LinkedHashMap<ByteArray, ByteArray>()
            var version: Int? = null
            while (true) {
                val (key, p1) = readString(data, pos); pos = p1
                if (key.isEmpty()) break
                val (value, p2) = readString(data, pos); pos = p2
                when {
                    key.size == 1 && key[0] == 0x00.toByte() -> {
                        if (tx == null) tx = Transaction.readFrom(value, 0).first
                        else throw PSBTException("Failed to parse PSBT - duplicated transaction field")
                    }
                    key.size == 1 && key[0] == 0xfb.toByte() -> version = leLong(value, 0, 4).toInt()
                    else -> {
                        if (unknown.containsKey(key)) throw PSBTException("Duplicated key")
                        unknown[key] = value
                    }
                }
            }
            if (tx != null && version == 2) throw PSBTException("Global TX field is not allowed in PSBTv2")
            val psbt = Psbt(tx, unknown, version)
            for (i in psbt.tx.vin.indices) {
                val (scope, p2) = readScope(data, pos, psbt.tx.vin[i], compress)
                pos = p2
                psbt.inputs[i] = scope
            }
            for (i in psbt.tx.vout.indices) {
                val (scope, p2) = readOutScope(data, pos, psbt.tx.vout[i], compress)
                pos = p2
                psbt.outputs[i] = scope
            }
            return psbt
        }

        private fun readScope(data: ByteArray, pos: Int, vin: TransactionInput, compress: Int): Pair<InputScope, Int> {
            val scope = InputScope(txid = vin.txid, vout = vin.vout, sequence = vin.sequence, compress = compress)
            var p = pos
            while (true) {
                val (key, p1) = readString(data, p); p = p1
                if (key.isEmpty()) break
                p = scope.readValue(data, p, key)
            }
            return scope to p
        }

        private fun readOutScope(data: ByteArray, pos: Int, vout: TransactionOutput, compress: Int): Pair<OutputScope, Int> {
            val scope = OutputScope(value = vout.value, scriptPubkey = vout.scriptPubkey, compress = compress)
            var p = pos
            while (true) {
                val (key, p1) = readString(data, p); p = p1
                if (key.isEmpty()) break
                p = scope.readValue(data, p, key)
            }
            return scope to p
        }
    }
}

// ----------------------------------------------------------------------
// small helpers
// ----------------------------------------------------------------------

fun serString(out: ByteArrayOutputStream, s: ByteArray): Int {
    out.write(Compact.toBytes(s.size.toLong()))
    out.write(s)
    return s.size + Compact.toBytes(s.size.toLong()).size
}

fun readString(stream: ByteArray, pos: Int): Pair<ByteArray, Int> {
    val (l, p) = Compact.readFrom(stream, pos)
    if (stream.size - p < l) throw PSBTException("Failed to read $l bytes")
    return stream.copyOfRange(p, p + l) to (p + l)
}

/** BIP-32 derive over a list of (possibly hardened-as-positive) child numbers. */
fun deriveList(key: Bip32.HDKey, indices: List<Int>): Bip32.HDKey {
    var current = key
    for (i in indices) {
        current = if (current.isPrivate) Bip32.ckdPriv(current, i) else Bip32.ckdPub(current, i)
    }
    return current
}

private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

private fun String.hexToBytes(): ByteArray =
    chunked(2).map { ((it[0].toHexInt() shl 4) or it[1].toHexInt()).toByte() }.toByteArray()

private fun Char.toHexInt(): Int =
    when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> throw IllegalArgumentException("not hex: $this")
    }

private fun ByteArray.containsSubsequence(sub: ByteArray): Boolean {
    if (sub.isEmpty()) return true
    if (sub.size > size) return false
    outer@ for (i in 0..size - sub.size) {
        for (j in sub.indices) if (this[i + j] != sub[j]) continue@outer
        return true
    }
    return false
}

private fun Int.toByteVector4BE(): ByteArray = byteArrayOf(
    (this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte()
)
