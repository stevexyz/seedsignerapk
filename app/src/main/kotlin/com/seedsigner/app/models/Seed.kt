package com.seedsigner.app.models

import com.seedsigner.app.models.Settings.Const
import com.seedsigner.core.crypto.Bip32
import com.seedsigner.core.crypto.Bip39
import com.seedsigner.core.crypto.Networks
import java.text.Normalizer

/**
 * 1:1 port of the core of seedsigner_orig/src/seedsigner/models/seed.py
 * (BIP-39 Seed; ElectrumSeed lands in Chunk D when it's enabled).
 *
 * Memory-only (spec §1.2): a Seed exists only while this process lives.
 */
class Seed(
    mnemonic: List<String>,
    passphrase: String = "",
    val wordlistLanguageCode: String = Const.WORDLIST_LANGUAGE__ENGLISH
) {
    private val _mnemonicList: List<String>
    private var _passphrase: String
    var seedBytes: ByteArray
        private set

    init {
        require(mnemonic.isNotEmpty()) { "Must initialize a Seed with a mnemonic List<String>" }
        // NFKD-normalize + split, exactly like upstream
        _mnemonicList = Normalizer.normalize(mnemonic.joinToString(" ").trim(), Normalizer.Form.NFKD)
            .split(" ")
        _passphrase = if (passphrase.isNotEmpty()) Normalizer.normalize(passphrase, Normalizer.Form.NFKD) else ""
        seedBytes = Bip39.mnemonicToSeed(mnemonicStr, _passphrase)
    }

    val passphrase: String get() = _passphrase

    /** Upstream `set_passphrase`: NFKD-normalize and regenerate the seed. */
    fun setPassphrase(passphrase: String, regenerateSeed: Boolean = true) {
        _passphrase = if (passphrase.isNotEmpty()) Normalizer.normalize(passphrase, Normalizer.Form.NFKD) else ""
        if (regenerateSeed) seedBytes = Bip39.mnemonicToSeed(mnemonicStr, _passphrase)
    }

    companion object {
        fun getWordlist(code: String = Const.WORDLIST_LANGUAGE__ENGLISH): List<String> =
            when (code) {
                Const.WORDLIST_LANGUAGE__ENGLISH -> Bip39.WORDLIST
                else -> throw Exception("Unrecognized wordlist_language_code $code")
            }
    }

    val mnemonicStr: String get() = _mnemonicList.joinToString(" ")
    val mnemonicList: List<String> get() = _mnemonicList
    val wordlist: List<String> get() = getWordlist(wordlistLanguageCode)
    val hasPassphrase: Boolean get() = _passphrase != ""
    val passphraseLabel: String get() = Const.LABEL__BIP39_PASSPHRASE
    val seedqrSupported: Boolean get() = true
    val bip85Supported: Boolean get() = true

    /**
     * First 4 bytes of the root xprv's hash160, hex — matches upstream
     * `Seed.get_fingerprint` byte for byte.
     */
    fun getFingerprint(network: String = Const.MAINNET): String {
        val embitNet = Networks.fromName(mapNetworkToEmbit(network))
        val root = Bip32.HDKey.fromSeed(seedBytes, embitNet.versions["xprv"]!!)
        val fp = root.fingerprintOfChild()
        return fp.toUInt().toString(16).padStart(8, '0')
    }

    /**
     * Upstream `Seed.detect_version`: embit's `bip32.detect_version` with
     * default "xpub".
     */
    fun detectVersion(
        derivationPath: String,
        network: String = Const.MAINNET,
        sigType: String = Const.SINGLE_SIG
    ): String {
        val embitNet = Networks.fromName(mapNetworkToEmbit(network))
        return Bip32.detectVersion(derivationPath, "xpub", embitNet)
    }

    /** BIP-39 seeds have no script/derivation overrides (upstream base class). */
    val scriptOverride: String? get() = null

    fun derivationOverride(sigType: String = Const.SINGLE_SIG): String? = null

    /** The xpub's pieces, as the QR encoders need them (encode_qr.py prep_xpub). */
    data class DerivedXpub(
        val key: ByteArray,          // 33-byte compressed pub key
        val chainCode: ByteArray,    // 32 bytes
        val parentFingerprint: ByteArray, // 4 bytes, big-endian
        val rootFingerprint: ByteArray,   // 4 bytes, big-endian
        val base58: String
    ) {
        /** Upstream `xpubstring`: "[fingerprint/derivation]xpub_base58". */
        fun xpubstring(fingerprint: ByteArray, derivation: String): String =
            "[${fingerprintHex(fingerprint)}${derivation.drop(1)}]$base58"

        companion object {
            fun intToFingerprint(i: Int): ByteArray =
                byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())

            fun fingerprintHex(b: ByteArray): String =
                b.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xFF) }
                    .toString(16).padStart(8, '0')
        }
    }

    fun deriveXpub(
        derivationPath: String,
        network: String = Const.MAINNET
    ): DerivedXpub {
        val version = detectVersion(derivationPath, network)
        val embitNet = Networks.fromName(mapNetworkToEmbit(network))
        val root = Bip32.HDKey.fromSeed(seedBytes, embitNet.versions["xprv"]!!)
        val rootFingerprint = DerivedXpub.intToFingerprint(root.fingerprintOfChild())
        val xprv = Bip32.derive(root, derivationPath)
        val xpub = xprv.toPublic()
        val xpubKey = Bip32.HDKey(
            embitNet.versions[version]!!, xpub.depth, xpub.fingerprint, xpub.childNumber,
            xpub.chainCode, null, xpub.publicKey
        )
        return DerivedXpub(
            key = xpub.publicKey,
            chainCode = xpub.chainCode,
            parentFingerprint = DerivedXpub.intToFingerprint(xpub.fingerprint),
            rootFingerprint = rootFingerprint,
            base58 = xpubKey.serialize(embitNet, version)
        )
    }

    fun getXpub(walletPath: String = "/", network: String = Const.MAINNET): String {
        val embitNet = Networks.fromName(mapNetworkToEmbit(network))
        val root = Bip32.HDKey.fromSeed(seedBytes, embitNet.versions["xprv"]!!)
        val derived = Bip32.derive(root.toPublic(), walletPath)
        // version from the detected prefix (xpub/zpub/ypub/tpub/vpub/upub)
        val prefix = Bip32.detectVersion(walletPath, "xpub", embitNet)
        val pub = Bip32.HDKey(
            embitNet.versions[prefix]!!,
            derived.depth, derived.fingerprint, derived.childNumber,
            derived.chainCode, null, derived.publicKey
        )
        return pub.serialize(embitNet, prefix)
    }

    override fun equals(other: Any?): Boolean =
        other is Seed && other.seedBytes.contentEquals(seedBytes)

    override fun hashCode(): Int = seedBytes.contentHashCode()
}

/**
 * Upstream `SettingsConstants.map_network_to_embit` (settings_definition.py).
 */
fun mapNetworkToEmbit(network: String): String = when (network) {
    Const.MAINNET -> "main"
    Const.TESTNET -> "test"
    Const.REGTEST -> "regtest"
    else -> throw Exception("Unknown network: $network")
}
