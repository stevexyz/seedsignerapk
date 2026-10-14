package com.seedsigner.core.tx

import com.seedsigner.core.crypto.*

/**
 * Script and Witness, ported from embit's `script.py`. `Script` is a thin
 * wrapper over raw script bytes (equality by content, matching embit).
 */
class Script(var data: ByteArray) {
    constructor() : this(ByteArray(0))

    val size: Int get() = data.size

    fun serialize(): ByteArray = Compact.toBytes(data.size.toLong()) + data

    fun push(chunk: ByteArray) {
        data += Compact.toBytes(chunk.size.toLong()) + chunk
    }

    /**
     * Returns "p2pkh" | "p2sh" | "p2wpkh" | "p2wsh" | "p2tr" | null — same
     * detection rules as embit's `Script.script_type()`.
     */
    fun scriptType(): String? {
        val d = data
        val b = { i: Int -> d[i].toInt() and 0xFF }
        if (d.size == 25 && b(0) == 0x76 && b(1) == 0xa9 && b(2) == 0x14 && b(d.size - 2) == 0x88 && b(d.size - 1) == 0xac) return "p2pkh"
        if (d.size == 23 && b(0) == 0xa9 && b(1) == 0x14 && b(d.size - 1) == 0x87) return "p2sh"
        if (d.size == 22 && b(0) == 0x00 && b(1) == 0x14) return "p2wpkh"
        if (d.size == 34 && b(0) == 0x00 && b(1) == 0x20) return "p2wsh"
        if (d.size == 34 && b(0) == 0x51 && b(1) == 0x20) return "p2tr"
        return null
    }

    /** Scriptpubkey → address string for [network]. Mirrors embit's `Script.address()`. */
    fun address(network: Networks.Network = Networks.MAINNET): String {
        val t = scriptType() ?: throw IllegalArgumentException("This type of script doesn't have address representation")
        val d = data
        return when (t) {
            "p2pkh" -> Base58.encodeCheck(byteArrayOf(network.p2pkh.toByte()) + d.copyOfRange(3, 23))
            "p2sh" -> Base58.encodeCheck(byteArrayOf(network.p2sh.toByte()) + d.copyOfRange(2, 22))
            "p2wpkh", "p2wsh", "p2tr" -> {
                var ver = d[0].toInt() and 0xFF
                if (ver > 0) ver = ver % 0x50
                Bech32.encodeAddress(network.bech32, ver, d.copyOfRange(2, d.size),
                    if (ver == 0) Bech32.Encoding.BECH32 else Bech32.Encoding.BECH32M)
            }
            else -> throw IllegalArgumentException("Unsupported script type")
        }
    }

    companion object {
        fun fromBytes(data: ByteArray): Script = Script(data)

        fun readFrom(stream: ByteArray, pos: Int): Pair<Script, Int> {
            val (l, p) = Compact.readFrom(stream, pos)
            if (stream.size - p < l) throw PSBTException("Can't read $l bytes")
            return Script(stream.copyOfRange(p, p + l)) to (p + l)
        }

        /** Decodes a bitcoin address into its scriptpubkey. */
        fun fromAddress(addr: String): Script {
            val b58 = Base58.decodeCheck(addr)
            return when (b58[0].toInt() and 0xFF) {
                0 -> Script(b58.copyOfRange(1, 21))                       // p2pkh
                5 -> Script(b58.copyOfRange(1, 21))                       // p2sh (hash already inside)
                else -> throw PSBTException("Unknown base58 version byte")
            }
            // NOTE: p2sh wraps as \xa9\x14<hash>\x87; p2pkh as \x76\xa9\x14<hash>\x88\xac
        }

        fun p2pkh(pubkey: ByteArray): Script =
            Script(byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14) + Hashes.hash160(pubkey) + byteArrayOf(0x88.toByte(), 0xac.toByte()))

        fun p2sh(script: ByteArray): Script =
            Script(byteArrayOf(0xa9.toByte(), 0x14) + Hashes.hash160(script) + byteArrayOf(0x87.toByte()))

        fun p2wpkh(pubkey: ByteArray): Script =
            Script(byteArrayOf(0x00, 0x14) + Hashes.hash160(pubkey))

        fun p2tr(pubkey: ByteArray): Script =
            Script(byteArrayOf(0x51, 0x20) + pubkey.copyOfRange(0, 32))

        fun p2wsh(script: ByteArray): Script =
            Script(byteArrayOf(0x00, 0x20) + Hashes.sha256(script))

        fun p2pkhFromP2wpkh(script: Script): Script =
            // p2wpkh data = 00 14 <20-byte key>; keep 14 <key> (mirrors embit's serialize()[2:])
            Script(byteArrayOf(0x76.toByte(), 0xa9.toByte()) + script.data.copyOfRange(1, 22) + byteArrayOf(0x88.toByte(), 0xac.toByte()))
    }

    override fun equals(other: Any?): Boolean = other is Script && other.data.contentEquals(data)
    override fun hashCode(): Int = data.contentHashCode()
}

class Witness(val items: List<ByteArray>) {
    constructor() : this(emptyList())

    fun serialize(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(Compact.toBytes(items.size.toLong()))
        for (item in items) {
            out.write(Compact.toBytes(item.size.toLong()))
            out.write(item)
        }
        return out.toByteArray()
    }

    companion object {
        fun readFrom(stream: ByteArray, pos: Int): Pair<Witness, Int> {
            val (num, p) = Compact.readFrom(stream, pos)
            var q = p
            val items = mutableListOf<ByteArray>()
            for (i in 0 until num) {
                val (l, r) = Compact.readFrom(stream, q)
                items += stream.copyOfRange(r, r + l)
                q = r + l
            }
            return Witness(items) to q
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Witness && other.items.size == items.size && other.items.zip(items).all { (a, b) -> a.contentEquals(b) }

    override fun hashCode(): Int = items.map { it.contentHashCode() }.hashCode()
}

/** Error type for PSBT/transaction parsing, mirroring embit's PSBTError. */
class PSBTException(message: String) : Exception(message)
