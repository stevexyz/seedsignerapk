package com.seedsigner.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Fixed test vectors from docs/dice_verification.md (50 dice rolls / 12 words):
 *   seed:      hole luggage safe present express tragic orbit shed switch metal identify path
 *   fingerprint: 8d9cced8
 *   zpub (BIP84, m/84h/0h/0h):
 *     zpub6qf9ziL759pzyhKMWaPfNSiCETkoA6oq3fbCDvXqcURiMtPnkEg3nH93W5mrSkvGPoJC9xTYZheYDsYoiYc5AkSk9iY3DkCJHkFgHMdijW6
 *   + receive/change addresses (BIP84)
 */
class Bip32VectorsTest {

    private val mnemonic =
        "hole luggage safe present express tragic orbit shed switch metal identify path"
    private val seed = Bip39.mnemonicToSeed(mnemonic)

    @Test
    fun `root fingerprint matches the documented 8d9cced8`() {
        val root = Bip32.HDKey.fromSeed(seed)
        assertEquals("8d9cced8", "%08x".format(root.fingerprintOfChild()))
    }

    @Test
    fun `BIP84 zpub matches the documented value`() {
        val root = Bip32.HDKey.fromSeed(seed)
        val account = Bip32.derive(root, "m/84h/0h/0h")
        assertEquals(
            "zpub6qf9ziL759pzyhKMWaPfNSiCETkoA6oq3fbCDvXqcURiMtPnkEg3nH93W5mrSkvGPoJC9xTYZheYDsYoiYc5AkSk9iY3DkCJHkFgHMdijW6",
            account.toPublic().serialize(Networks.MAINNET, "zpub")
        )
    }

    @Test
    fun `BIP84 receive addresses match the documented values`() {
        val root = Bip32.HDKey.fromSeed(seed)
        val receive = (0..4).map {
            Bip32.derive(root, "m/84h/0h/0h/0/$it").publicKey
        }
        assertEquals(
            listOf(
                "bc1q00lln3r4mt4uwvg7mxv96xgpewauwmggkex2ff",
                "bc1q0jj2cv965f3642mv4lgq5za80jtfpkd0jhjefr",
                "bc1qpecssejm2678v0rknk9tsxd5fsshezfr0vr5m5",
                "bc1qcsl37xn5rkfz8qhcfwq5u52acxecyyfz0kv4gh",
                "bc1q8ehx53re0wck4m9tzek8mlnctp2ztm7jq94zm4",
            ),
            receive.map { Address.p2wpkh(Networks.MAINNET, it) }
        )
    }

    @Test
    fun `BIP84 change addresses match the documented values`() {
        val root = Bip32.HDKey.fromSeed(seed)
        val change = (0..4).map {
            Bip32.derive(root, "m/84h/0h/0h/1/$it").publicKey
        }
        assertEquals(
            listOf(
                "bc1qz0ckhg3m349qpmweyn5v6r6tvx2wfw4nv8h75q",
                "bc1qme5tu2t424ws3z69u445q0yw88vpc7fwygra4r",
                "bc1qznuyuc087ky7fhv4nvlmll4p586ks4ggcyt36d",
                "bc1qjcqxv22j0g00pehruwwh34sw5znu6vp3myaspy",
                "bc1q6dpfl7czd22wt0max6p09vr6lvvpag7xw9u8lc",
            ),
            change.map { Address.p2wpkh(Networks.MAINNET, it) }
        )
    }

    @Test
    fun `xpub round trip through base58check`() {
        val root = Bip32.HDKey.fromSeed(seed)
        val account = Bip32.derive(root, "m/84h/0h/0h")
        val xpub = account.toPublic().serialize(Networks.MAINNET, "xpub")
        val parsed = Bip32.HDKey.parse(xpub)
        assertContentEquals(account.publicKey, parsed.publicKey)
        assertContentEquals(account.chainCode, parsed.chainCode)
        assertEquals(account.depth, parsed.depth)
        assertEquals(account.fingerprint, parsed.fingerprint)
    }
}
