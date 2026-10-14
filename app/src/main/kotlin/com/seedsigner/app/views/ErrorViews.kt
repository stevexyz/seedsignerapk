package com.seedsigner.app.views

import android.app.Activity
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View

/**
 * Upstream NotYetImplementedView: tells the user the feature is coming.
 */
class NotYetImplementedView : View() {

    override fun run(activity: Activity): Destination? {
        val feature = (viewArgs["feature"] as? String) ?: "this feature"
        val screen = Ui.screen(activity, "Not yet implemented")
        screen.addText("$feature is not implemented in this port yet.")
        screen.addText("All SeedSigner flows are being ported; this one is next in line.")
        screen.addButtons(listOf("Back"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination =
        Destination(BackStackView::class.java)
}

/**
 * Upstream ErrorView (view.py): a generic titled error screen; OK goes
 * back. viewArgs: title, headline, text.
 */
class ErrorView : View() {

    override fun run(activity: Activity): Destination? {
        val title = (viewArgs["title"] as? String) ?: "Error"
        val headline = (viewArgs["headline"] as? String) ?: ""
        val text = (viewArgs["text"] as? String) ?: ""
        val screen = Ui.screen(activity, title)
        if (headline.isNotEmpty()) screen.addText(headline, 20f, 0xFFFFB4B4.toInt())
        if (text.isNotEmpty()) screen.addText(text, 15f)
        screen.addButtons(listOf("Back"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination =
        Destination(BackStackView::class.java, skipCurrentView = true)
}

/**
 * Upstream UnhandledExceptionView: debugging info (exception type,
 * file:line, message) shown to the user with a restart suggestion.
 */
class UnhandledExceptionView : View() {

    override fun run(activity: Activity): Destination? {
        val error = viewArgs["error"] as? List<*> ?: listOf("?", "?", "?")
        val screen = Ui.screen(activity, "Unhandled exception")
        screen.addText("The app hit an unexpected error.")
        screen.addText("${error[0]} @ ${error[1]}", 13f, 0xFFFFB4B4.toInt())
        screen.addText(error[2].toString(), 13f)
        screen.addButtons(listOf("Restart app", "Power off"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        return when (index) {
            0 -> {
                controller.finishFlow()
                activity.recreate()
                Destination(null)
            }
            1 -> {
                controller.finishFlow()
                activity.finishAffinity()
                Destination(null)
            }
            else -> Destination(MainMenuView::class.java, clearHistory = true)
        }
    }
}
