package com.seedsigner.app.models

import android.content.Context
import org.json.JSONObject

/**
 * 1:1 port of the upstream settings model
 * (seedsigner_orig/src/seedsigner/models/settings_definition.py + settings.py),
 * restricted to the entries that make sense on Android.
 *
 * Excluded hardware-specific entries (documented, not functionality):
 * persistent_settings + microsd_toast_timer (no SD card on Android),
 * display_configuration / color_inverted / qr_brightness (no fixed panel),
 * camera_rotation (the app auto-rotates frames for detection).
 *
 * Persistence: plain JSON file in app-private storage (spec §1.4).
 */
class Settings {

    class Entry(
        val attrName: String,
        val displayName: String,
        val visibility: Int,          // VISIBILITY__*
        val type: Int,                // TYPE__*
        val selectionOptions: List<Pair<String, String>> = emptyList(),
        val defaultValue: Any? = null
    )

    object Const {
        // visibility
        const val VISIBILITY__GENERAL = 0
        const val VISIBILITY__ADVANCED = 1
        const val VISIBILITY__HIDDEN = 2

        // types
        const val TYPE__ENABLED_DISABLED = 0
        const val TYPE__SELECT_1 = 1
        const val TYPE__MULTISELECT = 2

        // option values (upstream SettingsConstants)
        const val OPTION__ENABLED = "E"
        const val OPTION__DISABLED = "D"
        const val OPTION__PROMPT = "P"
        const val OPTION__REQUIRED = "R"

        // networks
        const val MAINNET = "M"
        const val TESTNET = "T"
        const val REGTEST = "R"
        val ALL_NETWORKS = listOf(
            MAINNET to "Mainnet",
            TESTNET to "Testnet",
            REGTEST to "Regtest"
        )

        // qr density
        const val DENSITY__LOW = "L"
        const val DENSITY__MEDIUM = "M"
        const val DENSITY__HIGH = "H"
        val ALL_DENSITIES = listOf(
            DENSITY__LOW to "Low",
            DENSITY__MEDIUM to "Medium",
            DENSITY__HIGH to "High"
        )

        // sig types
        const val SINGLE_SIG = "ss"
        const val MULTISIG = "ms"
        val ALL_SIG_TYPES = listOf(
            SINGLE_SIG to "Single Sig",
            MULTISIG to "Multisig"
        )

        // script types (same set and order as upstream ALL_SCRIPT_TYPES)
        const val LEGACY_P2PKH = "leg"
        const val NATIVE_SEGWIT = "nat"
        const val NESTED_SEGWIT = "nes"
        const val TAPROOT = "tr"
        const val CUSTOM_DERIVATION = "cus"
        val ALL_SCRIPT_TYPES = listOf(
            LEGACY_P2PKH to "Legacy P2PKH",
            NATIVE_SEGWIT to "Native Segwit",
            NESTED_SEGWIT to "Nested Segwit",
            TAPROOT to "Taproot",
            CUSTOM_DERIVATION to "Custom Derivation"
        )

        // xpub qr formats
        const val XPUB_QR_FORMAT__UR_CRYPTO_ACCOUNT = "urca"
        const val XPUB_QR_FORMAT__STATIC = "sta"
        const val XPUB_QR_FORMAT__SPECTER_LEGACY = "spl"
        val ALL_XPUB_QR_FORMATS = listOf(
            XPUB_QR_FORMAT__UR_CRYPTO_ACCOUNT to "Animated (default)",
            XPUB_QR_FORMAT__STATIC to "Static",
            XPUB_QR_FORMAT__SPECTER_LEGACY to "Specter legacy"
        )

        // denominations
        const val BTC_DENOMINATION__BTC = "btc"
        const val BTC_DENOMINATION__SATS = "sats"
        const val BTC_DENOMINATION__THRESHOLD = "thr"
        const val BTC_DENOMINATION__BTCSATSHYBRID = "hyb"
        val ALL_BTC_DENOMINATIONS = listOf(
            BTC_DENOMINATION__BTC to "BTC",
            BTC_DENOMINATION__SATS to "sats",
            BTC_DENOMINATION__THRESHOLD to "Threshold at 0.01",
            BTC_DENOMINATION__BTCSATSHYBRID to "BTC | sats hybrid"
        )

        const val LOCALE__ENGLISH = "en"
        const val WORDLIST_LANGUAGE__ENGLISH = "en"
        const val LABEL__BIP39_PASSPHRASE = "BIP-39 Passphrase"

        // attr names
        const val SETTING__LOCALE = "locale"
        const val SETTING__WORDLIST_LANGUAGE = "wordlist_language"
        const val SETTING__BTC_DENOMINATION = "denomination"
        const val SETTING__NETWORK = "network"
        const val SETTING__QR_DENSITY = "qr_density"
        const val SETTING__SIG_TYPES = "sig_types"
        const val SETTING__SCRIPT_TYPES = "script_types"
        const val SETTING__XPUB_QR_FORMAT = "xpub_qr"
        const val SETTING__XPUB_DETAILS = "xpub_details"
        const val SETTING__PASSPHRASE = "passphrase"
        const val SETTING__COMPACT_SEEDQR = "compact_seedqr"
        const val SETTING__BIP85_CHILD_SEEDS = "bip85_child_seeds"
        const val SETTING__ELECTRUM_SEEDS = "electrum_seeds"
        const val SETTING__MESSAGE_SIGNING = "message_signing"
        const val SETTING__PRIVACY_WARNINGS = "privacy_warnings"
        const val SETTING__DIRE_WARNINGS = "dire_warnings"
        const val SETTING__QR_BRIGHTNESS_TIPS = "qr_brightness_tips"
        const val SETTING__PARTNER_LOGOS = "partner_logos"
    }

    private val C = Const

    val entries: List<Entry> = listOf(
        // General
        Entry(C.SETTING__LOCALE, "Language", C.VISIBILITY__GENERAL, C.TYPE__SELECT_1,
            listOf(C.LOCALE__ENGLISH to "English"), C.LOCALE__ENGLISH),
        Entry(C.SETTING__WORDLIST_LANGUAGE, "Mnemonic language", C.VISIBILITY__HIDDEN, C.TYPE__SELECT_1,
            listOf(C.WORDLIST_LANGUAGE__ENGLISH to "English"), C.WORDLIST_LANGUAGE__ENGLISH),
        Entry(C.SETTING__BTC_DENOMINATION, "Denomination display", C.VISIBILITY__GENERAL, C.TYPE__SELECT_1,
            C.ALL_BTC_DENOMINATIONS, C.BTC_DENOMINATION__THRESHOLD),
        // Advanced
        Entry(C.SETTING__NETWORK, "Bitcoin network", C.VISIBILITY__ADVANCED, C.TYPE__SELECT_1,
            C.ALL_NETWORKS, C.MAINNET),
        Entry(C.SETTING__QR_DENSITY, "QR code density", C.VISIBILITY__ADVANCED, C.TYPE__SELECT_1,
            C.ALL_DENSITIES, C.DENSITY__MEDIUM),
        Entry(C.SETTING__SIG_TYPES, "Sig types", C.VISIBILITY__ADVANCED, C.TYPE__MULTISELECT,
            C.ALL_SIG_TYPES, listOf(C.SINGLE_SIG, C.MULTISIG)),
        Entry(C.SETTING__SCRIPT_TYPES, "Script types", C.VISIBILITY__ADVANCED, C.TYPE__MULTISELECT,
            C.ALL_SCRIPT_TYPES, listOf(C.NATIVE_SEGWIT, C.NESTED_SEGWIT, C.TAPROOT)),
        Entry(C.SETTING__XPUB_QR_FORMAT, "Xpub QR format", C.VISIBILITY__ADVANCED, C.TYPE__MULTISELECT,
            C.ALL_XPUB_QR_FORMATS, listOf(C.XPUB_QR_FORMAT__UR_CRYPTO_ACCOUNT, C.XPUB_QR_FORMAT__STATIC)),
        Entry(C.SETTING__XPUB_DETAILS, "Show xpub details", C.VISIBILITY__ADVANCED, C.TYPE__ENABLED_DISABLED,
            emptyList(), C.OPTION__ENABLED),
        Entry(C.SETTING__PASSPHRASE, "BIP-39 passphrase", C.VISIBILITY__ADVANCED, C.TYPE__SELECT_1,
            listOf(C.OPTION__ENABLED to "Enabled", C.OPTION__DISABLED to "Disabled", C.OPTION__REQUIRED to "Required"),
            C.OPTION__ENABLED),
        Entry(C.SETTING__COMPACT_SEEDQR, "Compact SeedQR", C.VISIBILITY__ADVANCED, C.TYPE__ENABLED_DISABLED,
            emptyList(), C.OPTION__ENABLED),
        Entry(C.SETTING__BIP85_CHILD_SEEDS, "BIP-85 child seeds", C.VISIBILITY__ADVANCED, C.TYPE__ENABLED_DISABLED,
            emptyList(), C.OPTION__DISABLED),
        Entry(C.SETTING__ELECTRUM_SEEDS, "Electrum seeds", C.VISIBILITY__ADVANCED, C.TYPE__ENABLED_DISABLED,
            emptyList(), C.OPTION__DISABLED),
        Entry(C.SETTING__MESSAGE_SIGNING, "Message signing", C.VISIBILITY__ADVANCED, C.TYPE__ENABLED_DISABLED,
            emptyList(), C.OPTION__DISABLED),
        Entry(C.SETTING__PRIVACY_WARNINGS, "Show privacy warnings", C.VISIBILITY__ADVANCED, C.TYPE__ENABLED_DISABLED,
            emptyList(), C.OPTION__ENABLED),
        Entry(C.SETTING__DIRE_WARNINGS, "Show dire warnings", C.VISIBILITY__ADVANCED, C.TYPE__ENABLED_DISABLED,
            emptyList(), C.OPTION__ENABLED),
        Entry(C.SETTING__QR_BRIGHTNESS_TIPS, "Show QR brightness tips", C.VISIBILITY__ADVANCED, C.TYPE__ENABLED_DISABLED,
            emptyList(), C.OPTION__ENABLED),
        Entry(C.SETTING__PARTNER_LOGOS, "Show partner logos", C.VISIBILITY__ADVANCED, C.TYPE__ENABLED_DISABLED,
            emptyList(), C.OPTION__ENABLED)
    )

    private val values = HashMap<String, Any?>()

    init {
        entries.forEach { values[it.attrName] = it.defaultValue }
    }

    fun getEntry(attrName: String): Entry = entries.first { it.attrName == attrName }

    @Suppress("UNCHECKED_CAST")
    fun <T> getValue(attrName: String): T = values[attrName] as T

    fun setValue(attrName: String, value: Any?) {
        values[attrName] = value
    }

    fun getValueDisplayName(attrName: String): String {
        val entry = getEntry(attrName)
        val v = values[attrName]
        return when {
            entry.type == Const.TYPE__MULTISELECT ->
                (v as List<String>).joinToString(", ") { entry.selectionOptions.first { o -> o.first == it }.second }
            v is String && entry.selectionOptions.isNotEmpty() ->
                entry.selectionOptions.firstOrNull { it.first == v }?.second ?: v
            v is String && (v == Const.OPTION__ENABLED || v == Const.OPTION__DISABLED) ->
                if (v == Const.OPTION__ENABLED) "Enabled" else "Disabled"
            else -> v?.toString() ?: ""
        }
    }

    fun save(context: Context) {
        val json = JSONObject()
        values.forEach { (k, v) ->
            json.put(k, when (v) {
                is List<*> -> org.json.JSONArray(v)
                else -> v
            })
        }
        context.openFileOutput("settings.json", Context.MODE_PRIVATE)
            .use { it.write(json.toString().toByteArray()) }
    }

    fun load(context: Context) {
        try {
            val text = context.openFileInput("settings.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val json = JSONObject(text)
            for (entry in entries) {
                val k = entry.attrName
                if (!json.has(k)) continue
                val v = json.get(k)
                values[k] = if (entry.type == Const.TYPE__MULTISELECT) {
                    val arr = v as org.json.JSONArray
                    val list = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) list.add(arr.getString(i))
                    list
                } else v
            }
        } catch (e: Exception) {
            // no file yet (first run) or corrupt file: defaults stand
        }
    }
}
