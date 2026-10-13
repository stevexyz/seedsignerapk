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

    private val parts: List<String> = viewArgs["parts"] as List<String>
    private val title: String = (viewArgs["title"] as? String) ?: "Display QR"

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
        return Destination(BackStackView::class.java)
    }
}
