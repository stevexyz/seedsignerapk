package com.seedsigner.core.crypto

/**
 * Address derivation for the standard script types
 * (P2PKH, P2SH, native segwit P2WPKH/P2WSH, nested P2SH-segwit).
 */
object Address {

    /** P2WPKH: bech32 v0 of hash160(pubkey). */
    fun p2wpkh(network: Networks.Network, compressedPubkey: ByteArray): String =
        Bech32.encodeAddress(network.bech32, 0, Hashes.hash160(compressedPubkey),
            Bech32.Encoding.BECH32)

    /** P2WSH: bech32 v0 of sha256(witnessScript). */
    fun p2wsh(network: Networks.Network, witnessScript: ByteArray): String =
        Bech32.encodeAddress(network.bech32, 0, Hashes.sha256(witnessScript),
            Bech32.Encoding.BECH32)

    /** P2SH-P2WPKH: p2sh of the P2WPKH script. */
    fun p2shP2wpkh(network: Networks.Network, compressedPubkey: ByteArray): String = {
        val script = p2wpkhScript(compressedPubkey)
        Base58.encodeCheck(byteArrayOf(network.p2sh.toByte()) + Hashes.hash160(script))
    }()

    /** P2SH-P2WSH: p2sh of the P2WSH script. */
    fun p2shP2wsh(network: Networks.Network, witnessScript: ByteArray): String = {
        val script = p2wshScript(witnessScript)
        Base58.encodeCheck(byteArrayOf(network.p2sh.toByte()) + Hashes.hash160(script))
    }()

    /** P2PKH: base58check p2pkh of hash160(pubkey). */
    fun p2pkh(network: Networks.Network, compressedPubkey: ByteArray): String =
        Base58.encodeCheck(byteArrayOf(network.p2pkh.toByte()) + Hashes.hash160(compressedPubkey))

    // script builders (BIP-141)

    fun p2wpkhScript(compressedPubkey: ByteArray): ByteArray =
        byteArrayOf(0x00.toByte(), 0x14.toByte()) + Hashes.hash160(compressedPubkey)

    fun p2wshScript(witnessScript: ByteArray): ByteArray =
        byteArrayOf(0x00.toByte(), 0x20.toByte()) + Hashes.sha256(witnessScript)
}
