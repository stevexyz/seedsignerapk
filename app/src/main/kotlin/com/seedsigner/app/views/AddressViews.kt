package com.seedsigner.app.views

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Controller
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
import com.seedsigner.app.models.Derivation
import com.seedsigner.app.models.Seed
import com.seedsigner.app.models.Settings
import com.seedsigner.core.crypto.Address
import com.seedsigner.core.crypto.AddressType
import com.seedsigner.core.crypto.Bip32
import com.seedsigner.core.crypto.Networks
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 1:1 port of the upstream address-verification flow (seed_views.py
 * AddressVerification*View + SeedAddressVerification*View + scan_views.py
 * ScanAddressView).
 *
 * Core name <-> upstream settings code mapping (both directions):
 *   p2pkh<->leg  p2sh-p2wpkh<->nes  p2wpkh<->nat  p2tr<->tr
 *   main<->M     test<->T           regtest<->R
 */
internal object AddrCodes {
    val coreToScript = mapOf("p2pkh" to "leg", "p2sh-p2wpkh" to "nes", "p2wpkh" to "nat", "p2tr" to "tr")
    val scriptToCore = coreToScript.entries.associate { (k, v) -> v to k }
    val coreToNet = mapOf("main" to Settings.Const.MAINNET, "test" to Settings.Const.TESTNET, "regtest" to Settings.Const.REGTEST)
    val netToCore = coreToNet.entries.associate { (k, v) -> v to k }

    /** Display name for an option value of a single/multi-select setting. */
    fun displayName(settings: Settings, attrName: String, value: String): String =
        settings.getEntry(attrName).selectionOptions.firstOrNull { it.first == value }?.second ?: value
}

/**
 * Upstream ScanAddressView: a ScanView that only accepts address QRs.
 */
class ScanAddressView : ScanView() {

    override fun onResult(index: Int): Destination {
        stopScanner()
        val ur = decodedUr ?: return Destination(BackStackView::class.java)
        val raw = String(ur.cbor, Charsets.UTF_8).trim()
        val triple = AddressType.detectWithAddress(raw)
        return if (triple == null) {
            Destination(ErrorView::class.java,
                viewArgs = mapOf(
                    "title" to "Error",
                    "headline" to "Expected an address QR",
                    "text" to "The scanned QR does not contain a Bitcoin address."
                ))
        } else {
            Destination(AddressVerificationStartView::class.java,
                viewArgs = mapOf(
                    "address" to triple.first,
                    "scriptType" to triple.second,
                    "network" to triple.third
                ), skipCurrentView = true)
        }
    }
}

/**
 * Upstream AddressVerificationStartView: decides single/multisig from the
 * address and routes to seed selection (single) or descriptor loading
 * (multisig).
 */
class AddressVerificationStartView(
    private val address: String,
    private val scriptType: String,
    private val network: String
) : View() {

    override fun run(activity: Activity): Destination? {
        val data = mutableMapOf<String, Any?>(
            "address" to address,
            "script_type" to AddrCodes.coreToScript.getValue(scriptType),
            "network" to AddrCodes.coreToNet.getValue(network)
        )
        controller.unverifiedAddress = data

        // No way to differentiate single sig from multisig.
        if (data["script_type"] == Settings.Const.NESTED_SEGWIT) {
            return Destination(AddressVerificationSigTypeView::class.java, skipCurrentView = true)
        }

        val sigType: String
        var destination: Destination = when (data["script_type"]) {
            // Legacy P2PKH and taproot are always singlesig; a short native
            // segwit address (mainnet/testnet 42-43 chars, regtest 44) is a
            // p2wpkh singlesig. A long native segwit address (>= 62) is a
            // p2wsh multisig.
            Settings.Const.NATIVE_SEGWIT -> if (address.length >= 62) {
                sigType = Settings.Const.MULTISIG
                if (controller.multisigWalletDescriptor != null) {
                    Destination(SeedAddressVerificationView::class.java, viewArgs = mapOf("fresh" to true), skipCurrentView = true)
                } else {
                    controller.resumeMainFlow = Controller.Flow.VERIFY_MULTISIG_ADDR
                    Destination(LoadMultisigWalletDescriptorView::class.java, skipCurrentView = true)
                }
            } else {
                sigType = Settings.Const.SINGLE_SIG
                Destination(SeedsMenuView::class.java, skipCurrentView = true)
            }
            else -> {
                sigType = Settings.Const.SINGLE_SIG
                Destination(SeedsMenuView::class.java, skipCurrentView = true)
            }
        }

        data["sig_type"] = sigType
        data["derivation_path"] = Derivation.standardDerivationPath(
            network = data["network"] as String,
            walletType = sigType,
            scriptType = data["script_type"] as String
        )
        return destination
    }
}

/** Upstream AddressVerificationSigTypeView. */
class AddressVerificationSigTypeView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Verify Address")
        screen.addText("Sig type can't be auto-detected from this address. Please specify:")
        screen.addButtons(listOf("Single Sig", "Multisig"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        val data = controller.unverifiedAddress
            ?: throw Exception("Routing error: unverified_address hasn't been set")
        if (index == Ui.RET_CODE_BACK) {
            controller.unverifiedAddress = null
            return Destination(BackStackView::class.java)
        }

        val sigType = if (index == 0) Settings.Const.SINGLE_SIG else Settings.Const.MULTISIG
        val destination = when (sigType) {
            Settings.Const.SINGLE_SIG -> Destination(SeedsMenuView::class.java)
            else -> if (controller.multisigWalletDescriptor != null) {
                Destination(SeedAddressVerificationView::class.java, viewArgs = mapOf("fresh" to true))
            } else {
                controller.resumeMainFlow = Controller.Flow.VERIFY_MULTISIG_ADDR
                Destination(LoadMultisigWalletDescriptorView::class.java)
            }
        }
        data["sig_type"] = sigType
        data["derivation_path"] = Derivation.standardDerivationPath(
            network = data["network"] as String,
            walletType = sigType,
            scriptType = data["script_type"] as String
        )
        return destination
    }
}

/**
 * Upstream SeedAddressVerificationView: brute-forces receive/change address
 * indices on a background thread until the scanned address matches.
 *
 * Port notes: upstream shares ThreadsafeCounter objects between the worker
 * thread and the display's ProgressThread; the touch port uses an
 * AtomicLong index + AtomicLong stop flag + a 100 ms UI poll. "Skip 10"
 * adds 10 to the shared index, exactly like upstream's increment(10).
 */
class SeedAddressVerificationView(
    val fresh: Boolean = true
) : View() {

    companion object {
        // Shared across view re-runs so "Skip 10" keeps its progress (the
        // upstream equivalent: ThreadsafeCounter objects shared with the
        // worker thread; here a companion-held AtomicLong + worker thread).
        val currentIndex = AtomicLong(0)
        val verifiedIndex = AtomicLong(-1)
        val verifiedIsChange = AtomicLong(-1)
        val stopFlag = AtomicBoolean(false)
        var worker: Thread? = null

        fun reset() {
            stopFlag.set(true)
            worker?.join(500)
            worker = null
            currentIndex.set(0)
            verifiedIndex.set(-1)
            verifiedIsChange.set(-1)
            stopFlag.set(false)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private var derivationPath = ""
    private var statusText: android.widget.TextView? = null

    override fun run(activity: Activity): Destination? {
        val data = controller.unverifiedAddress
            ?: throw Exception("Routing error: unverified_address hasn't been set")
        val isMultisig = data["sig_type"] == Settings.Const.MULTISIG
        val seed: Seed? = controller.addressVerifySeed
        if (!isMultisig) {
            if (seed == null) throw Exception("Can't validate a single sig addr without specifying a seed")
        }
        val descriptor = controller.multisigWalletDescriptor as? com.seedsigner.core.descriptor.Descriptor
        if (isMultisig && descriptor == null) {
            // Upstream requires the descriptor to have been loaded first.
            return Destination(ErrorView::class.java,
                viewArgs = mapOf(
                    "title" to "Error",
                    "headline" to "Missing descriptor",
                    "text" to "Load the multisig wallet descriptor first."
                ))
        }

        if (fresh) reset() else stopFlag.set(false)

        val address = data["address"] as String
        val scriptType = data["script_type"] as String
        val sigType = data["sig_type"] as String
        val network = data["network"] as String

        val seedDerivationOverride = seed?.derivationOverride(Settings.Const.SINGLE_SIG) ?: ""
        derivationPath = if (seedDerivationOverride.isNotEmpty()) seedDerivationOverride else data["derivation_path"] as String

        val scriptTypeDisplay = AddrCodes.displayName(settings, Settings.Const.SETTING__SCRIPT_TYPES, scriptType)
        val sigTypeDisplay = AddrCodes.displayName(settings, Settings.Const.SETTING__SIG_TYPES, sigType)
        val networkDisplay = AddrCodes.displayName(settings, Settings.Const.SETTING__NETWORK, network)

        val embitNetwork = Networks.fromName(AddrCodes.netToCore.getValue(network))

        // The brute-force worker (upstream BruteForceAddressVerificationThread).
        val xpub = if (descriptor == null) {
            Bip32.derive(
                Bip32.HDKey.fromSeed(seed!!.seedBytes, version = embitNetwork.versions["xprv"]!!),
                derivationPath
            )
        } else {
            null
        }
        val coreScript = AddrCodes.scriptToCore.getValue(scriptType)
        val target = address
        val xpubRef = xpub
        val descriptorRef = descriptor

        worker = thread(start = true) {
            while (!stopFlag.get()) {
                val i = currentIndex.get()
                val (receive, change) = if (descriptorRef != null) {
                    com.seedsigner.core.descriptor.getMultisigAddress(descriptorRef, i.toInt(), false, embitNetwork) to
                        com.seedsigner.core.descriptor.getMultisigAddress(descriptorRef, i.toInt(), true, embitNetwork)
                } else {
                    Address.singleSigAddress(xpubRef!!, coreScript, i.toInt(), false, embitNetwork) to
                        Address.singleSigAddress(xpubRef, coreScript, i.toInt(), true, embitNetwork)
                }
                if (receive == target) {
                    verifiedIndex.set(i); verifiedIsChange.set(0); stopFlag.set(true); break
                }
                if (change == target) {
                    verifiedIndex.set(i); verifiedIsChange.set(1); stopFlag.set(true); break
                }
                currentIndex.incrementAndGet()
            }
        }

        val screen = Ui.screen(activity, "Verifying Address")
        screen.addText(address, 14f)
        screen.addText(derivationPath, 12f, 0xFF9E9E9E.toInt())
        screen.addText("$scriptTypeDisplay  ·  $sigTypeDisplay  ·  $networkDisplay", 12f, 0xFF9E9E9E.toInt())
        statusText = screen.addText("Deriving index ${currentIndex.get()}…", 16f)
        screen.addButtons(listOf("Skip 10", "Cancel"))
        controller.showScreen(screen)

        // Poll the worker's progress onto the UI (upstream's ProgressThread).
        val poll = object : Runnable {
            override fun run() {
                if (verifiedIndex.get() >= 0) return
                statusText?.text = "Deriving index ${currentIndex.get()}…"
                handler.postDelayed(this, 100)
            }
        }
        poller = poll
        handler.postDelayed(poll, 100)
        return null
    }

    private var poller: Runnable? = null

    override fun onResult(index: Int): Destination {
        // A verification may have completed while this screen was up.
        if (verifiedIndex.get() >= 0) {
            cleanup()
            data()!!.put("verified_index", verifiedIndex.get())
            data()!!.put("verified_index_is_change", verifiedIsChange.get() == 1L)
            return Destination(SeedAddressVerificationSuccessView::class.java, clearHistory = true)
        }

        if (index == Ui.RET_CODE_BACK || index == 1) {
            cleanup()
            reset()
            controller.unverifiedAddress = null
            controller.addressVerifySeed = null
            return Destination(MainMenuView::class.java, clearHistory = true)
        }
        if (index == 0) {
            // "Skip 10": jump the shared index forward and re-run the screen
            // (upstream re-runs its Screen per tap; user can't tell).
            cleanup()
            currentIndex.addAndGet(10)
            return Destination(SeedAddressVerificationView::class.java,
                viewArgs = mapOf("fresh" to false),
                skipCurrentView = true)
        }
        cleanup()
        reset()
        controller.unverifiedAddress = null
        controller.addressVerifySeed = null
        return Destination(MainMenuView::class.java, clearHistory = true)
    }

    private fun data(): MutableMap<String, Any?>? = controller.unverifiedAddress

    private fun cleanup() {
        poller?.let { handler.removeCallbacks(it) }
        poller = null
        statusText = null
        stopFlag.set(true)
        worker?.join(500)
        worker = null
    }
}

/** Upstream SeedAddressVerificationSuccessView. */
class SeedAddressVerificationSuccessView : View() {

    override fun run(activity: Activity): Destination? {
        val data = controller.unverifiedAddress
            ?: throw Exception("Routing error: unverified_address hasn't been set")
        val verifiedIndex = data["verified_index"] as Long
        val isChange = data["verified_index_is_change"] as Boolean

        val screen = Ui.screen(activity, "Address Verified")
        screen.addText(data["address"] as String, 16f)
        screen.addText("Index $verifiedIndex — ${if (isChange) "change" else "receive"} address", 16f)
        screen.addButtons(listOf("Done"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        controller.unverifiedAddress = null
        return Destination(MainMenuView::class.java, clearHistory = true)
    }
}
