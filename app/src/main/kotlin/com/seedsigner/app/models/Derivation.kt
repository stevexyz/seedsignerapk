package com.seedsigner.app.models

import com.seedsigner.app.models.Settings.Const

/**
 * 1:1 port of seedsigner_orig/src/seedsigner/helpers/embit_utils.py
 * (`get_standard_derivation_path`).
 */
object Derivation {

    fun standardDerivationPath(
        network: String = Const.MAINNET,
        walletType: String = Const.SINGLE_SIG,
        scriptType: String = Const.NATIVE_SEGWIT
    ): String {
        val networkPath = when (network) {
            Const.MAINNET -> "0'"
            Const.TESTNET -> "1'"
            Const.REGTEST -> "1'"
            else -> throw Exception("Unexpected network")
        }

        return if (walletType == Const.SINGLE_SIG) {
            when (scriptType) {
                Const.LEGACY_P2PKH -> "m/44'/${networkPath}/0'"
                Const.NESTED_SEGWIT -> "m/49'/${networkPath}/0'"
                Const.NATIVE_SEGWIT -> "m/84'/${networkPath}/0'"
                Const.TAPROOT -> "m/86'/${networkPath}/0'"
                else -> throw Exception("Unexpected script type")
            }
        } else {
            when (scriptType) {
                Const.LEGACY_P2PKH -> "m/45'" // BIP-45
                Const.NESTED_SEGWIT -> "m/48'/${networkPath}/0'/1'"
                Const.NATIVE_SEGWIT -> "m/48'/${networkPath}/0'/2'"
                Const.TAPROOT -> throw Exception("Taproot multisig not yet supported")
                else -> throw Exception("Unexpected script type")
            }
        }
    }
}
