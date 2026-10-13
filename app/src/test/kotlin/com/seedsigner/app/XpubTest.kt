package com.seedsigner.app

import com.seedsigner.app.models.Seed
import com.seedsigner.app.models.Settings
import com.seedsigner.app.models.Derivation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the xpub export math to the vectors in docs/dice_verification.md
 * (50 dice rolls -> 12 words; independent of any crosscheck script).
 */
class XpubTest {

    private val mnemonic = listOf(
        "hole", "luggage", "safe", "present", "express", "tragic",
        "orbit", "shed", "switch", "metal", "identify", "path"
    )

    @Test
    fun fingerprint_matches_dice_vector() {
        val seed = Seed(mnemonic)
        assertEquals("8d9cced8", seed.getFingerprint(Settings.Const.MAINNET))
    }

    @Test
    fun bip84_zpub_matches_dice_vector() {
        val seed = Seed(mnemonic)
        val path = Derivation.standardDerivationPath(
            Settings.Const.MAINNET, Settings.Const.SINGLE_SIG, Settings.Const.NATIVE_SEGWIT
        )
        assertEquals("m/84'/0'/0'", path)
        val derived = seed.deriveXpub(path, Settings.Const.MAINNET)
        assertEquals(
            "zpub6qf9ziL759pzyhKMWaPfNSiCETkoA6oq3fbCDvXqcURiMtPnkEg3nH93W5mrSkvGPoJC9xTYZheYDsYoiYc5AkSk9iY3DkCJHkFgHMdijW6",
            derived.base58
        )
        assertEquals("8d9cced8", Seed.DerivedXpub.fingerprintHex(derived.rootFingerprint))
    }

    @Test
    fun xpubstring_format_matches_upstream() {
        val seed = Seed(mnemonic)
        val path = "m/84'/0'/0'"
        val derived = seed.deriveXpub(path, Settings.Const.MAINNET)
        val s = derived.xpubstring(derived.rootFingerprint, path)
        assertEquals("[8d9cced8/84'/0'/0']${derived.base58}", s)
    }
}
