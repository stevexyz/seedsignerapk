package com.seedsigner.app.views

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Controller
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
import com.seedsigner.app.models.Seed
import com.seedsigner.app.models.mapNetworkToEmbit
import com.seedsigner.app.models.Settings

/**
 * 1:1 port of upstream SeedsMenuView (seed_views.py): lists in-memory seeds
 * by fingerprint with a "Load a seed" button; empty list jumps straight to
 * LoadSeedView.
 */
class SeedsMenuView : View() {

    override fun run(activity: Activity): Destination? {
        val seeds = controller.storage.seeds
        if (seeds.isEmpty()) {
            return Destination(LoadSeedView::class.java, clearHistory = true)
        }
        val network = controller.settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        val labels = seeds.map { it.getFingerprint(network) } + "Load a seed"
        val screen = Ui.screen(activity, "In-Memory Seeds")
        screen.addButtons(labels)
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        val seeds = controller.storage.seeds
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        // A seed picked for single-sig address verification goes straight to
        // the brute-force verification (upstream SeedSelectSeedView
        // flow=VERIFY_SINGLESIG_ADDR).
        if (index < seeds.size && controller.resumeMainFlow == Controller.Flow.VERIFY_SINGLESIG_ADDR) {
            controller.resumeMainFlow = null
            controller.addressVerifySeed = seeds[index]
            return Destination(SeedAddressVerificationView::class.java,
                viewArgs = mapOf("fresh" to true), skipCurrentView = true)
        }
        return if (index < seeds.size) {
            Destination(SeedOptionsView::class.java,
                viewArgs = mapOf("seedIndex" to index))
        } else {
            Destination(LoadSeedView::class.java)
        }
    }
}

/**
 * 1:1 port of upstream LoadSeedView.
 * "Scan a SeedQR" lands with the camera chunk; 12/24-word entry with the
 * load-seed chunk; Electrum only when the setting is enabled.
 */
class LoadSeedView : View() {

    private val c = Settings.Const

    override fun run(activity: Activity): Destination? {
        val buttons = mutableListOf("Scan a SeedQR", "Enter 12-word seed", "Enter 24-word seed")
        if (controller.settings.getValue<String>(c.SETTING__ELECTRUM_SEEDS) == c.OPTION__ENABLED) {
            buttons.add("Enter Electrum seed")
        }
        buttons.add("Create a seed")

        val screen = Ui.screen(activity, "Load a Seed")
        screen.addButtons(buttons)
        screen.addText("")
        controller.showScreen(screen)

        loadButtons = buttons
        return null
    }

    private var loadButtons: List<String> = emptyList()

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return when (loadButtons[index]) {
            "Scan a SeedQR" -> Destination(ScanView::class.java)
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
            "Enter Electrum seed" ->
                Destination(NotYetImplementedView::class.java, viewArgs = mapOf("feature" to "Electrum seed"))
            "Create a seed" -> Destination(ToolsMenuView::class.java)
            else -> Destination(BackStackView::class.java)
        }
    }
}

/**
 * Seed options (upstream SeedOptionsView): export xpubs / child seeds /
 * remove. Lands with the xpub-export chunk; remove works now.
 */
class SeedOptionsView : View() {

    override fun run(activity: Activity): Destination? {
        val index = viewArgs["seedIndex"] as Int
        val seed = controller.storage.seeds[index]
        val network = controller.settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        val fp = seed.getFingerprint(network)

        // A seed picked for message signing resumes that flow directly
        // (upstream SeedOptionsView.run).
        if (controller.resumeMainFlow == Controller.Flow.SIGN_MESSAGE) {
            controller.signMessageData?.set("seed", seed)
            return Destination(SeedSignMessageConfirmMessageView::class.java,
                viewArgs = mapOf("pageNum" to 0), skipCurrentView = true)
        }

        // A freshly loaded seed resumes the address explorer (upstream keeps
        // the resume flow set; it's cleared later at the explorer's back).
        if (controller.resumeMainFlow == Controller.Flow.ADDRESS_EXPLORER) {
            return Destination(SeedExportXpubScriptTypeView::class.java,
                viewArgs = mapOf("seedIndex" to index, "sigType" to Settings.Const.SINGLE_SIG),
                skipCurrentView = true)
        }

        // A freshly loaded seed that matches a pending PSBT resumes the
        // signing flow directly (upstream SeedOptionsView.run).
        controller.psbt?.let { psbt ->
            val net = com.seedsigner.core.crypto.Networks.fromName(mapNetworkToEmbit(network))
            if (com.seedsigner.core.psbt.PsbtParser.hasMatchingInputFingerprint(psbt, seed.seedBytes, net)) {
                if (controller.resumeMainFlow == com.seedsigner.app.Controller.Flow.PSBT) {
                    controller.resumeMainFlow = null
                    controller.psbtSeed = seed
                    return Destination(PSBTOverviewView::class.java, skipCurrentView = true)
                }
            }
        }

        val buttons = mutableListOf("Export xpubs", "Address explorer", "BIP-85 child seeds")
        if (settings.getValue<String>(Settings.Const.SETTING__MESSAGE_SIGNING) == Settings.Const.OPTION__ENABLED) {
            buttons.add("Sign message")
        }
        buttons.add("Remove seed")
        val screen = Ui.screen(activity, "Seed $fp")
        screen.addButtons(buttons)
        controller.showScreen(screen)
        seedIndex = index
        return null
    }

    private var seedIndex: Int = 0
    private var messageButtonIndex = -1

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val messageIdx = if (settings.getValue<String>(Settings.Const.SETTING__MESSAGE_SIGNING) == Settings.Const.OPTION__ENABLED) 3 else -1
        return when (index) {
            0 -> Destination(SeedExportXpubSigTypeView::class.java, viewArgs = mapOf("seedIndex" to seedIndex))
            1 -> {
                // Upstream EXPLORER: keep the resume flow set for the
                // newly-loaded-seed path through the xpub script-type view.
                controller.resumeMainFlow = Controller.Flow.ADDRESS_EXPLORER
                Destination(SeedExportXpubScriptTypeView::class.java,
                    viewArgs = mapOf("seedIndex" to seedIndex, "sigType" to Settings.Const.SINGLE_SIG))
            }
            2 -> Destination(SeedBIP85SelectNumWordsView::class.java, viewArgs = mapOf("seedIndex" to seedIndex))
            messageIdx -> {
                // Upstream: set the signing seed and scan a message QR.
                controller.signMessageData = mutableMapOf<String, Any?>("seed" to controller.storage.seeds[seedIndex])
                controller.resumeMainFlow = Controller.Flow.SIGN_MESSAGE
                Destination(ScanView::class.java)
            }
            else -> {
                if (index == messageIdx + 1) {
                    Destination(SeedDiscardView::class.java, viewArgs = mapOf("seedIndex" to seedIndex))
                } else {
                    Destination(SeedsMenuView::class.java)
                }
            }
        }
    }
}

/**
 * 1:1 port of upstream SeedMnemonicEntryView (seed_views.py) and its
 * SeedMnemonicEntryScreen keyboard semantics (gui/screens/seed_screens.py):
 *
 * - a-z keyboard (5 rows of 6 + backspace); tapping a letter "locks it in"
 *   (the Pi's KEY_PRESS equivalent); letters that lead to no word are inert
 * - candidate list = wordlist words starting with the current prefix
 * - tapping a candidate returns that word to the View
 * - back arrow = word-level back (first word cancels the whole entry)
 */
class SeedMnemonicEntryView(
    private val curWordIndex: Int = 0,
    private val isCalcFinalWord: Boolean = false
) : View() {

    private var letters: String = ""
    private var selectedWord: String? = null
    private var display: TextView? = null
    private var matchesList: LinearLayout? = null

    private val wordlist: List<String> get() =
        Seed.getWordlist(controller.settings.getValue<String>(Settings.Const.SETTING__WORDLIST_LANGUAGE))

    override fun run(activity: Activity): Destination? {
        letters = controller.storage.getPendingMnemonicWord(curWordIndex) ?: ""
        val screen = Ui.screen(activity, "Seed Word #" + (curWordIndex + 1))

        // live entry display
        val entry = TextView(activity)
        entry.text = letters.ifEmpty { "a" }
        entry.setTextColor(0xFFFFFFFF.toInt())
        entry.textSize = 34f
        entry.gravity = Gravity.CENTER
        screen.body.addView(entry, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        display = entry

        // candidate words (scrollable)
        val scroll = ScrollView(activity)
        val list = LinearLayout(activity)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list)
        screen.body.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        matchesList = list

        // keyboard: 5 rows of 6 letters + backspace filling the last row
        val density = activity.resources.displayMetrics.density
        val keyH = (density * 44).toInt()
        val rows = listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz\u232B")
        rows.forEach { row ->
            val rowLayout = LinearLayout(activity)
            rowLayout.orientation = LinearLayout.HORIZONTAL
            row.forEach { ch ->
                val b = Button(activity)
                b.text = ch.toString()
                b.isAllCaps = false
                b.setTextColor(0xFFFFFFFF.toInt())
                b.setBackgroundColor(0xFF2A3138.toInt())
                b.textSize = 20f
                b.setPadding(0, 0, 0, 0)
                b.setOnClickListener {
                    if (ch == '\u232B') onBackspace() else onLetter(ch)
                }
                rowLayout.addView(b, LinearLayout.LayoutParams(0, keyH, 1f))
            }
            screen.body.addView(rowLayout)
        }

        refresh()
        controller.showScreen(screen)
        return null
    }

    private fun refresh() {
        display?.text = letters.ifEmpty { "a" }
        val list = matchesList ?: return
        list.removeAllViews()
        if (letters.length < 2) return
        val candidates = wordlist.filter { it.startsWith(letters) }
        candidates.forEach { w ->
            val b = Button(list.context)
            b.text = w
            b.isAllCaps = false
            b.setTextColor(0xFFFFFFFF.toInt())
            b.setBackgroundColor(0xFF1F6FEB.toInt())
            b.textSize = 18f
            b.setOnClickListener {
                selectedWord = w
                controller.onScreenResult(0)
            }
            list.addView(b)
        }
    }

    private fun onLetter(c: Char) {
        val prefix = letters + c
        // a letter that leads to no word is inert (upstream deactivates it)
        if (wordlist.none { it.startsWith(prefix) }) return
        letters = prefix
        refresh()
    }

    private fun onBackspace() {
        if (letters.isNotEmpty()) {
            letters = letters.substring(0, letters.length - 1)
            refresh()
        }
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) {
            // backing out of the first word cancels the entry
            if (curWordIndex == 0) controller.storage.discardPendingMnemonic()
            return Destination(BackStackView::class.java)
        }

        val word = selectedWord ?: return Destination(BackStackView::class.java)
        controller.storage.updatePendingMnemonic(word, curWordIndex)

        if (isCalcFinalWord && curWordIndex == controller.storage.pendingMnemonicLength - 2) {
            // Time to calculate the last word: the user decides how to specify
            // the last bits of entropy for the final word.
            return Destination(ToolsCalcFinalWordFinalizePromptView::class.java)
        }

        if (isCalcFinalWord && curWordIndex == controller.storage.pendingMnemonicLength - 1) {
            // The user selected a final word to contribute entropy; show the
            // computed checksum word.
            return Destination(ToolsCalcFinalWordShowFinalWordView::class.java,
                viewArgs = mapOf("coinFlips" to null))
        }

        if (curWordIndex < controller.storage.pendingMnemonicLength - 1) {
            return Destination(
                SeedMnemonicEntryView::class.java,
                viewArgs = mapOf("curWordIndex" to (curWordIndex + 1), "isCalcFinalWord" to isCalcFinalWord)
            )
        }

        // last word: attempt to finalize the mnemonic
        return try {
            controller.storage.convertPendingMnemonicToPendingSeed()
            Destination(SeedFinalizeView::class.java)
        } catch (e: Exception) {
            Destination(SeedMnemonicInvalidView::class.java)
        }
    }
}

/** 1:1 port of upstream SeedMnemonicInvalidView. */
class SeedMnemonicInvalidView : View() {
    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Invalid Mnemonic!", showBack = false)
        screen.addText("Checksum failure; not a valid seed phrase.")
        screen.addButtons(listOf("Review & edit", "Discard"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination = when (index) {
        0 -> Destination(SeedMnemonicEntryView::class.java, viewArgs = mapOf("curWordIndex" to 0, "isCalcFinalWord" to false))
        else -> {
            controller.storage.discardPendingMnemonic()
            Destination(MainMenuView::class.java, clearHistory = true)
        }
    }
}

/** 1:1 port of upstream SeedFinalizeView. */
class SeedFinalizeView : View() {

    override fun run(activity: Activity): Destination? {
        val seed = controller.storage.pendingSeed ?: return Destination(MainMenuView::class.java)
        val network = controller.settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        val fp = seed.getFingerprint(network)

        val screen = Ui.screen(activity, "Fingerprint: $fp")
        val buttons = mutableListOf("Done")
        if (controller.settings.getValue<String>(Settings.Const.SETTING__PASSPHRASE) !=
            Settings.Const.OPTION__DISABLED) {
            buttons.add(seed.passphraseLabel)
        }
        screen.addButtons(buttons)
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return when (index) {
            0 -> {
                val seed = controller.storage.finalizePendingSeed()
                Destination(SeedOptionsView::class.java,
                    viewArgs = mapOf("seedIndex" to (controller.storage.seeds.indexOf(seed))),
                    clearHistory = true)
            }
            else -> Destination(SeedAddPassphraseView::class.java)
        }
    }
}

/**
 * 1:1 port of upstream SeedAddPassphraseView. The Pi renders a letter-by-letter
 * keyboard; the touch equivalent is a plain text field (same View logic, same
 * routing: empty/back -> exit dialog, non-empty -> review).
 */
class SeedAddPassphraseView : View() {

    private var input: EditText? = null

    override fun run(activity: Activity): Destination? {
        val seed = controller.storage.pendingSeed ?: return Destination(MainMenuView::class.java)
        val screen = Ui.screen(activity, seed.passphraseLabel)
        val et = EditText(activity)
        et.hint = "BIP-39 passphrase"
        et.setTextColor(0xFFFFFFFF.toInt())
        et.setBackgroundColor(0xFF2A3138.toInt())
        screen.body.addView(et, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        screen.addButtons(listOf("Done"))
        controller.showScreen(screen)
        input = et
        return null
    }

    override fun onResult(index: Int): Destination {
        val seed = controller.storage.pendingSeed ?: return Destination(MainMenuView::class.java)

        val isBack = index == Ui.RET_CODE_BACK
        val text = input?.text?.toString() ?: ""
        seed.setPassphrase(text)

        return if (isBack || seed.passphrase.isEmpty()) {
            Destination(SeedAddPassphraseExitDialogView::class.java)
        } else {
            Destination(SeedReviewPassphraseView::class.java)
        }
    }
}

/** 1:1 port of upstream SeedAddPassphraseExitDialogView. */
class SeedAddPassphraseExitDialogView : View() {

    override fun run(activity: Activity): Destination? {
        val seed = controller.storage.pendingSeed ?: return Destination(MainMenuView::class.java)
        val (title, message, buttons) = if (seed.hasPassphrase) {
            Triple("Discard passphrase?", "Your current passphrase entry will be erased.",
                listOf("Edit passphrase", "Discard passphrase"))
        } else {
            Triple("Skip passphrase?", "You have not entered a passphrase yet.",
                listOf("Edit passphrase", "Skip passphrase"))
        }
        val screen = Ui.screen(activity, title, showBack = false)
        screen.addText(message)
        screen.addButtons(buttons)
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        val seed = controller.storage.pendingSeed ?: return Destination(MainMenuView::class.java)
        return when (index) {
            0 -> Destination(SeedAddPassphraseView::class.java)
            else -> {
                seed.setPassphrase("")
                Destination(SeedFinalizeView::class.java)
            }
        }
    }
}

/** 1:1 port of upstream SeedReviewPassphraseView. */
class SeedReviewPassphraseView : View() {

    override fun run(activity: Activity): Destination? {
        val seed = controller.storage.pendingSeed ?: return Destination(MainMenuView::class.java)
        val network = controller.settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        val fpWith = seed.getFingerprint(network)
        val saved = seed.passphrase
        seed.setPassphrase("")
        val fpWithout = seed.getFingerprint(network)
        seed.setPassphrase(saved)

        val screen = Ui.screen(activity, "Review passphrase", showBack = false)
        screen.addText("Passphrase: ${seed.passphrase}", 22f)
        screen.addText("Fingerprint without passphrase: $fpWithout", 14f)
        screen.addText("Fingerprint with passphrase: $fpWith", 14f)
        screen.addButtons(listOf("Edit passphrase", "Done"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        return when (index) {
            0 -> Destination(SeedAddPassphraseView::class.java)
            else -> {
                val seed = controller.storage.finalizePendingSeed()
                Destination(SeedOptionsView::class.java,
                    viewArgs = mapOf("seedIndex" to (controller.storage.seeds.indexOf(seed))),
                    clearHistory = true)
            }
        }
    }
}

/**
 * 1:1 port of upstream SeedDiscardView: confirm removal of an in-memory seed.
 */
class SeedDiscardView : View() {

    private var seedIndex: Int = 0

    override fun run(activity: Activity): Destination? {
        seedIndex = (viewArgs["seedIndex"] as? Int) ?: 0
        val seed = controller.storage.seeds[seedIndex]
        val network = controller.settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        val screen = Ui.screen(activity, "Remove seed ${seed.getFingerprint(network)}?", showBack = false)
        screen.addText("The seed is only in memory and will be gone for good.")
        screen.addButtons(listOf("Remove", "Cancel"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        return when (index) {
            0 -> {
                controller.storage.seeds.removeAt(seedIndex)
                Destination(SeedsMenuView::class.java, clearHistory = true)
            }
            else -> Destination(SeedOptionsView::class.java, viewArgs = mapOf("seedIndex" to seedIndex))
        }
    }
}
