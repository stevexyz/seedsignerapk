package com.seedsigner.app.views

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Controller
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
import com.seedsigner.app.models.Seed
import com.seedsigner.app.models.Settings
import com.seedsigner.core.crypto.Bip32
import com.seedsigner.core.crypto.Networks
import com.seedsigner.core.qr.ur2.UR
import com.seedsigner.core.qr.ur2.UREncoder
import com.seedsigner.core.qr.ur2.UrTypes

/**
 * 1:1 port of the upstream "Export Xpub" flow (seed_views.py, the
 * SeedExportXpub*View classes) and its QR encoders (models/encode_qr.py).
 *
 * viewArgs key names mirror the upstream constructor parameters
 * (Controller.instantiateView maps them by name):
 *   seedIndex: Int            (our app keeps seeds by list index, not by object)
 *   sigType: String           Settings.Const.SINGLE_SIG / MULTISIG
 *   scriptType: String
 *   customDerivation: String?
 *   xpubQrFormat: String
 *   derivationPath: String
 */

/** Upstream SeedExportXpubSigTypeView. */
class SeedExportXpubSigTypeView : View() {

    override fun run(activity: Activity): Destination? {
        val seedIndex = viewArgs["seedIndex"] as Int
        val sigTypes = controller.settings.getValue<List<String>>(Settings.Const.SETTING__SIG_TYPES)

        if (sigTypes.size == 1) {
            return Destination(SeedExportXpubScriptTypeView::class.java,
                viewArgs = mapOf("seedIndex" to seedIndex, "sigType" to sigTypes[0]),
                skipCurrentView = true)
        }

        sigOptions = Settings.Const.ALL_SIG_TYPES.filter { it.first in sigTypes }
        val screen = Ui.screen(activity, "Export Xpub")
        screen.addButtons(sigOptions.map { it.second })
        controller.showScreen(screen)
        this.seedIndex = seedIndex
        return null
    }

    private var seedIndex: Int = 0
    private var sigOptions: List<Pair<String, String>> = emptyList()

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val sigType = sigOptions[index].first
        return Destination(SeedExportXpubScriptTypeView::class.java,
            viewArgs = mapOf("seedIndex" to seedIndex, "sigType" to sigType))
    }
}

/** Upstream SeedExportXpubScriptTypeView. */
class SeedExportXpubScriptTypeView : View() {

    override fun run(activity: Activity): Destination? {
        val seedIndex = viewArgs["seedIndex"] as Int
        val sigType = viewArgs["sigType"] as String
        val args = mutableMapOf<String, Any?>("seedIndex" to seedIndex, "sigType" to sigType)

        var scriptTypes = controller.settings.getValue<List<String>>(Settings.Const.SETTING__SCRIPT_TYPES)
        val seed = controller.storage.seeds[seedIndex]
        seed.scriptOverride?.let { scriptTypes = listOf(it) }

        val explorerFlow = controller.resumeMainFlow == Controller.Flow.ADDRESS_EXPLORER

        if (scriptTypes.size == 1) {
            args["scriptType"] = scriptTypes[0]
            return if (explorerFlow) {
                Destination(ToolsAddressExplorerAddressTypeView::class.java,
                    mapOf("seedIndex" to seedIndex, "scriptType" to scriptTypes[0], "customDerivation" to null),
                    skipCurrentView = true)
            } else {
                Destination(SeedExportXpubQRFormatView::class.java, args + ("customDerivation" to null),
                    skipCurrentView = true)
            }
        }

        scriptOptions = Settings.Const.ALL_SCRIPT_TYPES.filter { it.first in scriptTypes }
        val screen = Ui.screen(activity, if (explorerFlow) "Address Explorer" else "Export Xpub")
        screen.addButtons(scriptOptions.map { it.second })
        controller.showScreen(screen)
        this.seedIndex = seedIndex
        this.sigType = sigType
        return null
    }

    private var seedIndex: Int = 0
    private var sigType: String = Settings.Const.SINGLE_SIG
    private var scriptOptions: List<Pair<String, String>> = emptyList()

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val scriptType = scriptOptions[index].first
        val args = mapOf<String, Any?>(
            "seedIndex" to seedIndex, "sigType" to sigType,
            "scriptType" to scriptType, "customDerivation" to null
        )
        return if (scriptType == Settings.Const.CUSTOM_DERIVATION) {
            Destination(SeedExportXpubCustomDerivationView::class.java, args)
        } else if (controller.resumeMainFlow == Controller.Flow.ADDRESS_EXPLORER) {
            Destination(ToolsAddressExplorerAddressTypeView::class.java,
                mapOf("seedIndex" to seedIndex, "scriptType" to scriptType, "customDerivation" to null))
        } else {
            Destination(SeedExportXpubQRFormatView::class.java, args)
        }
    }
}

/** Upstream SeedExportXpubCustomDerivationView. */
class SeedExportXpubCustomDerivationView : View() {

    private var input: EditText? = null
    private var seedIndex: Int = 0
    private var sigType: String = Settings.Const.SINGLE_SIG
    private var scriptType: String = Settings.Const.CUSTOM_DERIVATION

    override fun run(activity: Activity): Destination? {
        seedIndex = viewArgs["seedIndex"] as Int
        sigType = viewArgs["sigType"] as String
        scriptType = viewArgs["scriptType"] as String

        val screen = Ui.screen(activity, "Custom Derivation")
        screen.addText("Enter the derivation path")
        val et = EditText(activity)
        et.setText("m/")
        et.setTextColor(0xFFFFFFFF.toInt())
        et.setBackgroundColor(0xFF2A3138.toInt())
        screen.body.addView(et, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        screen.addButtons(listOf("Continue"))
        controller.showScreen(screen)
        input = et
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val customDerivation = input?.text?.toString() ?: "m/"
        return Destination(SeedExportXpubQRFormatView::class.java,
            viewArgs = mapOf<String, Any?>(
                "seedIndex" to seedIndex, "sigType" to sigType,
                "scriptType" to scriptType, "customDerivation" to customDerivation
            ))
    }
}

/** Upstream SeedExportXpubQRFormatView. */
class SeedExportXpubQRFormatView : View() {

    private var seedIndex: Int = 0
    private var sigType: String = Settings.Const.SINGLE_SIG
    private var scriptType: String = Settings.Const.NATIVE_SEGWIT
    private var customDerivation: String? = null
    private var formatButtons: List<String> = emptyList()

    override fun run(activity: Activity): Destination? {
        seedIndex = viewArgs["seedIndex"] as Int
        sigType = viewArgs["sigType"] as String
        scriptType = viewArgs["scriptType"] as String
        customDerivation = viewArgs["customDerivation"] as? String

        val formats = controller.settings.getValue<List<String>>(Settings.Const.SETTING__XPUB_QR_FORMAT)
        if (formats.size == 1) {
            return Destination(SeedExportXpubWarningView::class.java,
                baseArgs() + ("xpubQrFormat" to formats[0]),
                skipCurrentView = true)
        }

        formatOptions = Settings.Const.ALL_XPUB_QR_FORMATS.filter { it.first in formats }
        val screen = Ui.screen(activity, "Xpub QR Format")
        screen.addButtons(formatOptions.map { it.second })
        controller.showScreen(screen)
        return null
    }

    private var formatOptions: List<Pair<String, String>> = emptyList()

    private fun baseArgs() = mapOf<String, Any?>(
        "seedIndex" to seedIndex, "sigType" to sigType,
        "scriptType" to scriptType, "customDerivation" to customDerivation
    )

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val format = formatOptions[index].first
        return Destination(SeedExportXpubWarningView::class.java, baseArgs() + ("xpubQrFormat" to format))
    }
}

/** Upstream SeedExportXpubWarningView. */
class SeedExportXpubWarningView : View() {

    override fun run(activity: Activity): Destination? {
        if (controller.settings.getValue<String>(Settings.Const.SETTING__PRIVACY_WARNINGS) ==
            Settings.Const.OPTION__DISABLED) {
            // Skip the warning entirely (upstream skips with skip_current_view)
            return Destination(SeedExportXpubDetailsView::class.java, viewArgs, skipCurrentView = true)
        }

        val screen = Ui.screen(activity, "Privacy Leak!")
        screen.addText("Xpub can be used to view all future transactions.", 18f)
        screen.addButtons(listOf("I Understand"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        return when (index) {
            Ui.RET_CODE_BACK -> Destination(BackStackView::class.java)
            else -> Destination(SeedExportXpubDetailsView::class.java, viewArgs, skipCurrentView = true)
        }
    }
}

/**
 * Upstream SeedExportXpubDetailsView: resolves the derivation path and shows
 * fingerprint / path / xpub before the QR display.
 */
class SeedExportXpubDetailsView : View() {

    private var seedIndex: Int = 0
    private var sigType: String = Settings.Const.SINGLE_SIG
    private var scriptType: String = Settings.Const.NATIVE_SEGWIT
    private var xpubQrFormat: String = Settings.Const.XPUB_QR_FORMAT__UR_CRYPTO_ACCOUNT
    private var customDerivation: String? = null
    private var resolvedPath: String = "m/"

    /** Resolved upstream: custom > seed override > standard. */
    private fun resolvePath(seed: Seed): String {
        val network = controller.settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        return if (scriptType == Settings.Const.CUSTOM_DERIVATION) {
            customDerivation ?: "m/"
        } else {
            seed.derivationOverride(sigType)
                ?: com.seedsigner.app.models.Derivation.standardDerivationPath(network, sigType, scriptType)
        }
    }

    override fun run(activity: Activity): Destination? {
        seedIndex = viewArgs["seedIndex"] as Int
        sigType = viewArgs["sigType"] as String
        scriptType = viewArgs["scriptType"] as String
        xpubQrFormat = viewArgs["xpubQrFormat"] as String
        customDerivation = viewArgs["customDerivation"] as? String

        val seed = controller.storage.seeds[seedIndex]
        resolvedPath = resolvePath(seed)

        if (controller.settings.getValue<String>(Settings.Const.SETTING__XPUB_DETAILS) ==
            Settings.Const.OPTION__DISABLED) {
            return Destination(SeedExportXpubQRDisplayView::class.java, qrDisplayArgs())
        }

        val network = controller.settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        val derived = seed.deriveXpub(resolvedPath, network)

        val screen = Ui.screen(activity, "Xpub Details")
        screen.addText("Fingerprint: ${Seed.DerivedXpub.fingerprintHex(derived.rootFingerprint)}", 18f)
        screen.addText(resolvedPath, 18f)
        screen.addText(derived.base58, 13f)
        screen.addButtons(listOf("Continue"))
        controller.showScreen(screen)
        return null
    }

    private fun qrDisplayArgs() = mapOf<String, Any?>(
        "seedIndex" to seedIndex,
        "xpubQrFormat" to xpubQrFormat,
        "derivationPath" to resolvedPath,
        "sigType" to sigType
    )

    override fun onResult(index: Int): Destination {
        return when (index) {
            Ui.RET_CODE_BACK -> Destination(BackStackView::class.java)
            else -> Destination(SeedExportXpubQRDisplayView::class.java, qrDisplayArgs())
        }
    }
}

/**
 * Upstream SeedExportXpubQRDisplayView: builds the QR parts for the selected
 * format (Static / Specter legacy pXofY / UR crypto-account fountain) and
 * hands them to [QrDisplayView].
 */
class SeedExportXpubQRDisplayView : View() {

    override fun run(activity: Activity): Destination {
        val seedIndex = viewArgs["seedIndex"] as Int
        val xpubQrFormat = viewArgs["xpubQrFormat"] as String
        val derivationPath = viewArgs["derivationPath"] as String
        val sigType = viewArgs["sigType"] as String

        val seed = controller.storage.seeds[seedIndex]
        val network = controller.settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        val density = controller.settings.getValue<String>(Settings.Const.SETTING__QR_DENSITY)
        val derived = seed.deriveXpub(derivationPath, network)
        val xpubstring = derived.xpubstring(derived.rootFingerprint, derivationPath)

        val parts: List<String> = when (xpubQrFormat) {
            Settings.Const.XPUB_QR_FORMAT__STATIC -> listOf(xpubstring)
            Settings.Const.XPUB_QR_FORMAT__SPECTER_LEGACY -> specterParts(xpubstring, density)
            else -> urParts(derived, derivationPath, network, density, sigType)
        }

        return Destination(QrDisplayView::class.java,
            viewArgs = mapOf("parts" to parts, "title" to "Xpub", "returnToMain" to true))
    }

    /** Upstream SpecterLegacyXPubQrEncoder._create_parts (40/65/90 per density). */
    private fun specterParts(xpubstring: String, density: String): List<String> {
        val chunk = when (density) {
            Settings.Const.DENSITY__LOW -> 40
            Settings.Const.DENSITY__HIGH -> 90
            else -> 65
        }
        val qrCnt = (xpubstring.length - 1) / chunk + 1
        if (qrCnt == 1) return listOf(xpubstring.substring(0, minOf(chunk, xpubstring.length)))
        val parts = mutableListOf<String>()
        var cnt = 0
        var start = 0
        while (cnt < qrCnt) {
            val stop = minOf(start + chunk, xpubstring.length)
            parts.add("p${cnt + 1}of$qrCnt " + xpubstring.substring(start, stop))
            start = stop
            cnt++
        }
        return parts
    }

    /** Upstream UrXpubQrEncoder: UR("crypto-account", account.to_cbor()). */
    private fun urParts(
        derived: Seed.DerivedXpub,
        derivationPath: String,
        network: String,
        density: String,
        sigType: String
    ): List<String> {
        val maxFragment = when (density) {
            Settings.Const.DENSITY__LOW -> 10
            Settings.Const.DENSITY__HIGH -> 120
            else -> 30
        }

        val cbor = UrTypes.xpubAccountCbor(
            rootFingerprint = derived.rootFingerprint,
            key = derived.key,
            chainCode = derived.chainCode,
            parentFingerprint = derived.parentFingerprint,
            derivation = derivationPath,
            network = network,
            scriptTags = scriptTagsFor(derivationPath)
        )
        val encoder = UREncoder(UR("crypto-account", cbor), maxFragment)
        return (0 until encoder.fountainEncoder.seqLen()).map { encoder.nextPart() }
    }

    /**
     * Upstream UrXpubQrEncoder's derivation->Output tag selection.
     * Each entry is one Output's ordered script-expression tags (outermost
     * first), matching the SCRIPT_EXPRESSION_TAG_MAP lookups.
     */
    private fun scriptTagsFor(derivationPath: String): List<List<Long>> {
        val comps = derivationPath.split("/")
            .drop(1) // "m"
            .filter { it.isNotEmpty() }
            .map { it.removeSuffix("'").removeSuffix("h").removeSuffix("H").toInt() }

        val t = UrTypes
        return when (comps.firstOrNull()) {
            84 -> listOf(listOf(t.SCRIPT_WPKH))
            49 -> listOf(listOf(t.SCRIPT_SH, t.SCRIPT_WPKH))
            48 -> if (comps.size >= 4) {
                when (comps[3]) {
                    2 -> listOf(listOf(t.SCRIPT_WSH))
                    1 -> listOf(listOf(t.SCRIPT_SH, t.SCRIPT_WSH))
                    else -> allScriptTags()
                }
            } else allScriptTags()
            86 -> listOf(listOf(t.SCRIPT_TR))
            44 -> listOf(listOf(t.SCRIPT_PKH))
            45 -> listOf(listOf(t.SCRIPT_SH))
            else -> allScriptTags()
        }
    }

    private fun allScriptTags(): List<List<Long>> {
        val t = UrTypes
        return listOf(
            listOf(t.SCRIPT_WPKH),
            listOf(t.SCRIPT_SH, t.SCRIPT_WPKH),
            listOf(t.SCRIPT_WSH),
            listOf(t.SCRIPT_SH, t.SCRIPT_WSH),
            listOf(t.SCRIPT_PKH),
            listOf(t.SCRIPT_SH)
        )
    }
}
