package com.seedsigner.core.psbt

import com.seedsigner.core.crypto.Bip39
import com.seedsigner.core.crypto.Networks
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for the E2E "Change: 0 sat" bug.
 *
 * The bug was in the E2E fixture, not the parser: the PSBT's change output
 * carried no `bip32_derivations`, so no wallet (this one included) could
 * tell the 4,998 sat output belonged to the seed and it was classified as a
 * destination. The parser itself is crosschecked 1:1 against upstream
 * (see CrosscheckPsbtParser).
 *
 * The fixture below is the corrected E2E PSBT (testnet, single-sig p2wpkh,
 * BIP84): input 55,000,000 sats at 84h/1h/0h/0/0, paying 50,000,000 to an
 * external destination and 4,998,000 back to the seed's change branch
 * 84h/1h/0h/1/0 (fee 2,000). Both derivations are stored as FULL paths from
 * the root with the ROOT fingerprint — the convention this parser verifies
 * (the fingerprint is matched against the seed's, the path is derived from
 * the root; claims with a foreign fingerprint are treated as someone else's).
 */
class ChangeDetectionTest {

    // psbt.bin from the E2E fixture set (magic psbt\xff, 280 bytes).
    private val psbtB64 = "cHNidP8BAHECAAAAAfg057lxnk97zZTfA96wGuI2PxIog7PUx2VRwfOLqyF9AAAAAAD/////AoDw+gIAAAAAFgAU4IitueF7OJ9MzypgQRLdScV9RhNwQ0wAAAAAABYAFNJsaO8ff2jEMNxSIOFg/SLZAaDyAAAAAAABAR/AO0cDAAAAABYAFJc235r7BQ902nByOkjYUFONUy4ZIgYCMH2CEwHsjEDnVv38WP2ygqyLaClgCDw08ndGldbcgGsYjZzO2FQAAIABAACAAAAAgAAAAAAAAAAAAAAiAgO+yolOg+gD8RQbo/zhCk9cjirB32wtbNqHK7EPSmNKPRiNnM7YVAAAgAEAAIAAAACAAQAAAAAAAAAA"

    private val mnemonic =
        "hole luggage safe present express tragic orbit shed switch metal identify path"

    @Test
    fun `change output with derivation claim is detected as change`() {
        val psbt = Psbt.parse(Base64.getDecoder().decode(psbtB64))
        val parser = PsbtParser(psbt, Bip39.mnemonicToSeed(mnemonic), Networks.TESTNET)

        assertEquals(50_000_000L, parser.spendAmount)
        assertEquals(4_998_000L, parser.changeAmount)
        assertEquals(2_000L, parser.feeAmount)
        assertEquals(55_000_000L, parser.inputAmount)
        assertEquals(1, parser.changeData.size)
        assertEquals(1, parser.destinationAddresses.size)
        assertEquals(50_000_000L, parser.destinationAmounts[0])

        // The change output is on the change branch (.../1/0).
        val changePath = parser.changeData[0].verifiedDerivationPath
        assertTrue(
            PsbtParser.isChangeBranch(changePath),
            "expected change branch for path $changePath"
        )
    }

    @Test
    fun `identical output without derivation claim is a destination`() {
        // Strip the change output's bip32_derivations: the same coins then look
        // like an external spend. This is exactly what the broken fixture did.
        val psbt = Psbt.parse(Base64.getDecoder().decode(psbtB64))
        psbt.outputs[1].bip32Derivations.clear()
        val parser = PsbtParser(psbt, Bip39.mnemonicToSeed(mnemonic), Networks.TESTNET)

        assertEquals(54_998_000L, parser.spendAmount)
        assertEquals(0L, parser.changeAmount)
        assertEquals(0, parser.changeData.size)
        assertEquals(2, parser.destinationAddresses.size)
    }
}
