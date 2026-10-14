package com.seedsigner.core.crypto

import java.security.MessageDigest
import java.security.spec.KeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.jvm.JvmStatic
import kotlin.text.Charsets

/**
 * BIP39 — byte-identical to upstream (docs/spec.md §3).
 *
 * Upstream reference:
 *  - `seedsigner_orig/src/seedsigner/helpers/mnemonic_generation.py`
 *  - embit `bip39` (mnemonic_from_bytes / mnemonic_to_bytes / mnemonic_to_seed)
 *
 * Dice and coin-flip entropy use the iancoleman.io / bitcoiner.guide
 * "Base 10" / "Binary" approach: the digit stream is treated as a string
 * and hashed via SHA-256. NOT compatible with iancoleman's "Dice" mode.
 */
object Bip39 {

    const val DICE_ROLLS_12WORD = 50
    const val DICE_ROLLS_24WORD = 99

    /** Official BIP39 English wordlist (2048 words). Also used by the v0.2 word picker. */
    val WORDLIST: List<String> by lazy {
        val text = Bip39::class.java.getResource("/wordlist/english.txt")?.readText()
            ?: error("BIP39 English wordlist resource missing")
        text.lines().filter { it.isNotBlank() }
            .also { require(it.size == 2048) { "wordlist must have 2048 words" } }
    }

    private val WORD_INDEX: Map<String, Int> by lazy {
        WORDLIST.withIndex().associate { (i, w) -> w to i }
    }

    // ---------- core BIP39 ----------

    /** entropy (16/20/24/28/32 bytes) -> space-separated mnemonic */
    @JvmStatic
    fun mnemonicFromEntropy(entropy: ByteArray): String {
        require(entropy.size in intArrayOf(16, 20, 24, 28, 32)) { "invalid entropy length" }
        val entropyBits = entropy.size * 8
        val checksumBits = entropyBits / 32
        val hash = sha256(entropy)
        val totalBits = entropyBits + checksumBits

        val words = ArrayList<String>(totalBits / 11)
        for (i in 0 until totalBits / 11) {
            var index = 0
            for (j in 0 until 11) {
                val bitPos = i * 11 + j
                val byte = if (bitPos < entropyBits) {
                    entropy[bitPos ushr 3]
                } else {
                    hash[(bitPos - entropyBits) ushr 3]
                }
                index = (index shl 1) or ((byte.toInt() shr (7 - (bitPos and 7))) and 1)
            }
            words.add(WORDLIST[index])
        }
        return words.joinToString(" ")
    }

    /** mnemonic (12/15/18/21/24 words) -> entropy. Throws on checksum mismatch. */
    @JvmStatic
    fun entropyFromMnemonic(mnemonic: String): ByteArray {
        val words = normalize(mnemonic).split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
        val totalBits = words.size * 11
        val checksumBits = totalBits / 33
        val entropyBits = totalBits - checksumBits
        require(entropyBits % 32 == 0 && entropyBits in intArrayOf(128, 160, 192, 224, 256)) {
            "invalid mnemonic length"
        }

        val bits = BooleanArray(totalBits)
        words.forEachIndexed { i, w ->
            val index = WORD_INDEX[w] ?: throw IllegalArgumentException("unknown BIP39 word: $w")
            for (j in 0 until 11) {
                bits[i * 11 + j] = (index ushr (10 - j)) and 1 == 1
            }
        }

        val entropy = ByteArray(entropyBits / 8)
        for (i in 0 until entropyBits) {
            if (bits[i]) entropy[i ushr 3] = ((entropy[i ushr 3].toInt() or (1 shl (7 - (i and 7))))).toByte()
        }

        val hash = sha256(entropy)
        for (i in 0 until checksumBits) {
            val expected = (hash[i ushr 3].toInt() shr (7 - (i and 7))) and 1 == 1
            require(bits[entropyBits + i] == expected) { "invalid mnemonic checksum" }
        }
        return entropy
    }

    /** Wordlist index of a (NFKD-normalized) word; throws on unknown words. */
    @JvmStatic
    fun wordIndex(word: String): Int =
        WORD_INDEX[normalize(word)] ?: throw IllegalArgumentException("unknown BIP39 word: $word")

    /** BIP39 seed: PBKDF2-HMAC-SHA512, 2048 iterations, salt "mnemonic"+passphrase (NFKD-normalized). */
    @JvmStatic
    fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val norm = normalize(mnemonic)
        val spec: KeySpec = PBEKeySpec(
            norm.toCharArray(),
            ("mnemonic" + normalize(passphrase)).toByteArray(Charsets.UTF_8),
            2048,
            512
        )
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
    }

    // ---------- entropy sources (upstream mnemonic_generation.py) ----------

    /**
     * String of 50 or 99 dice rolls -> 12- or 24-word mnemonic.
     * SHA-256 of the roll string; 12 words use the first 16 bytes.
     */
    @JvmStatic
    fun mnemonicFromDice(rollData: String): List<String> {
        require(rollData.length == DICE_ROLLS_12WORD || rollData.length == DICE_ROLLS_24WORD) {
            "dice rolls must be 50 (12 words) or 99 (24 words) long"
        }
        require(rollData.all { it in '1'..'6' }) { "dice rolls must be digits 1-6" }
        val entropy = sha256(rollData.toByteArray(Charsets.UTF_8))
        return if (rollData.length == DICE_ROLLS_12WORD) {
            mnemonicFromEntropy(entropy.copyOfRange(0, 16)).split(" ")
        } else {
            mnemonicFromEntropy(entropy).split(" ")
        }
    }

    /** String of 128 or 256 '0'/'1' coin flips -> 12- or 24-word mnemonic. */
    @JvmStatic
    fun mnemonicFromCoinFlips(coinFlips: String): List<String> {
        require(coinFlips.length == 128 || coinFlips.length == 256) {
            "coin flips must be 128 (12 words) or 256 (24 words) long"
        }
        require(coinFlips.all { it == '0' || it == '1' }) { "coin flips must be 0/1" }
        val entropy = sha256(coinFlips.toByteArray(Charsets.UTF_8))
        return if (coinFlips.length == 128) {
            mnemonicFromEntropy(entropy.copyOfRange(0, 16)).split(" ")
        } else {
            mnemonicFromEntropy(entropy).split(" ")
        }
    }

    /**
     * 11- or 23-word mnemonic (list or string) -> complete mnemonic with checksum.
     * Upstream: calculate_checksum(). 11/23 words get word[0] appended as a
     * temp final word; the result is re-derived so the last word is replaced
     * by the correct checksum word.
     */
    @JvmStatic
    fun calculateChecksum(mnemonic: List<String>): List<String> {
        val words = mnemonic.toMutableList()
        if (words.size == 11 || words.size == 23) {
            words.add(WORDLIST[0])
        }
        require(words.size == 12 || words.size == 24) { "pass in a 12- or 24-word mnemonic" }
        words.forEach { require(WORD_INDEX[it] != null) { "unknown BIP39 word: $it" } }

        // rebuild bits ignoring the final checksum word's bits, then re-derive
        val totalBits = words.size * 11
        val entropyBits = totalBits - totalBits / 33
        val bits = IntArray(totalBits)
        words.forEachIndexed { i, w ->
            val index = WORD_INDEX[w]!!
            for (j in 0 until 11) bits[i * 11 + j] = (index ushr (10 - j)) and 1
        }
        val entropy = ByteArray(entropyBits / 8)
        for (i in 0 until entropyBits) {
            if (bits[i] == 1) entropy[i ushr 3] = ((entropy[i ushr 3].toInt() or (1 shl (7 - (i and 7))))).toByte()
        }
        return mnemonicFromEntropy(entropy).split(" ")
    }

    /**
     * Partial final word for the user's coin-flip prefix:
     * 7 flips -> 0101010 + **** (last 4 bits replaced by checksum),
     * 3 flips -> 010 + ******** (last 8 bits replaced by checksum).
     * Upstream: get_partial_final_word().
     */
    @JvmStatic
    fun getPartialFinalWord(coinFlips: String): String {
        require(coinFlips.length in 1..11) { "expected 1-11 coin flips for the final word" }
        val binaryString = coinFlips + "0".repeat(11 - coinFlips.length)
        return WORDLIST[binaryString.toInt(2)]
    }

    // ---------- helpers ----------

    private fun normalize(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD)

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
