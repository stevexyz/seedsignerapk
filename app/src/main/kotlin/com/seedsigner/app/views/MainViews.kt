package com.seedsigner.app.views

import android.app.Activity
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Controller
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View

/**
 * Upstream OpeningSplashView: logo screen, auto-advance to the main menu.
 * (Upstream shows partner logos while the screen is up; that is a donation
 * UI element, not a flow element — text logo only here.)
 */
class OpeningSplashView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "")
        screen.addText("SeedSigner", 32f)
        controller.showScreen(screen)
        // auto-advance (upstream splash is a fixed-duration logo screen)
        activity.window.decorView.postDelayed({ controller.onScreenResult(0) }, 1500)
        return null
    }

    override fun onResult(index: Int): Destination =
        Destination(MainMenuView::class.java)
}

/**
 * 1:1 port of upstream MainMenuView: Scan / Seeds / Tools / Settings.
 */
class MainMenuView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Home", showBack = false)
        screen.addButtons(listOf("Scan", "Seeds", "Tools", "Settings"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination = when (index) {
        0 -> Destination(ScanView::class.java)
        1 -> Destination(SeedsMenuView::class.java)
        2 -> Destination(ToolsMenuView::class.java)
        3 -> Destination(SettingsMenuView::class.java)
        else -> Destination(MainMenuView::class.java)
    }
}

/**
 * Upstream PowerOptionsView: Restart / Power off, with confirmation
 * (upstream RestartView / PowerOffView).
 * Android mapping: Restart = recreate the activity (wipes all in-memory
 * state, spec §1.2); Power off = finish the process.
 */
class PowerOptionsView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Reset / Power")
        screen.addButtons(listOf("Restart", "Power off"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination = when (index) {
        0 -> {
            controller.finishFlow()
            activity.recreate()
            Destination(null) // ignored; MainActivity.onCreate restarts the flow
        }
        1 -> {
            controller.finishFlow()
            activity.finishAffinity()
            Destination(null)
        }
        else -> Destination(BackStackView::class.java)
    }
}
