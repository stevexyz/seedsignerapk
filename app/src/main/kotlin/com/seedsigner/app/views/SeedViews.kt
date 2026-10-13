package com.seedsigner.app.views

import android.app.Activity
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
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
            "Scan a SeedQR" -> Destination(ScanStubView::class.java)
            "Enter 12-word seed" -> {
                controller.storage.initPendingMnemonic(numWords = 12)
                Destination(SeedMnemonicEntryView::class.java)
            }
            "Enter 24-word seed" -> {
                controller.storage.initPendingMnemonic(numWords = 24)
                Destination(SeedMnemonicEntryView::class.java)
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

        val screen = Ui.screen(activity, "Seed $fp")
        screen.addButtons(listOf("Export xpubs", "BIP-85 child seeds", "Remove seed"))
        controller.showScreen(screen)
        seedIndex = index
        return null
    }

    private var seedIndex: Int = 0

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return when (index) {
            0 -> Destination(NotYetImplementedView::class.java, viewArgs = mapOf("feature" to "Export xpubs"))
            1 -> Destination(NotYetImplementedView::class.java, viewArgs = mapOf("feature" to "BIP-85 child seeds"))
            2 -> {
                controller.storage.seeds.removeAt(seedIndex)
                Destination(SeedsMenuView::class.java)
            }
            else -> Destination(SeedsMenuView::class.java)
        }
    }
}

/**
 * Mnemonic entry (upstream SeedMnemonicEntryView): the BIP-39 word grid +
 * on-screen letter keys. Full implementation lands in Chunk D; the stub
 * keeps the flow shape intact.
 */
class SeedMnemonicEntryView : View() {
    override fun run(activity: Activity): Destination =
        Destination(NotYetImplementedView::class.java, viewArgs = mapOf("feature" to "Mnemonic entry"))
}
