package com.seedsigner.core.crypto

import com.seedsigner.core.crypto.Hashes.sha256

/**
 * 1:1 port of seedsigner_orig/src/seedsigner/helpers/mnemonic_generation.py
 * (SeedSigner's internal mnemonic generation utility).
 *
 * Dice and coin flips follow the iancoleman.io/bip39 + bitcoiner.guide/seed
 * "Base 10" / "Binary" mode approach: the digit stream is treated as string
 * data and hashed via SHA256. The dice method is NOT compatible with
 * iancoleman's "Dice" mode.
 *
 * Wordlist: English only, like the rest of this port (upstream threads a
 * wordlist argument through; every call site passes the setting, and the
 * port's only wordlist is English).
 */
object MnemonicGeneration {

    const val DICE__NUM_ROLLS__12WORD = 50
    const val DICE__NUM_ROLLS__24WORD = 99

    /**
     * Provide 12- or 24 words (11/23 get the wordlist's `0000` word injected
     * as temp final word); returns the complete mnemonic with the correct
     * checksum, as a list.
     */
    fun calculateChecksum(mnemonic: List<String>): List<String> {
        val words = mnemonic.toMutableList()
        if (words.size in listOf(11, 23)) {
            words.add(Bip39.WORDLIST[0])
        }
        require(words.size == 12 || words.size == 24) { "Pass in a 12- or 24-word mnemonic" }

        // Extract the entropy bits, ignoring the checksum bits (upstream uses
        // embit's mnemonic_to_bytes(ignore_checksum=True): the user may have
        // picked their own final word or we injected 0000).
        val entropyBits = words.size * 11 - words.size * 11 / 33
        val entropy = ByteArray(entropyBits / 8)
        words.forEachIndexed { i, w ->
            val index = Bip39.wordIndex(w)
            for (j in 0 until 11) {
                if (i * 11 + j < entropyBits && (index ushr (10 - j)) and 1 == 1) {
                    entropy[i * 11 + j shr 3] =
                        ((entropy[i * 11 + j shr 3].toInt() or (1 shl (7 - ((i * 11 + j) and 7))))).toByte()
                }
            }
        }

        // mnemonicFromEntropy recomputes the proper checksum bits.
        return Bip39.mnemonicFromEntropy(entropy).split(" ")
    }

    /** Upstream generate_mnemonic_from_bytes. */
    fun generateMnemonicFromBytes(entropyBytes: ByteArray): List<String> =
        Bip39.mnemonicFromEntropy(entropyBytes).split(" ")

    /**
     * A string of 50 or 99 dice rolls -> 12- or 24-word mnemonic.
     */
    fun generateMnemonicFromDice(rollData: String): List<String> {
        var entropyBytes = sha256(rollData.toByteArray())
        if (rollData.length == DICE__NUM_ROLLS__12WORD) {
            // 12-word mnemonic; only use 128 bits / 16 bytes
            entropyBytes = entropyBytes.copyOfRange(0, 16)
        }
        return Bip39.mnemonicFromEntropy(entropyBytes).split(" ")
    }

    /**
     * A string of 128 or 256 0s and 1s -> 12- or 24-word mnemonic.
     */
    fun generateMnemonicFromCoinFlips(coinFlips: String): List<String> {
        var entropyBytes = sha256(coinFlips.toByteArray())
        if (coinFlips.length == 128) {
            // 12-word mnemonic; only use 128 bits / 16 bytes
            entropyBytes = entropyBytes.copyOfRange(0, 16)
        }
        return Bip39.mnemonicFromEntropy(entropyBytes).split(" ")
    }

    /**
     * The partial final word for the given coin flips:
     * 7 flips (12-word): bits + 4 checksum bits
     * 3 flips (24-word): bits + 8 checksum bits
     */
    fun getPartialFinalWord(coinFlips: String): String {
        val binaryString = coinFlips + "0".repeat(11 - coinFlips.length)
        return Bip39.WORDLIST[binaryString.toInt(2)]
    }
}
