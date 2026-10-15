package com.seedsigner.app.views

import android.app.Activity
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Controller
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
import com.seedsigner.core.crypto.Networks
import com.seedsigner.core.descriptor.Descriptor
import com.seedsigner.core.descriptor.getMultisigAddress
import com.seedsigner.core.descriptor.getMultisigPolicy
import com.seedsigner.app.models.mapNetworkToEmbit
import java.util.HexFormat

/**
 * 1:1 port of the upstream multisig wallet descriptor flows (scan_views.py
 * ScanWalletDescriptorView, decode_qr.py wallet-descriptor QR types,
 * seed_views.py LoadMultisigWalletDescriptorView + MultisigWalletDescriptorView).
 */

/**
 * QR-text -> descriptor-string recognition (upstream DecodeQR type checks):
 *  - "pXofY ..." / {"label"...,"descriptor"...}  -> Specter animated QR
 *  - "multisig setup file" text                  -> multisig config file
 *  - raw "sortedmulti" descriptor string         -> generic
 */
internal object DescriptorQr {

    /**
     * 1:1 of upstream scan_views: the scanned trailing "zero branch, any
     * index" wildcard is rewritten to the {0,1} branch-set form so the
     * descriptor can verify change addresses too.
     */
    fun rewriteWildcards(descriptorStr: String): String {
        var s = descriptorStr
        if (Regex("\\[([0-9,a-f,A-F]+?)(\\/[^\\]]+?)\\].*?(\\/0\\/\\*)").findAll(s).any()) {
            s = Regex("(\\[[0-9,a-f,A-F]+?\\/[^\\]]+?\\].*?)(\\/0\\/\\*)").replace(s, "$1/{0,1}/*")
        } else if (Regex("(\\[[0-9,a-f,A-F]+?\\/[^\\]]+?\\][A-Za-z0-9]*?)([,,\\)])").findAll(s).any()) {
            s = Regex("(\\[[0-9,a-f,A-F]+?\\/[^\\]]+?\\][A-Za-z0-9]*?)([,,\\)])").replace(s, "$1/{0,1}/*$2")
        }
        return s
    }

    /** True when [segment] (already concatenated for multi-part) is a generic single-frame descriptor. */
    fun isGeneric(segment: String): Boolean = "sortedmulti" in segment

    fun isSpecter(segment: String): Boolean {
        val descStr = segment.replace("\n", "").replace(" ", "")
        return Regex("^p(\\d+)of(\\d+) ", RegexOption.IGNORE_CASE).find(segment) != null ||
            Regex("^\\{\"label\".*\"descriptor\"\\:.*", RegexOption.IGNORE_CASE).find(descStr) != null
    }

    fun isConfigFile(segment: String): Boolean = "multisig setup file" in segment.lowercase()

    /**
     * Upstream DecodeQR.multisig_setup_file_to_descriptor.
     */
    fun configFileToDescriptor(text: String): String {
        var m = 0
        var n = 0
        val xpubs = mutableListOf<Map<String, String>>()
        var derivation = ""
        var scriptType = ""

        for (rawLine in text.split("\n")) {
            if (rawLine.startsWith("#")) continue
            val l = rawLine.trim()
            if (!l.contains(":")) continue
            val (labelRaw, valueRaw) = l.split(":", limit = 2)
            val label = labelRaw.trim().lowercase()
            val value = valueRaw.trim()
            when {
                label == "policy" -> {
                    val match = Regex("(\\d+)\\D*(\\d+)").find(value)
                        ?: throw Exception("Policy line not supported")
                    m = match.groupValues[1].toInt()
                    n = match.groupValues[2].toInt()
                }
                label == "derivation" -> derivation = value
                label == "format" -> if (value.lowercase() in listOf("p2wsh", "p2sh-p2wsh", "p2wsh-p2sh")) {
                    scriptType = value.lowercase()
                }
                label.length == 8 -> {
                    if (xpubs.isEmpty()) repeat(n) { xpubs.add(emptyMap()) }
                    require(xpubs.size > xpubs.indexOfFirst { it.isEmpty() })
                    val slot = xpubs.indexOfFirst { it.isEmpty() }
                    xpubs[slot] = mapOf("xfp" to label, "key" to value)
                }
            }
        }

        require(xpubs.size == n && xpubs.none { it.isEmpty() }) { "bad or missing xpub" }
        require(m in 1..9 && n in 1..9) { "bad or missing policy" }
        require(derivation.isNotEmpty()) { "bad or missing derivation path" }
        require(scriptType in listOf("p2wsh", "p2sh-p2wsh", "p2wsh-p2sh")) { "bad or missing script format" }

        val (open, close) = if (scriptType == "p2wsh") {
            "wsh(sortedmulti($m" to "))"
        } else {
            "sh(wsh(sortedmulti($m" to ")))"
        }

        var descriptor = open
        for (x in xpubs) {
            var d = derivation
            if (d.startsWith("m")) d = d.substring(1)
            d = d.replace("'", "h")
            descriptor += ",[${x["xfp"]}$d]${x["key"]}/{0,1}/*"
        }
        descriptor += close
        return descriptor
    }

    /** Extract the descriptor string from Specter QR JSON (`{"label":...,"descriptor":...}`). */
    fun specterJsonDescriptor(json: String): String {
        // Minimal extraction (the QR may be large; a full JSON parser is
        // overkill for one known field).
        val i = json.indexOf("\"descriptor\"")
        require(i >= 0) { "No descriptor field in Specter wallet JSON" }
        val colon = json.indexOf(':', i)
        val q1 = json.indexOf('"', colon + 1)
        var j = q1 + 1
        val sb = StringBuilder()
        while (j < json.length) {
            val c = json[j]
            if (c == '\\' && j + 1 < json.length) {
                sb.append(json[j + 1]); j += 2; continue
            }
            if (c == '"') break
            sb.append(c); j++
        }
        return sb.toString()
    }
}

/**
 * Upstream ScanWalletDescriptorView: a ScanView that only accepts descriptor
 * QRs. Multi-part Specter QR accumulation (upstream BaseAnimatedQrDecoder)
 * lives in the companion: each part re-runs the view (fresh camera) but the
 * already-collected segments survive, exactly like upstream's collector.
 */
class ScanWalletDescriptorView(
    private val fresh: Boolean = true
) : ScanView() {

    companion object {
        val segments = mutableMapOf<Int, String>()
        var totalSegments = 0
        fun reset() {
            segments.clear()
            totalSegments = 0
        }
    }

    override fun onResult(index: Int): Destination {
        stopScanner()
        if (fresh) reset()
        val ur = decodedUr ?: return Destination(BackStackView::class.java)
        val raw = String(ur.cbor, Charsets.UTF_8).trim()

        // Single-frame types
        if (DescriptorQr.isGeneric(raw)) {
            val descStr = DescriptorQr.rewriteWildcards(raw)
            return loadDescriptor(descStr)
        }
        if (DescriptorQr.isConfigFile(raw)) {
            return loadDescriptor(DescriptorQr.configFileToDescriptor(raw))
        }

        // Specter animated: accumulate the split JSON frames.
        val part = Regex("^p(\\d+)of(\\d+) ", RegexOption.IGNORE_CASE).find(raw)
        if (part != null) {
            val num = part.groupValues[1].toInt()
            totalSegments = part.groupValues[2].toInt()
            segments[num] = raw.substring(part.value.length)
            if (segments.size == totalSegments) {
                val json = (1..totalSegments).joinToString("") { segments[it] ?: "" }
                reset()
                return loadDescriptor(DescriptorQr.rewriteWildcards(DescriptorQr.specterJsonDescriptor(json)))
            }
            // More parts to come: re-show the scanner.
            return Destination(ScanWalletDescriptorView::class.java,
                viewArgs = mapOf("fresh" to false), skipCurrentView = true)
        }

        return Destination(ErrorView::class.java,
            viewArgs = mapOf(
                "title" to "Error",
                "headline" to "Expected a wallet descriptor QR",
                "text" to "The scanned QR is not a wallet descriptor."
            ))
    }

    private fun loadDescriptor(descStr: String): Destination {
        return try {
            val descriptor = Descriptor.fromString(descStr)
            if (!descriptor.isBasicMultisig) {
                // Upstream: single-sig descriptors are not yet handled.
                return@loadDescriptor Destination(NotYetImplementedView::class.java, viewArgs = mapOf("feature" to "single-sig descriptors"))
            }
            controller.multisigWalletDescriptor = descriptor
            Destination(MultisigWalletDescriptorView::class.java, skipCurrentView = true)
        } catch (e: Exception) {
            Destination(ErrorView::class.java,
                viewArgs = mapOf(
                    "title" to "Error",
                    "headline" to "Invalid descriptor",
                    "text" to (e.message ?: "The descriptor could not be parsed.")
                ))
        }
    }
}

/** Upstream LoadMultisigWalletDescriptorView. */
class LoadMultisigWalletDescriptorView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Multisig Descriptor", showBack = false)
        screen.addButtons(listOf("Scan descriptor", "Cancel"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == 0) return Destination(ScanWalletDescriptorView::class.java,
            viewArgs = mapOf("fresh" to true))
        // Cancel: out of the PSBT flow it goes Back; from elsewhere, Home.
        return if (controller.resumeMainFlow == Controller.Flow.PSBT) {
            Destination(BackStackView::class.java)
        } else {
            Destination(MainMenuView::class.java, clearHistory = true)
        }
    }
}

/**
 * Upstream MultisigWalletDescriptorView: shows the policy + fingerprints and
 * routes back into whichever flow loaded it.
 */
class MultisigWalletDescriptorView : View() {

    override fun run(activity: Activity): Destination? {
        val descriptor = controller.multisigWalletDescriptor as? Descriptor
            ?: throw Exception("Routing error: multisig_wallet_descriptor hasn't been set")
        val (m, n) = getMultisigPolicy(descriptor)

        val screen = Ui.screen(activity, "Multisig Descriptor")
        screen.addText("$m of $n", 20f)
        for (key in descriptor.keys) {
            key.fingerprint?.let { screen.addText(HexFormat.of().formatHex(it), 14f, 0xFF9E9E9E.toInt()) }
        }

        val flow = controller.resumeMainFlow
        val button: String = when {
            flow == Controller.Flow.PSBT -> "Return to transaction"
            flow == Controller.Flow.VERIFY_MULTISIG_ADDR && controller.unverifiedAddress != null ->
                "Verify addr ${controller.unverifiedAddress!!["address"]!!}.substring(0, 7)}"
            flow == Controller.Flow.ADDRESS_EXPLORER -> "Address explorer"
            else -> "OK"
        }
        screen.addButtons(listOf(button))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) {
            controller.multisigWalletDescriptor = null
            return Destination(BackStackView::class.java)
        }
        val flow = controller.resumeMainFlow
        return when {
            // Jump straight back to PSBT change verification (upstream
            // restarts the change list at #0).
            flow == Controller.Flow.PSBT -> {
                controller.resumeMainFlow = null
                Destination(PSBTChangeDetailsView::class.java,
                    viewArgs = mapOf("changeAddressNum" to 0), skipCurrentView = true)
            }
            flow == Controller.Flow.VERIFY_MULTISIG_ADDR -> {
                controller.resumeMainFlow = null
                Destination(SeedAddressVerificationView::class.java,
                    viewArgs = mapOf("fresh" to true), skipCurrentView = true)
            }
            flow == Controller.Flow.ADDRESS_EXPLORER -> {
                controller.resumeMainFlow = null
                Destination(ToolsAddressExplorerAddressTypeView::class.java,
                    viewArgs = mapOf("seedIndex" to -1, "scriptType" to "", "customDerivation" to null),
                    skipCurrentView = true)
            }
            else -> Destination(MainMenuView::class.java)
        }
    }
}
