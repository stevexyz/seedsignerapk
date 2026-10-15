package com.seedsigner.app.views

import android.app.Activity
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
import com.seedsigner.app.models.Seed
import com.seedsigner.core.crypto.Bip32
import com.seedsigner.core.crypto.Bip39
import com.seedsigner.core.crypto.Bip85
import com.seedsigner.core.crypto.Networks
import kotlin.random.Random

/**
 * 1:1 port of the upstream word-display + backup-quiz flow (seed_views.py),
 * shared by generated seeds, the BIP-85 child-seed flow, and (upstream)
 * reviewed stored seeds.
 *
 * viewArg conventions (mirror upstream):
 *   seed: Seed?            null == "the controller's pending seed"
 *   bip85ChildIndex/bip85NumWords: Int?
 *                          when set, the displayed mnemonic is the BIP-85
 *                          child (app 39') of [seed], not the seed itself
 */

/** Resolve the mnemonic this flow displays (seed words or BIP-85 child words). */
private fun View.wordsFor(seed: Seed, bip85ChildIndex: Int?, bip85NumWords: Int?): List<String> {
    if (bip85ChildIndex == null) return seed.mnemonicList
    // Upstream derives with the default (mainnet) network, like its call sites.
    val root = Bip32.HDKey.fromSeed(seed.seedBytes, version = Networks.MAINNET.versions["xprv"]!!)
    return Bip85.deriveMnemonic(root, bip85NumWords!!, bip85ChildIndex)
}

private fun View.wordsTitle(bip85ChildIndex: Int?): String =
    if (bip85ChildIndex == null) "Seed Words" else "Child #$bip85ChildIndex"

/** Upstream SeedWordsWarningView. */
class SeedWordsWarningView(
    private val seed: Seed? = null,
    private val seedIndex: Int? = null,
    private val bip85ChildIndex: Int? = null,
    private val bip85NumWords: Int? = null
) : View() {

    private fun activeSeed(): Seed? = seed ?: seedIndex?.let { controller.storage.seeds[it] }

    override fun run(activity: Activity): Destination? {
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
            viewArgs = mapOf(
                "seed" to activeSeed(), "pageIndex" to 0,
                "bip85ChildIndex" to bip85ChildIndex, "bip85NumWords" to bip85NumWords
            ), skipCurrentView = true)
    }
}

/** Upstream SeedWordsView: 4 words per page. */
class SeedWordsView(
    private val seed: Seed? = null,
    private val seedIndex: Int? = null,
    private val pageIndex: Int = 0,
    private val bip85ChildIndex: Int? = null,
    private val bip85NumWords: Int? = null
) : View() {

    private val WORDS_PER_PAGE = 4

    private fun activeSeed(): Seed? = seed ?: seedIndex?.let { controller.storage.seeds[it] } ?: controller.storage.pendingSeed

    override fun run(activity: Activity): Destination? {
        val isPendingSeed = seed == null && seedIndex == null
        val theSeed = activeSeed()
        val mnemonic = wordsFor(theSeed!!, bip85ChildIndex, bip85NumWords)
        val numPages = mnemonic.size / WORDS_PER_PAGE
        val words = mnemonic.subList(pageIndex * WORDS_PER_PAGE, minOf(mnemonic.size, (pageIndex + 1) * WORDS_PER_PAGE))

        val buttons = if (pageIndex < numPages - 1 || isPendingSeed) listOf("Next") else listOf("Done")

        val screen = Ui.screen(activity, "${wordsTitle(bip85ChildIndex)}: ${pageIndex + 1}/$numPages")
        words.forEachIndexed { i, w ->
            screen.addText("${(pageIndex * WORDS_PER_PAGE + i) + 1}.  $w", 20f)
        }
        screen.addButtons(buttons)
        controller.showScreen(screen)
        return null
    }

    private val nextArgs: Map<String, Any?>
        get() = mapOf(
            "seed" to seed, "seedIndex" to seedIndex,
            "bip85ChildIndex" to bip85ChildIndex,
            "bip85NumWords" to bip85NumWords
        )

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val isPendingSeed = seed == null && seedIndex == null
        val mnemonic = wordsFor(activeSeed()!!, bip85ChildIndex, bip85NumWords)
        val numPages = mnemonic.size / WORDS_PER_PAGE

        return if (pageIndex < numPages - 1) {
            Destination(SeedWordsView::class.java, nextArgs + ("pageIndex" to (pageIndex + 1)))
        } else if (isPendingSeed) {
            Destination(SeedWordsBackupTestPromptView::class.java, nextArgs)
        } else {
            // Must clear history to avoid BACK returning to the private words.
            Destination(SeedWordsBackupTestPromptView::class.java, nextArgs, clearHistory = true)
        }
    }
}

/** Upstream SeedWordsBackupTestPromptView. */
class SeedWordsBackupTestPromptView(
    private val seed: Seed? = null,
    private val seedIndex: Int? = null,
    private val bip85ChildIndex: Int? = null,
    private val bip85NumWords: Int? = null
) : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Backup Test")
        screen.addText("Test that you wrote down the words correctly?", 16f)
        screen.addButtons(listOf("Verify", "Skip"))
        controller.showScreen(screen)
        return null
    }

    private val args: Map<String, Any?>
        get() = mapOf(
            "seed" to seed, "seedIndex" to seedIndex,
            "bip85ChildIndex" to bip85ChildIndex,
            "bip85NumWords" to bip85NumWords
        )

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return if (index == 0) {
            Destination(SeedWordsBackupTestView::class.java,
                args + ("confirmedList" to null) + ("curIndex" to null))
        } else {
            if (seed == null && seedIndex == null) Destination(SeedFinalizeView::class.java)
            else Destination(SeedOptionsView::class.java,
                viewArgs = mapOf("seedIndex" to (seedIndex ?: controller.storage.seeds.indexOf(seed!!))))
        }
    }
}

/**
 * Upstream SeedWordsBackupTestView: 4-option word quiz, random order,
 * one random word at a time until every word is confirmed.
 */
class SeedWordsBackupTestView(
    private val seed: Seed? = null,
    private val seedIndex: Int? = null,
    private val confirmedList: List<*>? = null,
    private val curIndex: Int? = null,
    private val bip85ChildIndex: Int? = null,
    private val bip85NumWords: Int? = null
) : View() {

    private var mnemonicList: List<String> = emptyList()
    private var confirmed = mutableListOf<Int>()
    private var cur = -1
    private var options: List<String> = emptyList()

    private fun activeSeed(): Seed? = seed ?: seedIndex?.let { controller.storage.seeds[it] } ?: controller.storage.pendingSeed

    override fun run(activity: Activity): Destination? {
        val theSeed = activeSeed()
        mnemonicList = wordsFor(theSeed!!, bip85ChildIndex, bip85NumWords)
        confirmedList?.filterNotNull()?.forEach { confirmed.add(it as Int) }
        cur = curIndex ?: -1

        if (cur < 0) {
            do {
                cur = Random.nextInt(mnemonicList.size)
            } while (cur in confirmed)
        }

        val realWord = mnemonicList[cur]
        val fakes = (0 until 3).map { Bip39.WORDLIST[Random.nextInt(Bip39.WORDLIST.size)] }
        options = (listOf(realWord) + fakes).shuffled()

        val screen = Ui.screen(activity, "Verify Word #${cur + 1}", showBack = false)
        screen.addText("Which one is word #${cur + 1}?", 16f)
        screen.addButtons(options)
        controller.showScreen(screen)
        return null
    }

    private val args: Map<String, Any?>
        get() = mapOf(
            "seed" to seed, "seedIndex" to seedIndex,
            "bip85ChildIndex" to bip85ChildIndex,
            "bip85NumWords" to bip85NumWords
        )

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val chosen = options[index]

        return if (chosen == mnemonicList[cur]) {
            confirmed.add(cur)
            if (confirmed.size == mnemonicList.size) {
                // Confirmed the full mnemonic.
                Destination(SeedWordsBackupTestSuccessView::class.java, args)
            } else {
                Destination(SeedWordsBackupTestView::class.java,
                    args + ("confirmedList" to confirmed.toList()) + ("curIndex" to null))
            }
        } else {
            Destination(SeedWordsBackupTestMistakeView::class.java,
                args + ("curIndex" to cur) + ("wrongWord" to chosen) + ("confirmedList" to confirmed.toList()))
        }
    }
}

/** Upstream SeedWordsBackupTestMistakeView. */
class SeedWordsBackupTestMistakeView(
    private val seed: Seed? = null,
    private val seedIndex: Int? = null,
    private val curIndex: Int = 0,
    private val wrongWord: String = "",
    private val confirmedList: List<*>? = null,
    private val bip85ChildIndex: Int? = null,
    private val bip85NumWords: Int? = null
) : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Verification Error", showBack = false)
        screen.addText("Wrong Word!", 20f, 0xFFFF5252.toInt())
        screen.addText("Word #${curIndex + 1} is not \"$wrongWord\"!", 16f)
        screen.addButtons(listOf("Review seed words", "Try again"))
        controller.showScreen(screen)
        return null
    }

    private val args: Map<String, Any?>
        get() = mapOf(
            "seed" to seed, "seedIndex" to seedIndex,
            "bip85ChildIndex" to bip85ChildIndex,
            "bip85NumWords" to bip85NumWords
        )

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return if (index == 0) {
            Destination(SeedWordsView::class.java, args + ("pageIndex" to 0))
        } else {
            Destination(SeedWordsBackupTestView::class.java,
                args + ("confirmedList" to confirmedList) + ("curIndex" to curIndex))
        }
    }
}

/** Upstream SeedWordsBackupTestSuccessView. */
class SeedWordsBackupTestSuccessView(
    private val seed: Seed? = null,
    private val seedIndex: Int? = null,
    private val bip85ChildIndex: Int? = null,
    private val bip85NumWords: Int? = null
) : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Backup Verified", showBack = false)
        screen.addText("Success!", 22f, 0xFF81C784.toInt())
        screen.addText("All words were successfully verified!", 16f)
        screen.addButtons(listOf("OK"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        return if (seed == null && seedIndex == null) {
            Destination(SeedFinalizeView::class.java)
        } else {
            Destination(SeedOptionsView::class.java,
                viewArgs = mapOf("seedIndex" to (seedIndex ?: controller.storage.seeds.indexOf(seed!!))), clearHistory = true)
        }
    }
}
