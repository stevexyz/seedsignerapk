package com.seedsigner.app.views

import android.app.Activity
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
import com.seedsigner.app.models.Settings
import com.seedsigner.app.models.mapNetworkToEmbit
import com.seedsigner.app.qr.QrDisplay
import com.seedsigner.core.crypto.Bip32
import com.seedsigner.core.crypto.Networks
import com.seedsigner.core.psbt.PsbtInputOwnershipClaimError
import com.seedsigner.core.psbt.PsbtMixedDerivationPathTypesError
import com.seedsigner.core.psbt.PsbtOutputOwnershipClaimError
import com.seedsigner.core.psbt.PsbtOutputOwnershipContradictionError
import com.seedsigner.core.psbt.PsbtParser
import com.seedsigner.core.psbt.PsbtSeedCannotSignError
import com.seedsigner.core.psbt.PsbtSurplusDerivationPathsError

/**
 * 1:1 port of the upstream PSBT signing flow (psbt_views.py).
 *
 * The Controller holds the working state (psbt, psbtSeed, psbtParser),
 * exactly like upstream. viewArgs key names mirror the upstream
 * constructor parameters (Controller.instantiateView maps them by name):
 *   addressNum: Int            (PSBTAddressDetailsView)
 *   changeAddressNum: Int      (PSBTChangeDetailsView)
 *   isChange: Boolean          (PSBTAddressVerificationFailedView)
 *   warningThresholdPercent: Int (PSBTHighFeeWarningView)
 */

/** satoshis -> "X.XXXXXXXX BTC" (upstream shows BTC; sats in parentheses). */
private fun fmtAmount(sats: Long): String {
    val btc = sats / 100_000_000.0
    return String.format("%.8f BTC (%d sat)", btc, sats)
}

/** Upstream PSBTSelectSeedView. */
class PSBTSelectSeedView : View() {

    private var buttonCount = 0
    private var seedCount = 0

    override fun run(activity: Activity): Destination? {
        val psbt = controller.psbt ?: throw Exception("No transaction currently loaded")
        val networkStr = settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        val network = Networks.fromName(mapNetworkToEmbit(networkStr))

        // Skip the seed prompt if a seed was previously selected and matches.
        controller.psbtSeed?.let { seed ->
            if (PsbtParser.hasMatchingInputFingerprint(psbt, seed.seedBytes, network)) {
                return Destination(PSBTOverviewView::class.java)
            }
        }

        val seeds = controller.storage.seeds
        seedCount = seeds.size
        val buttons = seeds.map { seed ->
            val fp = seed.getFingerprint(networkStr)
            // A seed that can't sign the current PSBT is marked with "?"
            if (PsbtParser.hasMatchingInputFingerprint(psbt, seed.seedBytes, network)) fp else "$fp (?)"
        } + listOf("Scan a seed", "Enter 12-word seed", "Enter 24-word seed")
        buttonCount = buttons.size

        val screen = Ui.screen(activity, "Select Signer")
        screen.addButtons(buttons)
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val seeds = controller.storage.seeds

        if (index < seedCount) {
            controller.psbtSeed = seeds[index]
            return Destination(PSBTOverviewView::class.java)
        }

        // The remaining flows are a sub-flow; resume the PSBT flow once the
        // seed is loaded (SeedOptionsView routes back when it matches).
        controller.resumeMainFlow = com.seedsigner.app.Controller.Flow.PSBT

        return when (index - seedCount) {
            0 -> Destination(ScanView::class.java)
            1 -> {
                controller.storage.initPendingMnemonic(numWords = 12)
                Destination(SeedMnemonicEntryView::class.java)
            }
            else -> {
                controller.storage.initPendingMnemonic(numWords = 24)
                Destination(SeedMnemonicEntryView::class.java)
            }
        }
    }
}

/**
 * Upstream PSBTOverviewView. The constructor does the (slow) parse; upstream
 * runs it behind a loading screen thread — on a modern phone the parse is
 * fast enough to run inline on the UI thread.
 */
class PSBTOverviewView : View() {

    override fun run(activity: Activity): Destination? {
        val psbt = controller.psbt ?: throw Exception("No transaction currently loaded")
        val seed = controller.psbtSeed ?: throw Exception("No seed currently selected")

        if (controller.psbtParser == null ||
            !controller.psbtParser!!.seedBytes.contentEquals(seed.seedBytes)) {
            val networkStr = settings.getValue<String>(Settings.Const.SETTING__NETWORK)
            val network = Networks.fromName(mapNetworkToEmbit(networkStr))
            try {
                controller.psbtParser = PsbtParser(psbt, seed.seedBytes, network)
            }
            // Almost every exception case sets clear_history so BACK can't
            // return into the rejected transaction.
            catch (e: PsbtInputOwnershipClaimError) {
                return Destination(PSBTInputOwnershipClaimFailedView::class.java, clearHistory = true)
            }
            catch (e: PsbtOutputOwnershipClaimError) {
                return Destination(PSBTOutputOwnershipClaimFailedView::class.java, clearHistory = true)
            }
            catch (e: PsbtSurplusDerivationPathsError) {
                return Destination(PSBTSurplusDerivationPathsView::class.java, clearHistory = true)
            }
            catch (e: PsbtMixedDerivationPathTypesError) {
                return Destination(PSBTMixedDerivationPathTypesView::class.java, clearHistory = true)
            }
            catch (e: PsbtOutputOwnershipContradictionError) {
                return Destination(PSBTOutputOwnershipContradictionView::class.java, clearHistory = true)
            }
            catch (e: PsbtSeedCannotSignError) {
                // Not a suspicious psbt, just the wrong seed for it. Send the
                // user back to pick another rather than clearing the flow.
                controller.psbtParser = null
                controller.psbtSeed = null
                return Destination(PSBTSeedCannotSignView::class.java)
            }
        }

        val p = controller.psbtParser!!
        var numChangeOutputs = 0
        var numSelfTransferOutputs = 0
        for (changeOutput in p.changeData) {
            if (PsbtParser.isChangeBranch(changeOutput.verifiedDerivationPath)) numChangeOutputs++
            else numSelfTransferOutputs++
        }

        val screen = Ui.screen(activity, "PSBT Overview")
        screen.addText("Spend: ${fmtAmount(p.spendAmount)}", 18f)
        screen.addText("Change: ${fmtAmount(p.changeAmount)}", 18f)
        screen.addText("Fee: ${fmtAmount(p.feeAmount)}", 18f)
        screen.addText("Inputs: ${p.numInputs}", 14f)
        screen.addText("Recipients: ${p.numDestinations}", 14f)
        if (numSelfTransferOutputs > 0) screen.addText("Self-transfers: $numSelfTransferOutputs", 14f)
        if (numChangeOutputs > 0) screen.addText("Change outputs: $numChangeOutputs", 14f)
        p.destinationAddresses.forEach { addr -> screen.addText(addr, 12f, 0xFF9E9E9E.toInt()) }
        if (p.opReturnData != null) screen.addText("Contains OP_RETURN data", 14f, 0xFFFFB74D.toInt())
        if (p.isHighFee) screen.addText("High fee!", 16f, 0xFFFFB74D.toInt())
        screen.addButtons(listOf("Continue"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) {
            controller.psbtSeed = null
            return Destination(BackStackView::class.java)
        }
        val p = controller.psbtParser ?: return Destination(MainMenuView::class.java)

        // p2sh (legacy multisig) and p2pkh have no policy: skip the change
        // warning and the math view.
        return when {
            p.policy == null -> Destination(PSBTUnsupportedScriptTypeWarningView::class.java)
            p.isHighFee -> Destination(PSBTHighFeeWarningView::class.java,
                viewArgs = mapOf("warningThresholdPercent" to PsbtParser.HIGH_FEES_WARNING_THRESHOLD))
            p.changeAmount == 0L -> Destination(PSBTNoChangeWarningView::class.java)
            else -> Destination(PSBTMathView::class.java)
        }
    }
}

/** Upstream PSBTUnsupportedScriptTypeWarningView. */
class PSBTUnsupportedScriptTypeWarningView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Unsupported Script Type!")
        screen.addText("Transaction has unsupported input script type, please verify your change addresses.", 16f)
        screen.addButtons(listOf("Continue"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        // Only one exit point; skip PSBTMathView.
        return Destination(PSBTAddressDetailsView::class.java,
            viewArgs = mapOf("addressNum" to 0), skipCurrentView = true)
    }
}

/** Upstream PSBTNoChangeWarningView. */
class PSBTNoChangeWarningView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Full Spend!")
        screen.addText("This transaction spends its entire input value. No change is coming back to your wallet.", 16f)
        screen.addButtons(listOf("Continue"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        // Only one exit point.
        return Destination(PSBTMathView::class.java, skipCurrentView = true)
    }
}

/** Upstream PSBTHighFeeWarningView. */
class PSBTHighFeeWarningView : View() {

    private var warningThresholdPercent: Int = 25

    override fun run(activity: Activity): Destination? {
        warningThresholdPercent = viewArgs["warningThresholdPercent"] as Int
        val screen = Ui.screen(activity, "High Fee!")
        screen.addText("This transaction has a fee higher than $warningThresholdPercent% of the total output value (excluding change).", 16f, 0xFFFFB74D.toInt())
        screen.addButtons(listOf("Continue"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        // A PSBT may have a high fee AND no change.
        return if (controller.psbtParser?.changeAmount == 0L) {
            Destination(PSBTNoChangeWarningView::class.java, skipCurrentView = true)
        } else {
            Destination(PSBTMathView::class.java, skipCurrentView = true)
        }
    }
}

/**
 * Upstream PSBTMathView:
 *   + total input value
 *   - recipients' value
 *   - fees
 *   -------------------
 *   + change value
 */
class PSBTMathView : View() {

    override fun run(activity: Activity): Destination? {
        val p = controller.psbtParser ?: return Destination(MainMenuView::class.java)

        val screen = Ui.screen(activity, "Transaction Math")
        screen.addText("+ ${fmtAmount(p.inputAmount)}  (${p.numInputs} input${if (p.numInputs == 1) "" else "s"})", 16f)
        screen.addText("- ${fmtAmount(p.spendAmount)}  (${p.numDestinations} recipient${if (p.numDestinations == 1) "" else "s"})", 16f)
        screen.addText("- ${fmtAmount(p.feeAmount)}  (fee${if (p.isHighFee) " - HIGH!" else ""})", 16f)
        screen.addText("---------", 16f, 0xFF9E9E9E.toInt())
        screen.addText("+ ${fmtAmount(p.changeAmount)}  (back to you)", 18f, 0xFF81C784.toInt())
        screen.addButtons(listOf("Continue"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val p = controller.psbtParser!!
        return if (p.destinationAddresses.size > 0) {
            Destination(PSBTAddressDetailsView::class.java, viewArgs = mapOf("addressNum" to 0))
        } else {
            // This is a self-transfer.
            Destination(PSBTChangeDetailsView::class.java, viewArgs = mapOf("changeAddressNum" to 0))
        }
    }
}

/** Upstream PSBTAddressDetailsView. */
class PSBTAddressDetailsView : View() {

    private var addressNum: Int = 0

    override fun run(activity: Activity): Destination? {
        addressNum = viewArgs["addressNum"] as Int
        val p = controller.psbtParser ?: throw Exception("Routing error")

        var title = "Will Send"
        if (p.numDestinations > 1) title += " (#${addressNum + 1})"

        val next = if (addressNum < p.numDestinations - 1) "Next recipient" else "Next"

        val screen = Ui.screen(activity, title)
        screen.addText(p.destinationAddresses[addressNum], 18f)
        screen.addText(fmtAmount(p.destinationAmounts[addressNum]), 22f, 0xFF81C784.toInt())
        screen.addButtons(listOf(next))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val p = controller.psbtParser!!

        return when {
            addressNum < p.destinationAddresses.size - 1 ->
                Destination(PSBTAddressDetailsView::class.java, viewArgs = mapOf("addressNum" to addressNum + 1))
            p.changeAmount > 0L ->
                Destination(PSBTChangeDetailsView::class.java, viewArgs = mapOf("changeAddressNum" to 0))
            p.opReturnData != null ->
                Destination(PSBTOpReturnView::class.java)
            else ->
                // No change output to verify. Move on to sign the PSBT.
                Destination(PSBTFinalizeView::class.java)
        }
    }
}

/**
 * Upstream PSBTChangeDetailsView. "change" covers self-transfers too; the
 * derivation branch decides the title.
 */
class PSBTChangeDetailsView : View() {

    private var changeAddressNum: Int = 0

    override fun run(activity: Activity): Destination? {
        changeAddressNum = viewArgs["changeAddressNum"] as Int
        val p = controller.psbtParser ?: return Destination(MainMenuView::class.java)

        val changeData = p.getChangeData(changeAddressNum)
            ?: return Destination(MainMenuView::class.java)
        val verifiedDerivationPath = changeData.verifiedDerivationPath
        val isChangeDerivationPath = PsbtParser.isChangeBranch(verifiedDerivationPath)

        val title = if (isChangeDerivationPath) "Your Change" else "Self-Transfer"

        val networkStr = settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        val seedFingerprint = controller.psbtSeed?.getFingerprint(networkStr) ?: ""
        val derivationPath = Bip32.pathToStr(
            verifiedDerivationPath,
            controller.psbtParser!!.root!!.fingerprintOfChild().let {
                // pathToStr expects the 4-byte big-endian fingerprint
                intToBe4Bytes(it)
            }
        )

        val isChangeAddrVerified = !p.isMultisig || controller.multisigWalletDescriptor != null

        val screen = Ui.screen(activity, title)
        screen.addText(changeData.address, 18f)
        screen.addText(fmtAmount(changeData.amount), 22f, 0xFF81C784.toInt())
        screen.addText("Fingerprint: $seedFingerprint", 13f, 0xFF9E9E9E.toInt())
        screen.addText(derivationPath, 13f, 0xFF9E9E9E.toInt())
        if (isChangeAddrVerified) screen.addText("Address verified from your seed", 14f, 0xFF81C784.toInt())

        val buttons = if (p.isMultisig && controller.multisigWalletDescriptor == null) {
            listOf("Verify multisig addr", "Skip verification")
        } else {
            listOf("Next")
        }
        screen.addButtons(buttons)
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val p = controller.psbtParser!!
        val isMultisigPending = p.isMultisig && controller.multisigWalletDescriptor == null

        return when {
            // "Next" (index 0 when no multisig prompt) / "Skip verification" (index 1)
            !isMultisigPending || index == 1 -> {
                if (changeAddressNum < p.numChangeOutputs - 1) {
                    Destination(PSBTChangeDetailsView::class.java, viewArgs = mapOf("changeAddressNum" to changeAddressNum + 1))
                } else if (p.opReturnData != null) {
                    Destination(PSBTOpReturnView::class.java)
                } else {
                    // No more change to verify. Move on to sign the PSBT.
                    Destination(PSBTFinalizeView::class.java)
                }
            }
            // "Verify multisig addr" (v0.3 feature: descriptor loading)
            else -> Destination(LoadMultisigWalletDescriptorView::class.java)
        }
    }
}

/** 4-byte big-endian encoding of a BIP-32 fingerprint int. */
private fun intToBe4Bytes(i: Int): ByteArray = byteArrayOf(
    ((i shr 24) and 0xFF).toByte(),
    ((i shr 16) and 0xFF).toByte(),
    ((i shr 8) and 0xFF).toByte(),
    (i and 0xFF).toByte()
)

/**
 * Upstream PSBTSeedCannotSignView: the seed can't sign any input. Not an
 * attack — route back to seed selection without discarding the PSBT.
 */
class PSBTSeedCannotSignView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Seed Can't Sign", showBack = false)
        screen.addText("None of the inputs in this transaction are controlled by this seed.", 16f)
        screen.addButtons(listOf("Select a different seed"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination =
        Destination(PSBTSelectSeedView::class.java, clearHistory = true)
}

/** Upstream PSBTOutputOwnershipClaimFailedView: we view this as an attack. */
class PSBTOutputOwnershipClaimFailedView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Suspicious Transaction", showBack = false)
        screen.addText("Likely an Attack!", 20f, 0xFFFF5252.toInt())
        screen.addText("The transaction's change/self-transfer output is not going back to your wallet.", 16f)
        screen.addButtons(listOf("Discard transaction"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination =
        Destination(MainMenuView::class.java, clearHistory = true)
}

/** Upstream PSBTInputOwnershipClaimFailedView: renders the PSBT unsignable; warning level. */
class PSBTInputOwnershipClaimFailedView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Transaction Problem", showBack = false)
        screen.addText("This transaction incorrectly claims that one of its inputs belongs to this seed.", 16f)
        screen.addButtons(listOf("Discard transaction"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination =
        Destination(MainMenuView::class.java, clearHistory = true)
}

/** Upstream PSBTSurplusDerivationPathsView: too many keys claimed for one output. */
class PSBTSurplusDerivationPathsView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Transaction Problem", showBack = false)
        screen.addText("This transaction claims too many keys for one of its outputs.", 16f)
        screen.addButtons(listOf("Discard transaction"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination =
        Destination(MainMenuView::class.java, clearHistory = true)
}

/** Upstream PSBTMixedDerivationPathTypesView: taproot + non-taproot claims at once. */
class PSBTMixedDerivationPathTypesView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Transaction Problem", showBack = false)
        screen.addText("This transaction claims taproot and non-taproot keys for the same script.", 16f)
        screen.addButtons(listOf("Discard transaction"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination =
        Destination(MainMenuView::class.java, clearHistory = true)
}

/** Upstream PSBTOutputOwnershipContradictionView: we view this as an attack. */
class PSBTOutputOwnershipContradictionView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Suspicious Transaction", showBack = false)
        screen.addText("Likely an Attack!", 20f, 0xFFFF5252.toInt())
        screen.addText("This transaction misrepresents where one of its outputs pays.", 16f)
        screen.addButtons(listOf("Discard transaction"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination =
        Destination(MainMenuView::class.java, clearHistory = true)
}

/**
 * Upstream PSBTAddressVerificationFailedView: multisig change/self-transfer
 * could not be verified against the supplied descriptor.
 */
class PSBTAddressVerificationFailedView : View() {

    private var isChange: Boolean = true

    override fun run(activity: Activity): Destination? {
        isChange = viewArgs["isChange"] as Boolean
        val what = if (isChange) "change" else "self-transfer"
        val screen = Ui.screen(activity, "Suspicious Transaction", showBack = false)
        screen.addText("Address Verification Failed", 20f, 0xFFFF5252.toInt())
        screen.addText("Transaction's $what address could not be verified from wallet descriptor.", 16f)
        screen.addButtons(listOf("Discard transaction"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination =
        Destination(MainMenuView::class.java, clearHistory = true)
}

/** Upstream PSBTOpReturnView: shows the OP_RETURN payload. */
class PSBTOpReturnView : View() {

    override fun run(activity: Activity): Destination? {
        val p = controller.psbtParser ?: throw Exception("Routing error")
        val data = p.opReturnData!!
        val screen = Ui.screen(activity, "OP_RETURN")
        screen.addText(data.joinToString("") { "%02x".format(it) }, 14f)
        screen.addButtons(listOf("Next"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return Destination(PSBTFinalizeView::class.java)
    }
}

/** Upstream PSBTFinalizeView: the sign button. */
class PSBTFinalizeView : View() {

    override fun run(activity: Activity): Destination? {
        val p = controller.psbtParser ?: return Destination(MainMenuView::class.java)
        val psbt = controller.psbt!!

        val screen = Ui.screen(activity, "Sign Transaction?")
        screen.addText("Spend: ${fmtAmount(p.spendAmount)}", 16f)
        screen.addText("Fee: ${fmtAmount(p.feeAmount)}", 16f)
        screen.addButtons(listOf("Approve transaction"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)

        val p = controller.psbtParser!!
        val psbt = controller.psbt!!

        // Sign the PSBT
        val sigCnt = PsbtParser.sigCount(psbt)
        psbt.signWith(p.root!!)
        val trimmedPsbt = PsbtParser.trim(psbt)

        return if (sigCnt == PsbtParser.sigCount(trimmedPsbt)) {
            // Signing failed / didn't do anything.
            Destination(PSBTSigningErrorView::class.java)
        } else {
            controller.psbt = trimmedPsbt
            Destination(PSBTSignedQRDisplayView::class.java)
        }
    }
}

/**
 * Upstream PSBTSignedQRDisplayView: show the signed PSBT as a UR2 fountain
 * sequence, then return to the main menu (which wipes the flow state).
 */
class PSBTSignedQRDisplayView : View() {

    override fun run(activity: Activity): Destination? {
        val psbt = controller.psbt!!
        val density = settings.getValue<String>(Settings.Const.SETTING__QR_DENSITY)
        val parts = QrDisplay.ur2Parts("crypto-psbt", psbt.serialize(), density)
        return Destination(QrDisplayView::class.java,
            viewArgs = mapOf(
                "parts" to parts,
                "title" to "Signed PSBT",
                "returnToMain" to true
            ),
            clearHistory = true)
    }
}

/** Upstream PSBTSigningErrorView: signing added no valid signature. */
class PSBTSigningErrorView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Transaction Error")
        screen.addText("Signing Failed", 20f, 0xFFFFB74D.toInt())
        screen.addText("Signing with this seed did not add a valid signature.", 16f)
        screen.addButtons(listOf("Select different seed"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        // Clear the seed selected for PSBT signing; it did not add a valid signature.
        controller.psbtSeed = null
        return Destination(PSBTSelectSeedView::class.java, clearHistory = true)
    }
}

/**
 * Upstream LoadMultisigWalletDescriptorView (seed_views.py). v0.1 scope:
 * descriptor scanning/verification is a v0.3 feature (see feature_roadmap.md
 * "Full PSBT change-address verification"), so the Scan button reports that
 * instead of faking a flow. Cancel routes back into the PSBT flow, exactly
 * like upstream.
 */
class LoadMultisigWalletDescriptorView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Multisig Descriptor", showBack = false)
        screen.addText("Multisig descriptor verification arrives in v0.3.", 16f)
        screen.addButtons(listOf("Back"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination =
        Destination(BackStackView::class.java)
}
