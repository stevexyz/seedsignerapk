package com.seedsigner.core.crypto

import com.seedsigner.core.crypto.Hashes.hmacSha512

/**
 * 1:1 port of embit's bip85.py (BIP-85 application 39' = child mnemonics).
 * Reference: ~/toolchain/venv/.../embit/bip85.py (embit 0.8.0), the same
 * module upstream SeedSigner calls.
 *
 * Wordlist: English only (embit's LANGUAGES.ENGLISH = index 0), matching
 * upstream's TODO for other languages.
 */
object Bip85 {

    private val BIP85_MAGIC = "bip-entropy-from-k".toByteArray()

    /**
     * Derive app-specific BIP-85 entropy via
     * m/83696968'/app_index'/...path' then HMAC-SHA512(key=magic, data=secret key).
     */
    fun deriveEntropy(root: Bip32.HDKey, appIndex: Int, path: List<Int>): ByteArray {
        require(path.all { it >= 0 }) { "BIP-85 path elements must be non-hardened" }
        require(root.privateKey != null) { "need a private root key" }
        val derivation =
            listOf(Bip32.HARDENED or 83696968, Bip32.HARDENED or appIndex) +
                path.map { Bip32.HARDENED or it }
        var derived = root
        for (index in derivation) {
            derived = Bip32.child(derived, index)
        }
        return hmacSha512(BIP85_MAGIC, derived.privateKey!!)
    }

    /** Derive a 12/18/24-word child mnemonic (BIP-85 app index 39). */
    fun deriveMnemonic(root: Bip32.HDKey, numWords: Int = 12, index: Int = 0): List<String> {
        require(numWords in listOf(12, 18, 24)) { "num_words must be 12, 18 or 24" }
        // path = [langcode=0 (English), num_words, index]
        val entropy = deriveEntropy(root, 39, listOf(0, numWords, index))
        val entropyPart = entropy.copyOfRange(0, numWords * 4 / 3)
        return Bip39.mnemonicFromEntropy(entropyPart).split(" ")
    }
}
