package com.seedsigner.app

import android.app.Activity
import android.util.Log
import com.seedsigner.app.views.MainMenuView
import com.seedsigner.app.views.OpeningSplashView
import com.seedsigner.core.psbt.Psbt
import com.seedsigner.app.models.Seed
import com.seedsigner.app.models.SeedStorage
import com.seedsigner.app.models.Settings

/**
 * 1:1 port of seedsigner_orig/src/seedsigner/controller.py.
 *
 * Singleton holding all app state (settings, in-memory seeds, working PSBT,
 * back stack, flow resumption). Seeds live only in this process's memory
 * (spec §1.2): process death wipes them.
 *
 * Threading note: upstream's `Controller.start()` is a blocking while-loop
 * because the Pi's screen display is blocking. On Android the main thread
 * must keep pumping for touch events, so the loop is event-driven: a View
 * shows its screen and returns; when the user taps, `onScreenResult` runs
 * the View's `onResult(idx)` and continues with the returned Destination.
 * Same semantics, callback-shaped.
 */
class Controller private constructor() {

    companion object {
        val instance: Controller by lazy { Controller() }
    }

    val settings = Settings()
    val storage = SeedStorage()

    // Working flow state (wiped on Home, per upstream)
    var psbt: Psbt? = null
    var psbtSeed: Seed? = null
    var resumeMainFlow: String? = null

    // Flow constants (upstream Controller.FLOW__*)
    object Flow {
        const val PSBT = "psbt"
        const val VERIFY_MULTISIG_ADDR = "multisig_addr"
        const val VERIFY_SINGLESIG_ADDR = "singlesig_addr"
        const val ADDRESS_EXPLORER = "address_explorer"
        const val SIGN_MESSAGE = "sign_message"
    }

    val backStack: MutableList<Destination> = mutableListOf()

    private var activity: Activity? = null
    private var currentView: View? = null
    private var currentScreen: Ui.Screen? = null
    private var flowFinished = false

    fun attachActivity(activity: Activity) {
        this.activity = activity
        flowFinished = false
    }

    /** Ends the flow (activity recreate / process exit). */
    fun finishFlow() {
        flowFinished = true
        currentView = null
        currentScreen = null
        backStack.clear()
    }

    /** Upstream Controller.start: splash, then the main loop. */
    fun start() {
        runDestination(Destination(OpeningSplashView::class.java))
    }

    /**
     * Run one Destination: apply back-stack bookkeeping, instantiate the
     * View, and let it show its screen. Continuation happens in
     * [onScreenResult] when the user interacts.
     */
    fun runDestination(next: Destination) {
        val act = activity ?: return

        var dest = next
        if (dest.ViewCls == null) dest = Destination(MainMenuView::class.java)

        if (dest.ViewCls == MainMenuView::class.java) {
            // Home always wipes the back stack and temp flow state
            clearBackStack()
            resumeMainFlow = null
            psbt = null
            psbtSeed = null
        }

        Log.i("Controller", "back_stack: ${backStack.map { it.ViewCls?.simpleName }}")

        if (dest.skipCurrentView && backStack.isNotEmpty()) {
            backStack.removeAt(backStack.size - 1)
        }

        val clearHistory = dest.clearHistory

        if (dest.ViewCls == BackStackView::class.java) {
            dest = popPrevFromBackStack()
            if (dest.ViewCls == null) dest = Destination(MainMenuView::class.java)
            if (clearHistory) clearBackStack()
            if (backStack.isEmpty() || backStack.last() != dest) backStack.add(dest)
            runView(act, dest)
            return
        }

        if (clearHistory) clearBackStack()

        if (backStack.isEmpty() || backStack.last() != dest) {
            Log.i("Controller", "appending ${dest.ViewCls?.simpleName}")
            backStack.add(dest)
        }

        runView(act, dest)
    }

    private fun runView(act: Activity, dest: Destination) {
        val view = try {
            dest.ViewCls!!.getDeclaredConstructor().newInstance()
        } catch (e: Exception) {
            Log.e("Controller", "failed to instantiate ${dest.ViewCls?.name}", e)
            runDestination(handleException(e))
            return
        }
        view.activity = act
        view.viewArgs = dest.viewArgs
        currentView = view

        val redirected = try {
            view.run(act)
        } catch (e: Exception) {
            Log.e("Controller", "unhandled exception in ${view::class.java.simpleName}", e)
            runDestination(handleException(e))
            return
        }

        if (redirected != null) {
            // View redirected without waiting for user input
            runDestination(redirected)
        }
    }

    /** A screen's button/back fired: continue the flow. */
    fun onScreenResult(index: Int) {
        if (flowFinished) return
        val view = currentView ?: return
        val dest = try {
            view.onResult(index)
        } catch (e: Exception) {
            Log.e("Controller", "unhandled exception in onResult", e)
            handleException(e)
        }
        currentView = null
        runDestination(dest)
    }

    /**
     * Show a screen: swap it in and wire taps to [onScreenResult].
     * Upstream equivalent: `View.run_screen(Screen_cls, **kwargs)`.
     */
    fun showScreen(screen: Ui.Screen) {
        currentScreen = screen
        activity?.setContentView(screen.root)
        screen.resultHandler = { idx ->
            currentScreen = null
            onScreenResult(idx)
        }
    }

    /** Android back gesture: same as the on-screen back arrow. */
    fun onBack() {
        currentScreen?.back()
    }

    fun clearBackStack() {
        backStack.clear()
    }

    /** Upstream pop_prev_from_back_stack: pop current, then the real "back". */
    private fun popPrevFromBackStack(): Destination {
        if (backStack.isNotEmpty()) {
            backStack.removeAt(backStack.size - 1)
            if (backStack.isNotEmpty()) {
                return backStack.removeAt(backStack.size - 1)
            }
        }
        return Destination(null)
    }

    /**
     * Upstream handle_exception: user-friendly error screen with debugging
     * info (exception type, file:line, message).
     */
    private fun handleException(e: Exception): Destination {
        return Destination(
            com.seedsigner.app.views.UnhandledExceptionView::class.java,
            viewArgs = mapOf(
                "error" to listOf(
                    e::class.java.simpleName,
                    e.stackTrace.firstOrNull()?.let { "${it.fileName}:${it.lineNumber}" } ?: "",
                    e.message ?: ""
                )
            ),
            clearHistory = true
        )
    }
}
