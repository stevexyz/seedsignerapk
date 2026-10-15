package com.seedsigner.app.views

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View

/**
 * 1:1 port of the upstream BIP-85 child-seed flow (seed_views.py):
 * num words -> child index -> warning -> words -> quiz (shared views).
 * Derivation is `core` `Bip85` (app 39', byte-identical to embit,
 * `:core:crosscheck-bip85` incl. the official BIP-85 spec vectors).
 *
 * viewArgs: seedIndex: Int (the parent seed in controller.storage.seeds).
 */

/** Upstream SeedBIP85SelectNumWordsView. */
class SeedBIP85SelectNumWordsView(
    private val seedIndex: Int = 0
) : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "BIP-85 Num Words")
        screen.addButtons(listOf("12 Words", "24 Words"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val numWords = if (index == 0) 12 else 24
        return Destination(SeedBIP85SelectChildIndexView::class.java,
            viewArgs = mapOf("seedIndex" to seedIndex, "numWords" to numWords))
    }
}

/** Upstream SeedBIP85SelectChildIndexView: a number entry screen. */
class SeedBIP85SelectChildIndexView(
    private val seedIndex: Int = 0,
    private val numWords: Int = 12
) : View() {

    private var input: EditText? = null

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "BIP-85 Child Index")
        val et = EditText(activity)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.hint = "0 - 2147483647"
        et.setTextColor(0xFFFFFFFF.toInt())
        et.setBackgroundColor(0xFF2A3138.toInt())
        screen.body.addView(et, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        screen.addButtons(listOf("OK"))
        controller.showScreen(screen)
        input = et
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val text = input?.text?.toString()?.trim() ?: ""
        val childIndex = text.toLongOrNull()
        if (childIndex == null || childIndex < 0 || childIndex >= 2147483648L) {
            return Destination(SeedBIP85InvalidChildIndexView::class.java,
                viewArgs = mapOf("seedIndex" to seedIndex, "numWords" to numWords),
                skipCurrentView = true)
        }
        return Destination(SeedWordsWarningView::class.java,
            viewArgs = mapOf(
                "seed" to controller.storage.seeds[seedIndex],
                "bip85ChildIndex" to childIndex.toInt(),
                "bip85NumWords" to numWords
            ))
    }
}

/** Upstream SeedBIP85InvalidChildIndexView. */
class SeedBIP85InvalidChildIndexView(
    private val seedIndex: Int = 0,
    private val numWords: Int = 12
) : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "BIP-85 Index Error", showBack = false)
        screen.addText("Invalid Child Index", 20f, 0xFFFF5252.toInt())
        screen.addText("BIP-85 Child Index must be between 0 and 2^31-1.", 16f)
        screen.addButtons(listOf("Try again"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination =
        Destination(SeedBIP85SelectChildIndexView::class.java,
            viewArgs = mapOf("seedIndex" to seedIndex, "numWords" to numWords),
            skipCurrentView = true)
}
