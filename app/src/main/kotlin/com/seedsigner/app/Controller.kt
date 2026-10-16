package com.seedsigner.app

import android.app.Activity
import android.util.Log
import com.seedsigner.app.views.MainMenuView
import com.seedsigner.app.views.OpeningSplashView
import com.seedsigner.core.descriptor.Descriptor
import com.seedsigner.core.psbt.Psbt
import com.seedsigner.core.psbt.PsbtParser
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
    var psbtParser: PsbtParser? = null
    // v0.3: the user-supplied multisig wallet descriptor; null until scanned.
    var multisigWalletDescriptor: Descriptor? = null

    // Image-entropy working state (upstream controller attrs): the pooled
    // preview frames (packed Y planes) and the final still capture.
    // Wiped as soon as the mnemonic is generated and on Home.
    var imageEntropyPreviewFrames: List<ByteArray>? = null
    var imageEntropyFinalImage: ByteArray? = null

    // Message-signing working state (upstream controller.sign_message_data):
    // derivation_path, message, addr_format, seed, paged_message.
    var signMessageData: MutableMap<String, Any?>? = null

    // Address-verification working state (upstream controller.unverified_address):
    // address, script_type, network, sig_type, derivation_path, verified_index.
    var unverifiedAddress: MutableMap<String, Any?>? = null
    var addressVerifySeed: Seed? = null

    // Address explorer working state (upstream controller.address_explorer_data):
    // seedIndex, network, script_type, derivation_path, xpub, cached address
    // lists ("receive_addrs" / "change_addrs").
    var addressExplorerData: MutableMap<String, Any?>? = null

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
            psbtParser = null
            multisigWalletDescriptor = null
            imageEntropyPreviewFrames = null
            imageEntropyFinalImage = null
            signMessageData = null
            unverifiedAddress = null
            addressVerifySeed = null
            addressExplorerData = null
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
            instantiateView(dest)
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

    /**
     * Runtime CAMERA permission (manifest declares it; Android 6+ still
     * asks at first use). [cb] gets the grant result on the main thread.
     */
    fun requestCameraPermission(cb: (Boolean) -> Unit) {
        pendingPermission = cb
        activity?.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1)
    }

    /** MainActivity.onRequestPermissionsResult forwarder. */
    fun onPermissionResult(granted: Boolean) {
        pendingPermission?.invoke(granted)
        pendingPermission = null
    }

    private var pendingPermission: ((Boolean) -> Unit)? = null

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
     * Instantiate a View from its Destination. Mirrors upstream's
     * `View_cls(**view_args)` (Python kwargs: every named parameter not
     * present in view_args keeps its default).
     *
     * Constructor reality (Kotlin 1.9, -java-parameters, see
     * ViewInstantiationTest): a View compiles to
     *   (1) a no-arg constructor,
     *   (2) the real N-param constructor with real parameter names, and
     *   (3) synthetic defaulting bridges whose names are "arg0", "arg1",...
     * Rule: no viewArgs -> (1). Otherwise, when every viewArgs key names a
     * parameter of (2), use (2) — present keys are bound, absent keys take
     * the parameter defaults. Otherwise fall back to (1): the view reads
     * the `viewArgs` field directly (assigned by [runView] right after).
     * The "at most one named constructor per View" invariant that makes this
     * unambiguous is enforced by ViewInstantiationTest.
     */
    private fun instantiateView(dest: Destination): View {
        val cls = dest.ViewCls!!
        val args = dest.viewArgs
        // The real constructor: non-synthetic, real parameter names
        // (synthetic defaulting bridges compile to arg0/arg1/... or $names).
        val named = cls.declaredConstructors.firstOrNull { c ->
            c.parameters.isNotEmpty() &&
                c.parameters.all { p ->
                    p.name.isNotEmpty() && !p.name.startsWith("$") && !p.name.startsWith("arg")
                }
        }
        val ctor = if (args.isNotEmpty() && named != null &&
            named.parameters.all { it.name in args || !it.type.isPrimitive }) {
            named
        } else {
            cls.getDeclaredConstructor()
        }
        ctor.isAccessible = true
        return if (ctor.parameters.isEmpty()) {
            ctor.newInstance() as View
        } else {
            // Present keys bind; absent keys arrive as null. Absent keys are
            // only tolerated for reference-type parameters (nullable Kotlin
            // params, whose defaults are null anyway, e.g. `seed: Seed? =
            // null`); non-null Int/Boolean params are primitives and are
            // rejected up front, so no non-null default is ever clobbered.
            ctor.newInstance(*ctor.parameters.map { args[it.name] }.toTypedArray()) as View
        }
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
