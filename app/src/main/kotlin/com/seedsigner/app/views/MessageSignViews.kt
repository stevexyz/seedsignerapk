package com.seedsigner.app.views

import android.app.Activity
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Controller
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
import com.seedsigner.app.models.Seed
import com.seedsigner.app.models.Settings
import com.seedsigner.app.models.mapNetworkToEmbit
import com.seedsigner.core.crypto.Address
import com.seedsigner.core.crypto.Bip32
import com.seedsigner.core.crypto.MessageSigner
import com.seedsigner.core.crypto.Networks
import com.seedsigner.core.crypto.Secp256k1

/**
 * 1:1 port of the upstream message-signing flow (seed_views.py
 * SeedSignMessage*View + helpers/embit_utils.py). Entry: a scanned
 * `signmessage {derivation_path} ascii:{message}` QR or the "Sign message"
 * option in the seed menu.
 */

/**
 * 1:1 port of upstream embit_utils.parse_derivation_path (single sig paths).
 * Returns null for fields it cannot parse; [cleanMatch] false when any is null.
 */
internal data class AddrFormat(
    val scriptType: String?,
    val network: String?,            // our settings value: "M" / ["T","R"] collapsed
    val isChange: Boolean?,
    val index: Int?,
    val walletDerivationPath: String?,
    val cleanMatch: Boolean
) {
    companion object {
        fun parse(derivationPath: String): AddrFormat {
            val path = derivationPath.replace("'", "h")
            val sections = path.split("/")
            if (sections.size > 1 && sections[1] == "48h") {
                throw Exception("Not implemented")
            }
            val scriptType = when (sections.getOrNull(1)) {
                "44h" -> Settings.Const.LEGACY_P2PKH
                "49h" -> Settings.Const.NESTED_SEGWIT
                "84h" -> Settings.Const.NATIVE_SEGWIT
                "86h" -> Settings.Const.TAPROOT
                else -> Settings.Const.CUSTOM_DERIVATION
            }
            // Upstream: 0h -> mainnet; 1h -> [testnet, regtest]. Our settings
            // has one value; keep upstream's two-network group as a list.
            val network: List<String>? = when (sections.getOrNull(2)) {
                "0h" -> listOf(Settings.Const.MAINNET)
                "1h" -> listOf(Settings.Const.TESTNET, Settings.Const.REGTEST)
                else -> null
            }
            val isChange: Boolean? =
                if (sections.size >= 2 && sections[sections.size - 2] in listOf("0", "1"))
                    sections[sections.size - 2] == "1" else null
            val index: Int? = sections.lastOrNull()?.toIntOrNull()

            val walletDerivationPath =
                if (isChange != null && index != null) sections.dropLast(2).joinToString("/") else null

            val cleanMatch = network != null && isChange != null && index != null && walletDerivationPath != null
            return AddrFormat(scriptType, network?.firstOrNull(), isChange, index, walletDerivationPath, cleanMatch)
        }
    }
}

/**
 * Upstream SeedSignMessageStartView.
 */
class SeedSignMessageStartView(
    private val derivationPath: String = "",
    private val message: String = ""
) : View() {

    override fun run(activity: Activity): Destination? {
        val data = controller.signMessageData ?: mutableMapOf<String, Any?>().also {
            controller.signMessageData = it
        }
        data["derivation_path"] = derivationPath
        data["message"] = message
        data["addr_format"] = AddrFormat.parse(derivationPath)

        return if (controller.signMessageData?.get("seed") != null) {
            Destination(SeedSignMessageConfirmMessageView::class.java,
                viewArgs = mapOf("pageNum" to 0), skipCurrentView = true)
        } else {
            controller.resumeMainFlow = Controller.Flow.SIGN_MESSAGE
            // Upstream: SeedSelectSeedView(flow=SIGN_MESSAGE); our in-memory
            // seeds menu is the port of that screen.
            Destination(SeedsMenuView::class.java, skipCurrentView = true)
        }
    }
}

/**
 * Upstream SeedSignMessageConfirmMessageView. Upstream paginates by screen
 * pixel dimensions; the touch port uses fixed ~200-char pages at word
 * boundaries (documented simplification).
 */
class SeedSignMessageConfirmMessageView(
    private val pageNum: Int = 0
) : View() {

    companion object {
        const val PAGE_CHARS = 200
    }

    private fun pagedMessage(): List<String> {
        val msg = controller.signMessageData!!["message"] as String
        if (msg.length <= PAGE_CHARS) return listOf(msg)
        val pages = mutableListOf<String>()
        var rest = msg
        while (rest.isNotEmpty()) {
            if (rest.length <= PAGE_CHARS) {
                pages.add(rest)
                break
            }
            var cut = rest.lastIndexOf(' ', PAGE_CHARS)
            if (cut < 0) cut = PAGE_CHARS
            pages.add(rest.substring(0, cut).trimEnd())
            rest = rest.substring(cut).trimStart()
        }
        return pages
    }

    override fun run(activity: Activity): Destination? {
        val pages = pagedMessage()
        val title = if (pages.size == 1) "Review Message" else "Message (pt ${pageNum + 1}/${pages.size})"
        val screen = Ui.screen(activity, title)
        screen.addText(pages[pageNum], 16f)
        screen.addButtons(listOf("Next"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) {
            if (pageNum == 0) {
                // Exiting this flow entirely
                controller.resumeMainFlow = null
                controller.signMessageData = null
            }
            return Destination(BackStackView::class.java)
        }
        val pages = pagedMessage()
        return if (pageNum == pages.size - 1) {
            Destination(SeedSignMessageConfirmAddressView::class.java)
        } else {
            Destination(SeedSignMessageConfirmMessageView::class.java, viewArgs = mapOf("pageNum" to (pageNum + 1)))
        }
    }
}

/** Upstream SeedSignMessageConfirmAddressView. */
class SeedSignMessageConfirmAddressView : View() {

    private var address = ""

    override fun run(activity: Activity): Destination? {
        val data = controller.signMessageData
            ?: throw Exception("Routing error: sign_message_data hasn't been set")
        val seed = data["seed"] as? Seed
            ?: throw Exception("Routing error: sign_message_data hasn't been set")
        val derivationPath = data["derivation_path"] as String
        val addrFormat = data["addr_format"] as AddrFormat

        if (!addrFormat.cleanMatch || addrFormat.scriptType == Settings.Const.CUSTOM_DERIVATION) {
            throw Exception("Signing messages for custom derivation paths not supported")
        }

        val settingsNetwork = settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        // Upstream: the path's network must be the current one (the testnet
        // group counts as matching either testnet or regtest).
        val pathNetworks = when (addrFormat.network) {
            Settings.Const.MAINNET -> listOf(Settings.Const.MAINNET)
            else -> listOf(Settings.Const.TESTNET, Settings.Const.REGTEST)
        }
        if (settingsNetwork !in pathNetworks) {
            controller.resumeMainFlow = null
            controller.signMessageData = null
            return Destination(ErrorView::class.java,
                viewArgs = mapOf(
                    "title" to "Network Mismatch",
                    "headline" to "Wrong Network",
                    "text" to "The message's derivation path ($derivationPath) does not match the current network ($settingsNetwork)."
                ))
        }

        val embitNet = Networks.fromName(mapNetworkToEmbit(settingsNetwork))
        val fullKey = Bip32.derive(Bip32.HDKey.fromSeed(seed.seedBytes, version = embitNet.versions["xprv"]!!), derivationPath)
        val pubkey = fullKey.publicKey
        address = when (addrFormat.scriptType) {
            Settings.Const.LEGACY_P2PKH -> Address.p2pkh(embitNet, pubkey)
            Settings.Const.NESTED_SEGWIT -> Address.p2shP2wpkh(embitNet, pubkey)
            Settings.Const.TAPROOT -> {
                // Taproot messages sign with the key-path tweaked internal key,
                // but the address shown/derived upstream is from the raw key's
                // x-only with no script tree (upstream get_single_sig_address).
                Address.p2trAddress(embitNet, Secp256k1.taprootTweakPub(pubkey.copyOfRange(1, 33)))
            }
            else -> Address.p2wpkh(embitNet, pubkey)
        }

        val screen = Ui.screen(activity, "Confirm Address")
        screen.addText(derivationPath, 13f, 0xFF9E9E9E.toInt())
        screen.addText(address, 18f)
        screen.addButtons(listOf("Sign message"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return Destination(SeedSignMessageSignedMessageQRView::class.java)
    }
}

/** Upstream SeedSignMessageSignedMessageQRView. */
class SeedSignMessageSignedMessageQRView : View() {

    override fun run(activity: Activity): Destination? {
        val data = controller.signMessageData
        val seed = data!!["seed"] as Seed
        val derivationPath = data["derivation_path"] as String
        val message = data["message"] as String

        val settingsNetwork = settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        val signed = MessageSigner.signMessage(
            seedBytes = seed.seedBytes,
            derivation = derivationPath,
            msg = message.toByteArray(Charsets.UTF_8),
            compressed = true,
            network = Networks.fromName(mapNetworkToEmbit(settingsNetwork))
        )

        // cleanup
        controller.resumeMainFlow = null
        controller.signMessageData = null

        // Upstream: a single static QR (GenericStaticQrEncoder).
        return Destination(QrDisplayView::class.java,
            viewArgs = mapOf(
                "parts" to listOf(signed),
                "title" to "Signed Message",
                "returnToMain" to true
            ),
            skipCurrentView = true)
    }
}
