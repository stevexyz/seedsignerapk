package com.seedsigner.core.tx

import com.seedsigner.core.crypto.Hashes
import java.io.ByteArrayOutputStream

/**
 * Transaction / TransactionInput / TransactionOutput + sighash computation,
 * ported 1:1 from embit's `transaction.py` (BIP-143, BIP-341).
 *
 * NOTE: `txid` is stored in *internal* (reversed) order, exactly like embit —
 * it is written to the wire reversed, and `txid()` returns the internal
 * bytes. Keep this convention when comparing with display strings.
 */

object SIGHASH {
    const val DEFAULT: Int = 0
    const val ALL: Int = 1
    const val NONE: Int = 2
    const val SINGLE: Int = 3
    const val ANYONECANPAY: Int = 0x80

    /** Returns (baseSighash, anyoneCanPay) or throws on invalid types. */
    fun check(sighash: Int): Pair<Int, Boolean> {
        var acp = false
        var sh = sighash
        if (sh and ANYONECANPAY != 0) {
            sh = sh xor ANYONECANPAY
            acp = true
        }
        if (sh !in intArrayOf(DEFAULT, ALL, NONE, SINGLE))
            throw PSBTException("Invalid SIGHASH type")
        return sh to acp
    }
}

class TransactionInput(
    val txid: ByteArray,           // internal order
    val vout: Long,
    var scriptSig: Script = Script(),
    var sequence: Long = 0xFFFFFFFFL,
    var witness: Witness = Witness(),
) {
    val isSegwit: Boolean
        get() = witness.items.isNotEmpty()   // embit: witness.serialize() != b"\x00"

    fun serialize(scriptSig: Script? = null, sighash: Int = SIGHASH.ALL): ByteArray {
        val out = ByteArrayOutputStream()
        writeTo(out, scriptSig, sighash)
        return out.toByteArray()
    }

    fun writeTo(out: ByteArrayOutputStream, scriptSig: Script? = null, sighash: Int = SIGHASH.ALL) {
        val (sh, acp) = SIGHASH.check(sighash)
        val seq = if (acp || sh in intArrayOf(SIGHASH.SINGLE, SIGHASH.NONE)) 0L else sequence
        out.write(txid.reversedArray())
        out.write(longToLeBytes(vout, 4))
        out.write((scriptSig ?: this.scriptSig).serialize())
        out.write(longToLeBytes(seq, 4))
    }

    companion object {
        fun readFrom(stream: ByteArray, pos: Int): Pair<TransactionInput, Int> {
            require(stream.size - pos >= 32)
            val txid = stream.copyOfRange(pos, pos + 32).reversedArray()
            val vout = leLong(stream, pos + 32, 4)
            val (scriptSig, p1) = Script.readFrom(stream, pos + 36)
            val sequence = leLong(stream, p1, 4)
            return TransactionInput(txid, vout, scriptSig, sequence) to (p1 + 4)
        }
    }

    override fun equals(other: Any?): Boolean = other is TransactionInput &&
        other.txid.contentEquals(txid) && other.vout == vout &&
        other.scriptSig == scriptSig && other.sequence == sequence && other.witness == witness

    override fun hashCode(): Int = txid.contentHashCode()
}

class TransactionOutput(
    val value: Long,
    val scriptPubkey: Script,
) {
    fun serialize(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(longToLeBytes(value, 8))
        out.write(scriptPubkey.serialize())
        return out.toByteArray()
    }

    companion object {
        fun readFrom(stream: ByteArray, pos: Int): Pair<TransactionOutput, Int> {
            val value = leLong(stream, pos, 8)
            val (scriptPubkey, p1) = Script.readFrom(stream, pos + 8)
            return TransactionOutput(value, scriptPubkey) to p1
        }
    }

    override fun equals(other: Any?): Boolean =
        other is TransactionOutput && other.value == value && other.scriptPubkey == scriptPubkey

    override fun hashCode(): Int = value.hashCode()
}

class Transaction(
    var version: Long = 2,
    var vin: MutableList<TransactionInput> = mutableListOf(),
    var vout: MutableList<TransactionOutput> = mutableListOf(),
    var locktime: Long = 0,
) {
    val isSegwit: Boolean
        get() = vin.any { it.isSegwit }

    fun serialize(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(longToLeBytes(version, 4))
        if (isSegwit) out.write(byteArrayOf(0x00, 0x01)) // segwit marker + flag
        out.write(Compact.toBytes(vin.size.toLong()))
        for (inp in vin) inp.writeTo(out)
        out.write(Compact.toBytes(vout.size.toLong()))
        for (o in vout) out.write(o.serialize())
        if (isSegwit) for (inp in vin) out.write(inp.witness.serialize())
        out.write(longToLeBytes(locktime, 4))
        return out.toByteArray()
    }

    /** Double-SHA256 in internal (reversed) order. */
    fun hash(): ByteArray = Hashes.doubleSha256(serialize())

    fun txid(): ByteArray = hash().reversedArray()

    companion object {
        /**
         * Reads vout[idx] plus the prev-tx hash without retaining the whole
         * transaction — port of embit's `Transaction.read_vout`.
         * Returns (output, txHash in internal order).
         */
        fun readVout(stream: ByteArray, pos: Int, idx: Long): Triple<TransactionOutput, ByteArray, Int> {
            var p = pos
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(stream.copyOfRange(p, p + 4)); p += 4
            var numVin = Compact.readFrom(stream, p).first; p = Compact.readFrom(stream, p).second
            val isSegwit = numVin == 0
            if (isSegwit) {
                require((stream[p].toInt() and 0xFF) == 0x01) { "Invalid segwit marker" }
                p++
                numVin = Compact.readFrom(stream, p).first; p = Compact.readFrom(stream, p).second
            }
            digest.update(Compact.toBytes(numVin.toLong()))
            repeat(numVin) {
                val (txin, p1) = TransactionInput.readFrom(stream, p)
                p = p1
                digest.update(txin.serialize())
            }
            val numVout = Compact.readFrom(stream, p).first; p = Compact.readFrom(stream, p).second
            digest.update(Compact.toBytes(numVout.toLong()))
            if (idx >= numVout.toLong() || idx < 0)
                throw PSBTException("Invalid vout index $idx, max is ${numVout - 1}")
            var res: TransactionOutput? = null
            repeat(numVout.toInt()) { i ->
                val (vout, p1) = TransactionOutput.readFrom(stream, p)
                p = p1
                if (i == idx.toInt()) res = vout
                digest.update(vout.serialize())
            }
            if (isSegwit) {
                repeat(numVin.toInt()) {
                    val (_, p1) = Witness.readFrom(stream, p)
                    p = p1
                }
            }
            digest.update(stream.copyOfRange(p, p + 4)); p += 4
            return Triple(res ?: throw PSBTException("no vout"),
                java.security.MessageDigest.getInstance("SHA-256").digest(digest.digest()), p)
        }

        fun readFrom(stream: ByteArray, pos: Int): Pair<Transaction, Int> {
            var p = pos
            val version = leLong(stream, p, 4); p += 4
            var numVin = Compact.readFrom(stream, p).first; p = Compact.readFrom(stream, p).second
            val isSegwit = numVin == 0
            if (isSegwit) {
                require((stream[p].toInt() and 0xFF) == 0x01) { "Invalid segwit marker" }
                p++
                numVin = Compact.readFrom(stream, p).first; p = Compact.readFrom(stream, p).second
            }
            val vin = mutableListOf<TransactionInput>()
            repeat(numVin) {
                val (txin, p1) = TransactionInput.readFrom(stream, p)
                p = p1
                vin += txin
            }
            val numVout = Compact.readFrom(stream, p).first; p = Compact.readFrom(stream, p).second
            val vout = mutableListOf<TransactionOutput>()
            repeat(numVout.toInt()) {
                val (voutE, p1) = TransactionOutput.readFrom(stream, p)
                p = p1
                vout += voutE
            }
            if (isSegwit) {
                for (inp in vin) {
                    val (w, p1) = Witness.readFrom(stream, p)
                    p = p1
                    inp.witness = w
                }
            }
            val locktime = leLong(stream, p, 4); p += 4
            return Transaction(version, vin, vout, locktime) to p
        }
    }
}

// ----------------------------------------------------------------------
// Sighash functions — ported from embit's Transaction methods.
// Kept as extensions so PSBT can call them on the (possibly virtual) tx.
// ----------------------------------------------------------------------

private fun txHashPrevouts(tx: Transaction): ByteArray {
    val out = ByteArrayOutputStream()
    for (inp in tx.vin) {
        out.write(inp.txid.reversedArray())
        out.write(longToLeBytes(inp.vout, 4))
    }
    return Hashes.sha256(out.toByteArray())
}

private fun txHashSequence(tx: Transaction): ByteArray {
    val out = ByteArrayOutputStream()
    for (inp in tx.vin) out.write(longToLeBytes(inp.sequence, 4))
    return Hashes.sha256(out.toByteArray())
}

private fun txHashOutputs(tx: Transaction): ByteArray {
    val out = ByteArrayOutputStream()
    for (o in tx.vout) out.write(o.serialize())
    return Hashes.sha256(out.toByteArray())
}

fun hashAmounts(amounts: List<Long>): ByteArray {
    val out = ByteArrayOutputStream()
    for (a in amounts) out.write(longToLeBytes(a, 8))
    return Hashes.sha256(out.toByteArray())
}

fun hashScriptPubkeys(scriptPubkeys: List<Script>): ByteArray {
    val out = ByteArrayOutputStream()
    for (s in scriptPubkeys) out.write(s.serialize())
    return Hashes.sha256(out.toByteArray())
}

/** BIP-143 segwit sighash. */
fun Transaction.sighashSegwit(inputIndex: Int, scriptPubkey: Script, value: Long, sighash: Int = SIGHASH.ALL): ByteArray {
    require(inputIndex in 0 until vin.size) { "Invalid input index" }
    var sh = sighash
    val acp = SIGHASH.check(sighash).second
    sh = SIGHASH.check(sh).first
    if (sh == SIGHASH.DEFAULT) sh = SIGHASH.ALL
    val inp = vin[inputIndex]
    val zero = ByteArray(32)
    val out = ByteArrayOutputStream()
    out.write(longToLeBytes(version, 4))
    if (acp) out.write(zero) else out.write(Hashes.sha256(txHashPrevouts(this)))
    if (acp || sh in intArrayOf(SIGHASH.NONE, SIGHASH.SINGLE)) out.write(zero)
    else out.write(Hashes.sha256(txHashSequence(this)))
    out.write(inp.txid.reversedArray())
    out.write(longToLeBytes(inp.vout, 4))
    out.write(scriptPubkey.serialize())
    out.write(longToLeBytes(value, 8))
    out.write(longToLeBytes(inp.sequence, 4))
    when {
        sh !in intArrayOf(SIGHASH.NONE, SIGHASH.SINGLE) -> out.write(Hashes.sha256(txHashOutputs(this)))
        sh == SIGHASH.SINGLE && inputIndex < vout.size -> out.write(Hashes.doubleSha256(vout[inputIndex].serialize()))
        else -> out.write(zero)
    }
    out.write(longToLeBytes(locktime, 4))
    out.write(longToLeBytes(sighash.toLong(), 4))
    // BIP-143: sighash = SHA256(SHA256(preimage))
    return Hashes.doubleSha256(out.toByteArray())
}

/** Legacy sighash. */
fun Transaction.sighashLegacy(inputIndex: Int, scriptPubkey: Script, sighash: Int = SIGHASH.ALL): ByteArray {
    require(inputIndex in 0 until vin.size) { "Invalid input index" }
    var sh = sighash
    val acp = SIGHASH.check(sighash).second
    sh = SIGHASH.check(sh).first
    if (sh == SIGHASH.DEFAULT) sh = SIGHASH.ALL
    if (sh == SIGHASH.SINGLE && inputIndex >= vout.size) return ByteArray(31) + byteArrayOf(0x01)

    val out = ByteArrayOutputStream()
    out.write(longToLeBytes(version, 4))
    if (acp) {
        out.write(Compact.toBytes(1))
        out.write(vin[inputIndex].serialize(scriptPubkey))
    } else {
        out.write(Compact.toBytes(vin.size.toLong()))
        for (i in vin.indices) {
            out.write(if (i == inputIndex) vin[i].serialize(scriptPubkey) else vin[i].serialize(Script(), sighash))
        }
    }
    when (sh) {
        SIGHASH.NONE -> out.write(Compact.toBytes(0))
        SIGHASH.SINGLE -> {
            out.write(Compact.toBytes(inputIndex + 1L))
            val empty = TransactionOutput(0xFFFFFFFFL, Script()).serialize()
            for (i in 0 until inputIndex) out.write(empty)
            out.write(vout[inputIndex].serialize())
        }
        SIGHASH.ALL -> {
            out.write(Compact.toBytes(vout.size.toLong()))
            for (o in vout) out.write(o.serialize())
        }
        else -> throw PSBTException("Invalid sighash")
    }
    out.write(longToLeBytes(locktime, 4))
    out.write(longToLeBytes(sighash.toLong(), 4))
    // legacy sighash is DOUBLE-SHA256 of the serialization
    return Hashes.doubleSha256(out.toByteArray())
}

/** BIP-341 taproot sighash. */
fun Transaction.sighashTaproot(
    inputIndex: Int,
    scriptPubkeys: List<Script>,
    values: List<Long>,
    sighash: Int = SIGHASH.DEFAULT,
    extFlag: Int = 0,
    annex: ByteArray? = null,
    script: Script? = null,
    leafVersion: Int = 0xC0,
    codeseparatorPos: Long? = null,
): ByteArray {
    require(inputIndex in 0 until vin.size) { "Invalid input index" }
    require(values.size == vin.size) { "All spent amounts are required" }
    val acp = SIGHASH.check(sighash).second
    val sh = SIGHASH.check(sighash).first
    val md = Hashes.taggedHashInit("TapSighash", byteArrayOf(0x00))
    md.update(byteArrayOf(sighash.toByte()))
    md.update(longToLeBytes(version, 4))
    md.update(longToLeBytes(locktime, 4))
    if (!acp) {
        md.update(txHashPrevouts(this))
        md.update(hashAmounts(values))
        md.update(hashScriptPubkeys(scriptPubkeys))
        md.update(txHashSequence(this))
    }
    if (sh !in intArrayOf(SIGHASH.SINGLE, SIGHASH.NONE)) md.update(txHashOutputs(this))
    md.update(byteArrayOf((2 * extFlag + (if (annex != null) 1 else 0)).toByte()))
    if (acp) {
        val inp = vin[inputIndex]
        val ins = ByteArrayOutputStream(); inp.writeTo(ins)
        md.update(ins.toByteArray())
        md.update(longToLeBytes(values[inputIndex], 8))
        md.update(scriptPubkeys[inputIndex].serialize())
        md.update(longToLeBytes(inp.sequence, 4))
    } else {
        md.update(longToLeBytes(inputIndex.toLong(), 4))
    }
    if (annex != null) {
        md.update(Hashes.sha256(Compact.toBytes(annex.size.toLong()) + annex))
    }
    if (sh == SIGHASH.SINGLE) md.update(vout[inputIndex].serialize())
    if (script != null) {
        md.update(Hashes.taggedHash("TapLeaf", byteArrayOf(leafVersion.toByte()) + script.serialize()))
        md.update(byteArrayOf(0x00))
        md.update(if (codeseparatorPos == null) longToLeBytes(0xFFFFFFFFL, 4) else longToLeBytes(codeseparatorPos, 4))
    }
    return md.digest()
}

// ----------------------------------------------------------------------
// Little-endian byte helpers
// ----------------------------------------------------------------------

fun longToLeBytes(v: Long, len: Int): ByteArray {
    val out = ByteArray(len)
    for (i in 0 until len) out[i] = ((v ushr (8 * i)) and 0xFF).toByte()
    return out
}

fun leLong(b: ByteArray, pos: Int, len: Int): Long {
    var v = 0L
    for (i in 0 until len) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
    return v
}

fun ByteArray.leInt(): Int = leLong(this, 0, size).toInt()
