package com.seedsigner.app.views

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.LinearLayout
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
import com.seedsigner.app.qr.QrDisplay

/**
 * Upstream animated QR display (EncodeQrSequence screens): cycles through
 * the UR2 parts of a payload one QR at a time (2 s per part, upstream
 * cadence) so a phone camera can capture the whole fountain sequence.
 *
 * viewArgs:
 *   "parts": List<String>  (UR strings in encode order)
 *   "title": String        (screen title)
 */
class QrDisplayView : View() {

    companion object {
        const val PART_INTERVAL_MS = 2000L
    }

    // viewArgs is assigned by the Controller AFTER construction, so it must
    // not be read in plain property initializers (see View.kt) — lazy keeps
    // this correct while reading once.
    private val parts: List<String> by lazy { viewArgs["parts"] as List<String> }
    private val title: String by lazy { (viewArgs["title"] as? String) ?: "Display QR" }
    /** Upstream QR displays return to the main menu when done. */
    private val returnToMain: Boolean by lazy { (viewArgs["returnToMain"] as? Boolean) ?: false }

    private val handler = Handler(Looper.getMainLooper())
    private var index = 0
    private var cycling = false

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, title)

        val dm = activity.resources.displayMetrics
        val sizePx = (minOf(dm.widthPixels, dm.heightPixels) * 0.7f).toInt()
        val image = ImageView(activity)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        screen.body.addView(image, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val status = screen.addText("Part 1 of ${parts.size}")

        val show = {
            image.setImageBitmap(QrDisplay.bitmap(parts[index], sizePx))
            status.text = "Part ${index + 1} of ${parts.size}"
        }

        val cycle = object : Runnable {
            override fun run() {
                if (!cycling) return
                if (index < parts.size - 1) {
                    index++
                    show.invoke()
                }
                handler.postDelayed(this, PART_INTERVAL_MS)
            }
        }

        screen.addButtons(listOf("Done"))
        controller.showScreen(screen)

        cycling = true
        show.invoke()
        handler.postDelayed(cycle, PART_INTERVAL_MS)
        return null
    }

    override fun onResult(index: Int): Destination {
        cycling = false
        handler.removeCallbacksAndMessages(null)
        return if (returnToMain) {
            Destination(MainMenuView::class.java, clearHistory = true)
        } else {
            Destination(BackStackView::class.java)
        }
    }
}
