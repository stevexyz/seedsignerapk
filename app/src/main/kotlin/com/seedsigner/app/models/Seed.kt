package com.seedsigner.app.models

import com.seedsigner.app.models.Settings.Const
import com.seedsigner.core.crypto.Bip32
import com.seedsigner.core.crypto.Bip39
import com.seedsigner.core.crypto.Networks
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.PBEKeySpec
import java.security.spec.KeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.SecretKeySpec

/**
 * 1:1 port of the core of seedsigner_orig/src/seedsigner/models/seed.py.
 *
 * Memory-only (spec §1.2): a Seed exists only while this process lives.
 */
abstract class Seed(
    mnemonic: List<String>,
    passphrase: String = "",
    val wordlistLanguageCode: String = Const.WORDLIST_LANGUAGE__ENGLISH
) {
    private val _mnemonicList: List<String>
    protected var _passphrase: String
    var seedBytes: ByteArray = ByteArray(0)
        protected set

    init {
        require(mnemonic.isNotEmpty()) { "Must initialize a Seed with a mnemonic List<String>" }
        // NFKD-normalize + split, exactly like upstream
        _mnemonicList = Normalizer.normalize(mnemonic.joinToString(" ").trim(), Normalizer.Form.NFKD)
            .split(" ")
        _passphrase = if (passphrase.isNotEmpty()) Normalizer.normalize(passphrase, Normalizer.Form.NFKD) else ""
        _generateSeed()
    }

    /** Upstream `Seed._generate_seed` (base: BIP-39). Subclasses override. */
    protected open fun _generateSeed() {
        seedBytes = Bip39.mnemonicToSeed(mnemonicStr, _passphrase)
    }

    val passphrase: String get() = _passphrase

    /** Upstream `set_passphrase`: NFKD-normalize and regenerate the seed. */
    open fun setPassphrase(passphrase: String, regenerateSeed: Boolean = true) {
        _passphrase = if (passphrase.isNotEmpty()) Normalizer.normalize(passphrase, Normalizer.Form.NFKD) else ""
        if (regenerateSeed) _generateSeed()
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
    open val seedqrSupported: Boolean get() = true
    open val bip85Supported: Boolean get() = true

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
    open val scriptOverride: String? get() = null

    open fun derivationOverride(sigType: String = Const.SINGLE_SIG): String? = null

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

    /**
     * Upstream SeedBase: seeds that can sign normal PSBTs. BIP-39 seeds
     * qualify; Electrum seeds do not (upstream ElectrumSeed sets False).
     */
    open val isStandard: Boolean get() = true

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

/**
 * Upstream `Bip39Seed(Seed)` — the default seed type.
 */
class Bip39Seed(
    mnemonic: List<String>,
    passphrase: String = "",
    wordlistLanguageCode: String = Const.WORDLIST_LANGUAGE__ENGLISH
) : Seed(mnemonic, passphrase, wordlistLanguageCode)

/**
 * 1:1 port of upstream `ElectrumSeed(Seed)`: only 12 words, version prefix
 * must be "100" (Segwit format), and the seed is
 * `PBKDF2-SHA512(mnemonic, "electrum"+passphrase, 2048)`.
 *
 * Upstream quirks kept: the base passphrase is NFKD-normalized like BIP-39,
 * but the Electrum passphrase override is lowercased + whitespace-collapsed
 * (and NOT NFKD-normalized) before being concatenated into the PBKDF2 salt.
 */
class ElectrumSeed(
    mnemonic: List<String>,
    passphrase: String = "",
    wordlistLanguageCode: String = Const.WORDLIST_LANGUAGE__ENGLISH
) : Seed(mnemonic, passphrase, wordlistLanguageCode) {

    private val _electrumPassphrase: String
        get() = if (_passphrase.isNotEmpty()) normalizeElectrumPassphrase(_passphrase) else ""

    init {
        // The base init generated the seed before this subclass existed:
        // regenerate now that the passphrase override is available.
        _generateSeed()
    }

    override fun _generateSeed() {
        if (mnemonicList.size != 12) {
            throw Exception("Unsupported Electrum seed length: ${mnemonicList.size}")
        }
        // Version check: first 3 hex chars of HMAC-SHA512("Seed version", mnemonic)
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec("Seed version".toByteArray(Charsets.US_ASCII), "HmacSHA512"))
        val digest = mac.doFinal(mnemonicStr.toByteArray(Charsets.UTF_8))
        val prefix = digest.take(3).joinToString("") { "%02x".format(it) }
        if (ELECTRUM_SEED_SEGWIT != prefix) {
            throw Exception("Unsupported Electrum seed format: $prefix")
        }
        val pbkdf2 = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = PBEKeySpec(
            mnemonicStr.toCharArray(),
            ("electrum" + _electrumPassphrase).toByteArray(Charsets.UTF_8),
            ELECTRUM_PBKDF2_ROUNDS,
            512
        )
        seedBytes = pbkdf2.generateSecret(spec).encoded
    }

    /** Upstream `ElectrumSeed.set_passphrase`: the passphrase override is
     *  stored in normalized-lowercase form and the seed is regenerated with
     *  it. */
    // setPassphrase inherits the base behavior: the _electrumPassphrase
    // getter recomputes from the (base-updated) _passphrase, so the
    // regenerated seed uses the normalized override automatically.


    override val scriptOverride: String get() = Const.NATIVE_SEGWIT

    override fun derivationOverride(sigType: String): String =
        if (sigType == Const.SINGLE_SIG) "m/0h" else "m/1h"

    override val isStandard: Boolean get() = false

    companion object {
        const val ELECTRUM_SEED_SEGWIT = "100"
        const val ELECTRUM_PBKDF2_ROUNDS = 2048

        /** 1:1 of upstream `normalize_electrum_passphrase`. */
        fun normalizeElectrumPassphrase(passphrase: String): String {
            var p = Normalizer.normalize(passphrase, Normalizer.Form.NFKD)
            p = p.lowercase()
            p = p.split("\\s+".toRegex()).joinToString(" ")
            return p
        }
    }
}
