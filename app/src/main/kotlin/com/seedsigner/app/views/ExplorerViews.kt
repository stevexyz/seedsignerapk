package com.seedsigner.app.views

import android.app.Activity
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Controller
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
import com.seedsigner.app.models.Seed
import com.seedsigner.app.models.Settings
import com.seedsigner.core.crypto.Address
import com.seedsigner.core.crypto.Bip32
import com.seedsigner.core.crypto.Networks
import com.seedsigner.app.models.mapNetworkToEmbit

/**
 * 1:1 port of the upstream Address Explorer (tools_views.py
 * ToolsAddressExplorer*View), single-sig-from-seed sources.
 *
 * Multisig (descriptor) sources land in the descriptor-model chunk.
 */

/** Upstream ToolsAddressExplorerSelectSourceView. */
class ToolsAddressExplorerSelectSourceView : View() {

    override fun run(activity: Activity): Destination? {
        val seeds = controller.storage.seeds
        val network = controller.settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        val labels = seeds.map { it.getFingerprint(network) } +
            listOf("Scan a seed", "Scan wallet descriptor", "Enter 12-word seed", "Enter 24-word seed")
        sourceLabels = labels
        sourceSeedCount = seeds.size
        val screen = Ui.screen(activity, "Address Explorer")
        screen.addButtons(labels)
        controller.showScreen(screen)
        return null
    }

    private var sourceLabels: List<String> = emptyList()
    private var sourceSeedCount = 0

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)

        // Every option goes through a side flow first; the controller re-routes
        // when it completes (upstream comment, verbatim behavior).
        controller.resumeMainFlow = Controller.Flow.ADDRESS_EXPLORER

        if (index < sourceSeedCount) {
            return Destination(SeedExportXpubScriptTypeView::class.java,
                viewArgs = mapOf("seedIndex" to index, "sigType" to Settings.Const.SINGLE_SIG))
        }
        return when (sourceLabels[index]) {
            "Scan a seed" -> Destination(ScanView::class.java)
            "Scan wallet descriptor" -> Destination(LoadMultisigWalletDescriptorView::class.java)
            "Enter 12-word seed" -> {
                controller.storage.initPendingMnemonic(numWords = 12)
                Destination(SeedMnemonicEntryView::class.java,
                    viewArgs = mapOf("curWordIndex" to 0, "isCalcFinalWord" to false))
            }
            "Enter 24-word seed" -> {
                controller.storage.initPendingMnemonic(numWords = 24)
                Destination(SeedMnemonicEntryView::class.java,
                    viewArgs = mapOf("curWordIndex" to 0, "isCalcFinalWord" to false))
            }
            else -> Destination(BackStackView::class.java)
        }
    }
}

/**
 * Upstream ToolsAddressExplorerAddressTypeView: stores the explorer's
 * working data in the controller and chooses Receive/Change.
 */
class ToolsAddressExplorerAddressTypeView(
    private val seedIndex: Int,
    private val scriptType: String,
    private val customDerivation: String? = null
) : View() {

    override fun run(activity: Activity): Destination? {
        val network = controller.settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        // seedIndex < 0 = descriptor source (not ported yet).
        val seed: Seed? = if (seedIndex >= 0) controller.storage.seeds[seedIndex] else null

        val data = mutableMapOf<String, Any?>(
            "seedIndex" to seedIndex,
            "network" to network,
            "script_type" to scriptType
        )

        val seedDerivationOverride = seed?.derivationOverride(Settings.Const.SINGLE_SIG) ?: ""
        val derivationPath = when {
            scriptType == Settings.Const.CUSTOM_DERIVATION && customDerivation != null -> customDerivation
            seedDerivationOverride.isNotEmpty() -> seedDerivationOverride
            else -> com.seedsigner.app.models.Derivation.standardDerivationPath(
                network = network,
                walletType = Settings.Const.SINGLE_SIG,
                scriptType = scriptType
            )
        }
        if (seed != null) {
            data["derivation_path"] = derivationPath
            val embitNetwork = Networks.fromName(mapNetworkToEmbit(network))
            val root = Bip32.HDKey.fromSeed(seed.seedBytes, version = embitNetwork.versions["xprv"]!!)
            data["xpub"] = Bip32.derive(root, derivationPath)
        } else {
            // Descriptor source (upstream stores the wallet_descriptor).
            data["wallet_descriptor"] = controller.multisigWalletDescriptor
        }
        controller.addressExplorerData = data

        val screen = Ui.screen(activity, "Address Explorer")
        seed?.let {
            screen.addText("Seed ${it.getFingerprint(network)}", 14f, 0xFF9E9E9E.toInt())
        }
        if (seed == null) {
            controller.multisigWalletDescriptor?.let {
                val (m, n) = com.seedsigner.core.descriptor.getMultisigPolicy(it)
                screen.addText("$m / $n multisig", 14f, 0xFF9E9E9E.toInt())
            }
        } else {
            screen.addText(derivationPath, 12f, 0xFF9E9E9E.toInt())
        }
        screen.addButtons(listOf("Receive addresses", "Change addresses"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) {
            // Upstream: if we entered via an already-loaded seed's options
            // screen, clear the resume flow to avoid a redirect loop.
            val stack = controller.backStack
            if (stack.size > 1 && stack[stack.size - 2].ViewCls == SeedOptionsView::class.java) {
                controller.resumeMainFlow = null
                controller.addressExplorerData = null
            }
            return Destination(BackStackView::class.java)
        }
        return Destination(ToolsAddressExplorerAddressListView::class.java,
            viewArgs = mapOf(
                "isChange" to (index == 1),
                "startIndex" to 0,
                "selectedButtonIndex" to 0,
                "initialScroll" to 0
            ))
    }
}

/**
 * Upstream ToolsAddressExplorerAddressListView: 10 addresses per screen with
 * a Next button; tapping an address shows its QR.
 */
class ToolsAddressExplorerAddressListView(
    private val isChange: Boolean,
    private val startIndex: Int = 0,
    private val selectedButtonIndex: Int = 0,
    private val initialScroll: Int = 0
) : View() {

    companion object {
        const val ADDRS_PER_SCREEN = 10
    }

    private var pageAddresses: List<String> = emptyList()

    override fun run(activity: Activity): Destination? {
        val data = controller.addressExplorerData
            ?: throw Exception("Routing error: address_explorer_data hasn't been set")

        val storageKey = if (isChange) "change_addrs" else "receive_addrs"
        val cached = data[storageKey] as? MutableList<String>
        if (cached != null && cached.size >= startIndex + ADDRS_PER_SCREEN) {
            pageAddresses = cached.subList(startIndex, startIndex + ADDRS_PER_SCREEN).toList()
        } else {
            val xpub = data["xpub"] as? com.seedsigner.core.crypto.Bip32.HDKey
            val descriptor = data["wallet_descriptor"] as? com.seedsigner.core.descriptor.Descriptor
            val scriptType = data["script_type"] as? String ?: ""
            if (xpub == null && descriptor == null) {
                return Destination(ErrorView::class.java,
                    viewArgs = mapOf(
                        "title" to "Error",
                        "headline" to "Missing explorer source",
                        "text" to "Load a seed or a wallet descriptor first."
                    ))
            }
            if (xpub != null && scriptType == Settings.Const.CUSTOM_DERIVATION) {
                // Upstream raises the same error.
                return Destination(ErrorView::class.java,
                    viewArgs = mapOf(
                        "title" to "Error",
                        "headline" to "Custom Derivation address explorer not yet implemented",
                        "text" to ""
                    ))
            }
            val network = data["network"] as String
            val embitNetwork = Networks.fromName(mapNetworkToEmbit(network))
            val coreScript = if (scriptType.isNotEmpty()) AddrCodes.scriptToCore.getValue(scriptType) else ""
            val list = cached ?: mutableListOf<String>().also { data[storageKey] = it }
            pageAddresses = (startIndex until startIndex + ADDRS_PER_SCREEN).map { i ->
                val addr = if (descriptor != null) {
                    com.seedsigner.core.descriptor.getMultisigAddress(descriptor, i, isChange, embitNetwork)
                } else {
                    Address.singleSigAddress(xpub!!, coreScript, i, isChange, embitNetwork)
                }
                list.add(addr)
                addr
            }
        }

        val buttons = pageAddresses.map { it.takeLast(12) } + "Next »"
        val screen = Ui.screen(activity, if (isChange) "Change Addrs" else "Receive Addrs")
        screen.addText("Index $startIndex … ${startIndex + pageAddresses.size - 1}", 12f, 0xFF9E9E9E.toInt())
        screen.addButtons(buttons)
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return if (index == pageAddresses.size) {
            // NEXT
            Destination(ToolsAddressExplorerAddressListView::class.java,
                viewArgs = mapOf(
                    "isChange" to isChange,
                    "startIndex" to startIndex + ADDRS_PER_SCREEN,
                    "selectedButtonIndex" to 0,
                    "initialScroll" to 0
                ))
        } else {
            Destination(ToolsAddressExplorerAddressView::class.java,
                viewArgs = mapOf(
                    "index" to index + startIndex,
                    "address" to pageAddresses[index],
                    "isChange" to isChange,
                    "startIndex" to startIndex,
                    "parentInitialScroll" to 0
                ), skipCurrentView = true)
        }
    }
}

/** Upstream ToolsAddressExplorerAddressView: shows the address as a static QR. */
class ToolsAddressExplorerAddressView(
    private val index: Int,
    private val address: String,
    private val isChange: Boolean,
    private val startIndex: Int,
    private val parentInitialScroll: Int = 0
) : View() {

    override fun run(activity: Activity): Destination? {
        return Destination(QrDisplayView::class.java,
            viewArgs = mapOf(
                "parts" to listOf(address),
                "title" to "Address $index"
            ))
    }

    override fun onResult(ignored: Int): Destination {
        // Exiting the QR display always returns to the list.
        return Destination(ToolsAddressExplorerAddressListView::class.java,
            viewArgs = mapOf(
                "isChange" to isChange,
                "startIndex" to startIndex,
                "selectedButtonIndex" to index - startIndex,
                "initialScroll" to parentInitialScroll
            ), skipCurrentView = true)
    }
}
