package com.seedsigner.app.views

import android.app.Activity
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
import com.seedsigner.app.models.Settings

/**
 * 1:1 port of upstream SettingsMenuView (settings_views.py).
 *
 * Android deviation (documented): the "Hardware" visibility level is
 * dropped — its entries (display, panel brightness, camera rotation) do not
 * exist on Android. General → Advanced nesting is preserved.
 */
class SettingsMenuView : View() {

    // lazy: viewArgs is assigned by the Controller after construction.
    private val visibility: Int by lazy {
        (viewArgs["visibility"] as? Int) ?: Settings.Const.VISIBILITY__GENERAL
    }

    override fun run(activity: Activity): Destination? {
        val c = Settings.Const
        val entries = controller.settings.entries.filter { it.visibility == visibility }

        val labels = entries.map { it.displayName }
        val screen = Ui.screen(activity,
            if (visibility == c.VISIBILITY__GENERAL) "Settings" else "Advanced")

        val extraButtons = when (visibility) {
            c.VISIBILITY__GENERAL -> listOf("Advanced", "Version")
            else -> emptyList()
        }
        screen.addButtons(labels + extraButtons)
        controller.showScreen(screen)
        return null
    }

    private val entryCount get() =
        controller.settings.entries.count { it.visibility == visibility }

    override fun onResult(index: Int): Destination {
        val c = Settings.Const
        if (index == Ui.RET_CODE_BACK) {
            return if (visibility == c.VISIBILITY__GENERAL) {
                Destination(MainMenuView::class.java)
            } else {
                Destination(SettingsMenuView::class.java)
            }
        }
        if (index < entryCount) {
            val entry = controller.settings.entries
                .filter { it.visibility == visibility }[index]
            return Destination(SettingsEntryUpdateView::class.java,
                viewArgs = mapOf("attrName" to entry.attrName))
        }
        val extraIndex = index - entryCount
        return if (visibility == c.VISIBILITY__GENERAL) {
            when (extraIndex) {
                0 -> Destination(SettingsMenuView::class.java,
                    viewArgs = mapOf("visibility" to c.VISIBILITY__ADVANCED))
                else -> Destination(VersionView::class.java)
            }
        } else {
            Destination(SettingsMenuView::class.java)
        }
    }
}

/**
 * Upstream SettingsEntryUpdateSelectionView: pick one option (SELECT_1 /
 * ENABLED_DISABLED) or toggle a set (MULTISELECT).
 */
class SettingsEntryUpdateView : View() {

    // lazy: viewArgs is assigned by the Controller after construction.
    private val attrName: String by lazy { viewArgs["attrName"] as String }
    private val entry: Settings.Entry by lazy { controller.settings.getEntry(attrName) }

    override fun run(activity: Activity): Destination? {
        val c = Settings.Const
        val labels: List<String>
        val currentValues: List<String>

        if (entry.type == c.TYPE__MULTISELECT) {
            currentValues = controller.settings.getValue<List<String>>(attrName)
            labels = entry.selectionOptions.map { (value, name) ->
                if (value in currentValues) "✓ $name" else "  $name"
            }
        } else {
            val current = controller.settings.getValue<String>(attrName)
            val options = if (entry.selectionOptions.isNotEmpty()) entry.selectionOptions
            else listOf(c.OPTION__ENABLED to "Enabled", c.OPTION__DISABLED to "Disabled")
            currentValues = listOf(current)
            labels = options.map { (value, name) ->
                if (value == current) "✓ $name" else "  $name"
            }
        }

        val screen = Ui.screen(activity, entry.displayName)
        screen.addButtons(labels)
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        val c = Settings.Const
        if (index == Ui.RET_CODE_BACK) return Destination(SettingsMenuView::class.java)

        if (entry.type == c.TYPE__MULTISELECT) {
            val current = controller.settings.getValue<List<String>>(attrName).toMutableList()
            val value = entry.selectionOptions[index].first
            if (value in current) current.remove(value) else current.add(value)
            controller.settings.setValue(attrName, current)
        } else {
            val value = if (entry.selectionOptions.isNotEmpty()) entry.selectionOptions[index].first
            else if (index == 0) c.OPTION__ENABLED else c.OPTION__DISABLED
            controller.settings.setValue(attrName, value)
        }
        // Persist every change (upstream saves on each settings update); the
        // file is app-private and never holds secrets.
        controller.settings.save(activity)
        return Destination(SettingsMenuView::class.java)
    }
}

/** Upstream VersionView: app version + git commit. */
class VersionView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Version")
        // Keep in sync with versionName in app/build.gradle.kts.
        screen.addText("SeedSigner v0.4.0 (Android port)")
        screen.addText("Experimental release — not for production use")
        screen.addButtons(listOf("Back"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination =
        Destination(SettingsMenuView::class.java)
}

/**
 * 1:1 port of upstream SettingsIngestSettingsQRView (settings_views.py:310):
 * parse a scanned "settings::v1 ..." QR, apply it, show the confirmation.
 */
class SettingsIngestSettingsQRView : View() {

    override fun run(activity: Activity): Destination? {
        val data = viewArgs["data"] as? String ?: ""
        // May raise, which bubbles up to the Controller's error screen,
        // exactly like upstream.
        val (configName, updates) = com.seedsigner.app.models.SettingsQr.parse(data)
        updates.forEach { (k, v) -> controller.settings.setValue(k, v) }
        controller.settings.save(activity)

        val screen = Ui.screen(activity, "Settings QR", showBack = false)
        configName?.let { screen.addText(it, 18f) }
        screen.addText("${updates.size} setting(s) applied", 16f)
        screen.addText("Settings saved", 14f, 0xFF9E9E9E.toInt())
        screen.addButtons(listOf("Done"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        // Only one exit point upstream.
        return Destination(MainMenuView::class.java, clearHistory = true)
    }
}
