package com.seedsigner.app

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Screen-building helpers (classic framework Views only, spec §1.4).
 *
 * Mirrors the upstream presentation layer's job (gui/screens): a View (biz
 * logic) asks for a titled screen with a back button and a list of big
 * buttons; user taps flow back as an index, with -1 == back pressed.
 */
object Ui {

    /** -1 when the back button (or Android back) is pressed. */
    const val RET_CODE_BACK = -1

    /**
     * Full-screen layout: dark background, title bar (back arrow + title),
     * a vertical body, and a bottom button list.
     */
    fun screen(context: Context, title: String, showBack: Boolean = true): Screen {
        val density = context.resources.displayMetrics.density
        val pad = (density * 10).toInt()

        val root = FrameLayout(context)
        root.setBackgroundColor(0xFF101418.toInt())

        val screen = Screen(root)

        val content = LinearLayout(context)
        content.orientation = LinearLayout.VERTICAL

        val titleBar = LinearLayout(context)
        titleBar.orientation = LinearLayout.HORIZONTAL
        titleBar.gravity = Gravity.CENTER_VERTICAL
        titleBar.setPadding(pad, pad, pad, pad / 2)

        if (showBack) {
            val back = Button(context)
            back.text = "←"
            back.setTextColor(0xFFFFFFFF.toInt())
            back.setBackgroundColor(0x00000000)
            back.isAllCaps = false
            back.setOnClickListener { screen.back() }
            titleBar.addView(back)
        }

        val titleText = TextView(context)
        titleText.text = title
        titleText.setTextColor(0xFFFFFFFF.toInt())
        titleText.textSize = 20f
        titleText.gravity = Gravity.CENTER
        titleBar.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(titleBar)

        val body = LinearLayout(context)
        body.orientation = LinearLayout.VERTICAL
        body.gravity = Gravity.CENTER
        content.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val buttons = LinearLayout(context)
        buttons.orientation = LinearLayout.VERTICAL
        buttons.setPadding(pad, 0, pad, pad)
        val buttonsWrap = FrameLayout(context)
        buttonsWrap.addView(buttons, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })
        root.addView(buttonsWrap)

        screen.body = body
        screen.buttons = buttons
        return screen
    }

    /**
     * A built screen. [back] fires for the on-screen back arrow and for the
     * Android back gesture (MainActivity forwards to it).
     */
    class Screen(val root: FrameLayout) {
        lateinit var body: LinearLayout
        lateinit var buttons: LinearLayout

        /** Set by Controller.showScreen; receives a button index or RET_CODE_BACK. */
        var resultHandler: ((Int) -> Unit)? = null

        fun back() { resultHandler?.invoke(RET_CODE_BACK) }

        fun addText(text: String, size: Float = 16f, color: Int = 0xFFFFFFFF.toInt()): TextView {
            val tv = TextView(root.context)
            tv.text = text
            tv.setTextColor(color)
            tv.textSize = size
            tv.gravity = Gravity.CENTER
            body.addView(tv)
            return tv
        }

        fun addButtons(options: List<String>) {
            val density = root.context.resources.displayMetrics.density
            options.forEachIndexed { i, label ->
                val b = Button(root.context)
                b.text = label
                b.isAllCaps = false
                b.setTextColor(0xFFFFFFFF.toInt())
                b.setBackgroundColor(0xFF1F6FEB.toInt())
                b.textSize = 18f
                b.setOnClickListener { resultHandler?.invoke(i) }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (density * 52).toInt()
                )
                lp.bottomMargin = (density * 8).toInt()
                buttons.addView(b, lp)
            }
        }
    }
}
