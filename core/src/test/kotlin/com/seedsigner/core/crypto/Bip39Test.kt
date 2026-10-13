package com.seedsigner.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Fixed test vectors. Sources:
 *  - dice vectors: docs/dice_verification.md (50 rolls / 12 words, 99 rolls / 24 words)
 *  - BIP39 spec vector: entropy 00..00 -> "abandon ... about"
 */
class Bip39Test {

    // docs/dice_verification.md
    private val rolls50 = "65515223131652132161133154444123616466443112153441"
    private val rolls99 = "655152231316521321611331544441236164664431121534415633526456254462245546236542364246312613322234612"

    @Test
    fun `dice 50 rolls produces the 12-word vector`() {
        assertEquals(
            "hole luggage safe present express tragic orbit shed switch metal identify path",
            Bip39.mnemonicFromDice(rolls50).joinToString(" ")
        )
    }

    @Test
    fun `dice 99 rolls produces the 24-word vector`() {
        assertEquals(
            "eyebrow obvious such suggest poet seven breeze blame virtual frown dynamic donor harsh pigeon express broccoli easy apology scatter force recipe shadow claim radio",
            Bip39.mnemonicFromDice(rolls99).joinToString(" ")
        )
    }

    @Test
    fun `BIP39 spec zero entropy vector`() {
        val entropy = ByteArray(16)
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            Bip39.mnemonicFromEntropy(entropy)
        )
        // round trip
        assertContentEquals(entropy, Bip39.entropyFromMnemonic(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        ))
    }

    @Test
    fun `mnemonic to seed is deterministic 64 bytes`() {
        val seed = Bip39.mnemonicToSeed(
            "hole luggage safe present express tragic orbit shed switch metal identify path"
        )
        assertEquals(64, seed.size)
        assertContentEquals(seed, Bip39.mnemonicToSeed(
            "hole luggage safe present express tragic orbit shed switch metal identify path"
        ))
        // passphrase changes the seed
        kotlin.test.assertNotEquals(
            seed.contentHashCode(),
            Bip39.mnemonicToSeed(
                "hole luggage safe present express tragic orbit shed switch metal identify path",
                "TREZOR"
            ).contentHashCode()
        )
    }

    @Test
    fun `entropy from mnemonic rejects bad checksum`() {
        assertFailsWith<IllegalArgumentException> {
            // last word corrupted -> checksum mismatch
            Bip39.entropyFromMnemonic(
                "hole luggage safe present express tragic orbit shed switch metal identify path zoo"
            )
        }
    }

    @Test
    fun `calculate checksum completes an 11-word mnemonic`() {
        val complete = Bip39.mnemonicFromDice(rolls50)
        val partial = complete.dropLast(1)
        val result = Bip39.calculateChecksum(partial)

        // Upstream behavior (embit mnemonic_to_bytes + mnemonic_from_bytes):
        // the injected word[0] zeroes the last 7 entropy bits, so the final
        // word is a pure checksum word, not the original 12th word.
        assertEquals(partial, result.dropLast(1))
        val entropy = Bip39.entropyFromMnemonic(result.joinToString(" ")) // checksum valid
        assertEquals(0, entropy[15].toInt() and 0b00000111)
    }

    @Test
    fun `partial final word lookup`() {
        // 7 flips "0101010" + 4 zero bits -> index 0b01010100000 = 672
        assertEquals(Bip39.WORDLIST[672], Bip39.getPartialFinalWord("0101010"))
    }

}
