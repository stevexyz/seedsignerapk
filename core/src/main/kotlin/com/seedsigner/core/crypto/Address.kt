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

    /** P2TR: bech32m v1 of the (already tweaked) 32-byte output key. */
    fun p2trAddress(network: Networks.Network, outputKeyXOnly: ByteArray): String =
        Bech32.encodeAddress(network.bech32, 1, outputKeyXOnly, Bech32.Encoding.BECH32M)

    /**
     * 1:1 port of upstream embit_utils.get_single_sig_address: derive the
     * receive (index) or change (1/index) key under the wallet xpub and
     * format it for the given single-sig script type.
     */
    fun singleSigAddress(
        walletXpub: Bip32.HDKey,
        scriptType: String,
        index: Int,
        isChange: Boolean,
        network: Networks.Network
    ): String {
        val pubkey = Bip32.child(Bip32.child(walletXpub, if (isChange) 1 else 0), index).publicKey
        return when (scriptType) {
            "p2pkh" -> p2pkh(network, pubkey)
            "p2sh-p2wpkh" -> p2shP2wpkh(network, pubkey)
            "p2wpkh" -> p2wpkh(network, pubkey)
            "p2tr" -> p2trAddress(network, Secp256k1.taprootTweakPub(pubkey.copyOfRange(1, 33)))
            else -> throw IllegalArgumentException("Not a single-sig script type: $scriptType")
        }
    }

    // script builders (BIP-141)

    fun p2wpkhScript(compressedPubkey: ByteArray): ByteArray =
        byteArrayOf(0x00.toByte(), 0x14.toByte()) + Hashes.hash160(compressedPubkey)

    fun p2wshScript(witnessScript: ByteArray): ByteArray =
        byteArrayOf(0x00.toByte(), 0x20.toByte()) + Hashes.sha256(witnessScript)
}

/**
 * 1:1 port of the upstream DecodeQR Bitcoin address recognition
 * (models/decode_qr.py BitcoinAddressQrDecoder.add): detects a Bitcoin
 * address in QR text and classifies it as (script type, network).
 *
 * Script type names: p2pkh / p2sh-p2wpkh / p2wpkh / p2tr.
 * Network names: main / test / regtest (embit-style keys).
 * Returns null when no address is recognized.
 */
object AddressType {

    // re.IGNORECASE upstream (wallets may uppercase bech32 addresses).
    private val ADDRESS_REGEX = Regex(
        "^((bc1q|tb1q|bcrt1q|bc1p|tb1p|bcrt1p|[123]|[mn])[a-zA-HJ-NP-Z0-9]{25,64})",
        RegexOption.IGNORE_CASE
    )

    /**
     * [segment] may carry a "bitcoin:" prefix (ignored, per upstream: the
     * match runs on segment.split(":").last()).
     */
    /** (scriptType, network) or null. */
    fun detect(segment: String): Pair<String, String>? {
        val match = ADDRESS_REGEX.find(segment.split(":").last()) ?: return null
        val prefix = match.groupValues[2].lowercase()

        val (scriptType, network) = when (prefix) {
            "1" -> "p2pkh" to "main"
            "m", "n" -> "p2pkh" to "test"
            "3" -> "p2sh-p2wpkh" to "main"
            "2" -> "p2sh-p2wpkh" to "test"
            "bc1q" -> "p2wpkh" to "main"
            "tb1q" -> "p2wpkh" to "test"
            "bcrt1q" -> "p2wpkh" to "regtest"
            "bc1p" -> "p2tr" to "main"
            "tb1p" -> "p2tr" to "test"
            "bcrt1p" -> "p2tr" to "regtest"
            else -> return null
        }

        return scriptType to network
    }

    /** (address, scriptType, network) or null. */
    fun detectWithAddress(segment: String): Triple<String, String, String>? {
        val match = ADDRESS_REGEX.find(segment.split(":").last()) ?: return null
        val address = match.groupValues[1]
        val (scriptType, network) = detect(segment) ?: return null
        // Bech32 addresses are case-insensitive; normalize to lowercase.
        val normalized = if (scriptType == "p2wpkh" || scriptType == "p2tr") address.lowercase() else address
        return Triple(normalized, scriptType, network)
    }
}
