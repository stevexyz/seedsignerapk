package com.seedsigner.app.views

import android.app.Activity
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
import com.seedsigner.app.models.Seed
import com.seedsigner.core.crypto.Bip39
import kotlin.random.Random

/**
 * 1:1 port of the upstream generated-words flow (seed_views.py):
 * warning -> paged word display -> optional backup quiz -> finalize.
 *
 * The `seed: Seed?` viewArg carries upstream's pending-seed convention:
 * null means "use the controller's pending seed" (the just-generated one).
 * BIP-85 child-seed support (upstream's bip85_data arg) is v0.3.
 */

/** Upstream SeedWordsWarningView. */
class SeedWordsWarningView : View() {

    private var seed: Seed? = null

    override fun run(activity: Activity): Destination? {
        seed = viewArgs["seed"] as? Seed
        val screen = Ui.screen(activity, "Keep your seed private")
        screen.addText("You must keep your seed words private & away from all online devices.", 16f)
        screen.addButtons(listOf("I Understand"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        // Only one exit point.
        return Destination(SeedWordsView::class.java,
            viewArgs = mapOf("seed" to seed, "pageIndex" to 0), skipCurrentView = true)
    }
}

/** Upstream SeedWordsView: 4 words per page. */
class SeedWordsView : View() {

    private val WORDS_PER_PAGE = 4

    private var seed: Seed? = null
    private var isPendingSeed = false
    private var pageIndex = 0

    override fun run(activity: Activity): Destination? {
        pageIndex = viewArgs["pageIndex"] as Int
        seed = viewArgs["seed"] as? Seed
        if (seed == null) {
            isPendingSeed = true
            seed = controller.storage.pendingSeed
        }
        val mnemonic = seed!!.mnemonicList
        val numPages = mnemonic.size / WORDS_PER_PAGE
        val words = mnemonic.subList(pageIndex * WORDS_PER_PAGE, minOf(mnemonic.size, (pageIndex + 1) * WORDS_PER_PAGE))

        val buttons = if (pageIndex < numPages - 1 || isPendingSeed) listOf("Next") else listOf("Done")

        val screen = Ui.screen(activity, "Seed Words: ${pageIndex + 1}/$numPages")
        words.forEachIndexed { i, w ->
            screen.addText("${(pageIndex * WORDS_PER_PAGE + i) + 1}.  $w", 20f)
        }
        screen.addButtons(buttons)
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val mnemonic = seed!!.mnemonicList
        val numPages = mnemonic.size / WORDS_PER_PAGE

        // NEXT on the last page (pending) or DONE
        return if (pageIndex < numPages - 1) {
            Destination(SeedWordsView::class.java,
                viewArgs = mapOf("seed" to (if (isPendingSeed) null else seed), "pageIndex" to (pageIndex + 1)))
        } else if (isPendingSeed) {
            Destination(SeedWordsBackupTestPromptView::class.java, viewArgs = mapOf("seed" to null))
        } else {
            // Must clear history to avoid BACK returning to the private words.
            Destination(SeedWordsBackupTestPromptView::class.java,
                viewArgs = mapOf("seed" to seed), clearHistory = true)
        }
    }
}

/** Upstream SeedWordsBackupTestPromptView. */
class SeedWordsBackupTestPromptView : View() {

    private var seed: Seed? = null

    override fun run(activity: Activity): Destination? {
        seed = viewArgs["seed"] as? Seed
        val screen = Ui.screen(activity, "Backup Test")
        screen.addText("Test that you wrote down the seed correctly?", 16f)
        screen.addButtons(listOf("Verify", "Skip"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return if (index == 0) {
            Destination(SeedWordsBackupTestView::class.java,
                viewArgs = mapOf("seed" to seed, "confirmedList" to null, "curIndex" to null))
        } else {
            if (seed == null) Destination(SeedFinalizeView::class.java)
            else Destination(SeedOptionsView::class.java, viewArgs = mapOf("seedIndex" to controller.storage.seeds.indexOf(seed)))
        }
    }
}

/**
 * Upstream SeedWordsBackupTestView: 4-option word quiz, random order,
 * one random word at a time until every word is confirmed.
 */
class SeedWordsBackupTestView : View() {

    private var seed: Seed? = null
    private var isPendingSeed = false
    private var mnemonicList: List<String> = emptyList()
    private var confirmedList: MutableList<Int> = mutableListOf()
    private var curIndex: Int = -1
    private var options: List<String> = emptyList()

    override fun run(activity: Activity): Destination? {
        seed = viewArgs["seed"] as? Seed
        if (seed == null) {
            isPendingSeed = true
            seed = controller.storage.pendingSeed
        }
        mnemonicList = seed!!.mnemonicList
        (viewArgs["confirmedList"] as? List<*>)?.filterNotNull()?.forEach { confirmedList.add(it as Int) }
        curIndex = (viewArgs["curIndex"] as? Int) ?: -1

        if (curIndex < 0) {
            do {
                curIndex = Random.nextInt(mnemonicList.size)
            } while (curIndex in confirmedList)
        }

        val realWord = mnemonicList[curIndex]
        val fakes = (0 until 3).map { Bip39.WORDLIST[Random.nextInt(Bip39.WORDLIST.size)] }
        options = (listOf(realWord) + fakes).shuffled()

        val screen = Ui.screen(activity, "Verify Word #${curIndex + 1}", showBack = false)
        screen.addText("Which one is word #${curIndex + 1}?", 16f)
        screen.addButtons(options)
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val chosen = options[index]

        // pending seed travels on as null (upstream convention)
        val nextSeed = if (isPendingSeed) null else seed

        return if (chosen == mnemonicList[curIndex]) {
            confirmedList.add(curIndex)
            if (confirmedList.size == mnemonicList.size) {
                // Confirmed the full mnemonic.
                Destination(SeedWordsBackupTestSuccessView::class.java, viewArgs = mapOf("seed" to nextSeed))
            } else {
                Destination(SeedWordsBackupTestView::class.java,
                    viewArgs = mapOf("seed" to nextSeed, "confirmedList" to confirmedList.toList(), "curIndex" to null))
            }
        } else {
            Destination(SeedWordsBackupTestMistakeView::class.java,
                viewArgs = mapOf(
                    "seed" to nextSeed,
                    "curIndex" to curIndex,
                    "wrongWord" to chosen,
                    "confirmedList" to confirmedList.toList()
                ))
        }
    }
}

/** Upstream SeedWordsBackupTestMistakeView. */
class SeedWordsBackupTestMistakeView : View() {

    private var seed: Seed? = null
    private var curIndex = 0
    private var wrongWord = ""
    private var confirmedList: List<Int> = emptyList()

    override fun run(activity: Activity): Destination? {
        seed = viewArgs["seed"] as? Seed
        curIndex = viewArgs["curIndex"] as Int
        wrongWord = viewArgs["wrongWord"] as String
        confirmedList = (viewArgs["confirmedList"] as? List<*>)?.filterNotNull()?.map { it as Int } ?: emptyList()

        val screen = Ui.screen(activity, "Verification Error", showBack = false)
        screen.addText("Wrong Word!", 20f, 0xFFFF5252.toInt())
        screen.addText("Word #${curIndex + 1} is not \"$wrongWord\"!", 16f)
        screen.addButtons(listOf("Review seed words", "Try again"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return if (index == 0) {
            Destination(SeedWordsView::class.java, viewArgs = mapOf("seed" to seed, "pageIndex" to 0))
        } else {
            Destination(SeedWordsBackupTestView::class.java,
                viewArgs = mapOf("seed" to seed, "confirmedList" to confirmedList, "curIndex" to curIndex))
        }
    }
}

/** Upstream SeedWordsBackupTestSuccessView. */
class SeedWordsBackupTestSuccessView : View() {

    private var seed: Seed? = null

    override fun run(activity: Activity): Destination? {
        seed = viewArgs["seed"] as? Seed
        val screen = Ui.screen(activity, "Backup Verified", showBack = false)
        screen.addText("Success!", 22f, 0xFF81C784.toInt())
        screen.addText("All mnemonic backup words were successfully verified!", 16f)
        screen.addButtons(listOf("OK"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        return if (seed == null) {
            Destination(SeedFinalizeView::class.java)
        } else {
            Destination(SeedOptionsView::class.java,
                viewArgs = mapOf("seedIndex" to controller.storage.seeds.indexOf(seed)), clearHistory = true)
        }
    }
}
