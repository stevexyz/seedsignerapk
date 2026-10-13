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
    val passphrase: String = "",
    val wordlistLanguageCode: String = Const.WORDLIST_LANGUAGE__ENGLISH
) {
    private val _mnemonicList: List<String>
    private val _passphrase: String
    val seedBytes: ByteArray

    init {
        require(mnemonic.isNotEmpty()) { "Must initialize a Seed with a mnemonic List<String>" }
        // NFKD-normalize + split, exactly like upstream
        _mnemonicList = Normalizer.normalize(mnemonic.joinToString(" ").trim(), Normalizer.Form.NFKD)
            .split(" ")
        _passphrase = if (passphrase.isNotEmpty()) Normalizer.normalize(passphrase, Normalizer.Form.NFKD) else ""
        seedBytes = Bip39.mnemonicToSeed(mnemonicStr, _passphrase)
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
