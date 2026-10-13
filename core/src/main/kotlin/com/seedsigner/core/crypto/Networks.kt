package com.seedsigner.core.crypto

/**
 * Network constants — byte-identical to embit 0.8.0 `networks.py`
 * (the same table upstream SeedSigner uses).
 */
object Networks {

    data class Network(
        val name: String,
        val p2pkh: Int,
        val p2sh: Int,
        val bech32: String,
        val versions: Map<String, Int>,   // xprv/xpub/yprv/zprv/Yprv/Zprv/ypub/zpub/Ypub/Zpub
        val coinType: Int,
    )

    private fun v(b1: Int, b2: Int, b3: Int, b4: Int) =
        (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4

    val MAINNET = Network(
        name = "main",
        p2pkh = 0x00,
        p2sh = 0x05,
        bech32 = "bc",
        versions = mapOf(
            "xprv" to v(0x04, 0x88, 0xad, 0xe4),
            "xpub" to v(0x04, 0x88, 0xb2, 0x1e),
            "yprv" to v(0x04, 0x9d, 0x78, 0x78),
            "zprv" to v(0x04, 0xb2, 0x43, 0x0c),
            "Yprv" to v(0x02, 0x95, 0xb0, 0x05),
            "Zprv" to v(0x02, 0xaa, 0x7a, 0x99),
            "ypub" to v(0x04, 0x9d, 0x7c, 0xb2),
            "zpub" to v(0x04, 0xb2, 0x47, 0x46),
            "Ypub" to v(0x02, 0x95, 0xb4, 0x3f),
            "Zpub" to v(0x02, 0xaa, 0x7e, 0xd3),
        ),
        coinType = 0,
    )

    val TESTNET = Network(
        name = "test",
        p2pkh = 0x6f,
        p2sh = 0xc4,
        bech32 = "tb",
        versions = mapOf(
            "xprv" to v(0x04, 0x35, 0x83, 0x94),
            "xpub" to v(0x04, 0x35, 0x87, 0xcf),
            "yprv" to v(0x04, 0x4a, 0x4e, 0x28),
            "zprv" to v(0x04, 0x5f, 0x18, 0xbc),
            "Yprv" to v(0x02, 0x42, 0x85, 0xb5),
            "Zprv" to v(0x02, 0x57, 0x50, 0x48),
            "ypub" to v(0x04, 0x4a, 0x52, 0x62),
            "zpub" to v(0x04, 0x5f, 0x1c, 0xf6),
            "Ypub" to v(0x02, 0x42, 0x89, 0xef),
            "Zpub" to v(0x02, 0x57, 0x54, 0x83),
        ),
        coinType = 1,
    )

    val REGTEST = Network(
        name = "regtest",
        p2pkh = 0x6f,
        p2sh = 0xc4,
        bech32 = "bcrt",
        versions = TESTNET.versions,
        coinType = 1,
    )

    /** Upstream `SettingsConstants.map_network_to_embit` mapping. */
    fun fromName(name: String): Network = when (name) {
        "mainnet" -> MAINNET
        "testnet" -> TESTNET
        "regtest" -> REGTEST
        else -> throw IllegalArgumentException("unknown network: $name")
    }
}
