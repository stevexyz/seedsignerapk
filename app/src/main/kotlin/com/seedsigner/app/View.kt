package com.seedsigner.app

import android.app.Activity
import com.seedsigner.app.views.MainMenuView

/**
 * 1:1 port of the upstream MVC flow (seedsigner_orig/src/seedsigner/views/view.py
 * + controller.py): a View is a biz-logic unit that shows one screen and
 * returns a Destination telling the Controller which View to run next.
 */

/**
 * Marker: pop the back stack (upstream `BackStackView`). Never run directly —
 * the Controller intercepts it before execution.
 */
class BackStackView : View() {
    override fun run(activity: Activity): Destination =
        throw IllegalStateException("BackStackView is intercepted by the Controller")
}

/**
 * Route token. [ViewCls] is the class of the next View (null == Home).
 * [skipCurrentView]: the current View only forwarded; omit it from history.
 * [clearHistory]: wipe the back stack (destructive exits).
 */
class Destination(
    val ViewCls: Class<out View>?,
    val viewArgs: Map<String, Any?> = emptyMap(),
    val skipCurrentView: Boolean = false,
    val clearHistory: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (other !is Destination) return false
        return other.ViewCls == ViewCls && other.viewArgs == viewArgs
    }

    override fun hashCode(): Int = ViewCls?.hashCode() ?: 0

    override fun toString(): String = ViewCls?.simpleName ?: "None"
}

abstract class View {
    lateinit var activity: Activity
    var viewArgs: Map<String, Any?> = emptyMap()
    val controller: Controller = Controller.instance
    var isScreensaverAllowed: Boolean = true

    protected val settings get() = controller.settings

    /**
     * Show the View's single screen. Return a Destination to redirect
     * immediately without waiting for user input (upstream `set_redirect`),
     * or null to wait for the user's tap (delivered to [onResult]).
     */
    abstract fun run(activity: Activity): Destination?

    /** The user tapped button [index] (or [Ui.RET_CODE_BACK]); next route. */
    open fun onResult(index: Int): Destination =
        Destination(MainMenuView::class.java, clearHistory = true)
}
