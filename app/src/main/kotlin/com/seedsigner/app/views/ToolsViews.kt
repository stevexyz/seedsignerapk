package com.seedsigner.app.views

import android.app.Activity
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
import com.seedsigner.app.models.Bip39Seed
import com.seedsigner.app.models.Seed
import com.seedsigner.app.models.Settings
import com.seedsigner.core.crypto.Bip39
import com.seedsigner.core.crypto.MnemonicGeneration

/**
 * 1:1 port of the upstream Tools menu seed-creation flows (tools_views.py):
 * new seed via dice rolls, and "Calc 12th/24th word" (coin-flip / word /
 * zero entropy for the final word). The image-entropy flow lands in the
 * next v0.2 chunk; address explorer / verification are v0.3.
 */

/** Upstream ToolsMenuView. */
class ToolsMenuView : View() {

    override fun run(activity: Activity): Destination? {
        val buttons = listOf(
            "New seed (camera)",
            "New seed (dice)",
            "Calc 12th/24th word",
            "Address explorer",
            "Verify address"
        )
        val screen = Ui.screen(activity, "Tools")
        screen.addButtons(buttons)
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return when (index) {
            0 -> Destination(ToolsImageEntropyLivePreviewView::class.java)
            1 -> Destination(ToolsDiceEntropyMnemonicLengthView::class.java)
            2 -> Destination(ToolsCalcFinalWordNumWordsView::class.java)
            3 -> Destination(ToolsAddressExplorerSelectSourceView::class.java)
            4 -> Destination(ScanAddressView::class.java)
            // v0.3 features
            else -> Destination(NotYetImplementedView::class.java, viewArgs = mapOf("feature" to "this tool"))
        }
    }
}

/**
 * Upstream ToolsImageEntropyLivePreviewView: collect a pool of distinct,
 * non-flat live frames (PREVIEW_POOL_SIZE = 50) as entropy volume. The pool
 * rules mirror the upstream NIST SP 800-90B-style health checks: a single
 * flat-color frame is rejected, and a frame identical (sha256) to an already
 * admitted one is rejected.
 *
 * Platform notes (vs upstream): frames are packed Y planes of the camera2
 * stream (upstream: RGB of the Pi stream) — same sensor data, entropy role
 * unchanged; the on-screen preview is the raw TextureView (upstream draws
 * its own preview).
 */
class ToolsImageEntropyLivePreviewView : View() {

    companion object {
        const val PREVIEW_POOL_SIZE = 50
    }

    private var scanner: com.seedsigner.app.camera.CameraScanner? = null
    private val pool = mutableListOf<ByteArray>()
    private val seenHashes = mutableSetOf<ByteArray>()
    private var status: android.widget.TextView? = null
    private var done = false

    override fun run(activity: Activity): Destination? {
        if (activity.checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            controller.requestCameraPermission { granted ->
                if (granted) run(activity) else controller.onScreenResult(Ui.RET_CODE_BACK)
            }
            return null
        }

        controller.imageEntropyPreviewFrames = null

        val screen = Ui.screen(activity, "Image entropy")
        val preview = android.view.TextureView(activity)
        screen.body.addView(preview, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        status = screen.addText("0 / $PREVIEW_POOL_SIZE", 18f)
        screen.addButtons(listOf("Cancel"))
        controller.showScreen(screen)

        val cam = com.seedsigner.app.camera.CameraScanner(activity, preview) { }
        scanner = cam
        cam.setEntropyFrameListener { frame ->
            handleFrame(frame)
        }
        preview.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: android.graphics.SurfaceTexture, w: Int, h: Int) { cam.start() }
            override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {}
        }
        if (preview.isAvailable) cam.start()
        return null
    }

    private fun handleFrame(frame: ByteArray) {
        if (done) return
        // flat-color check: a single shade carries no entropy
        var min = frame[0].toInt(); var max = frame[0].toInt()
        for (b in frame) {
            val v = b.toInt()
            if (v < min) min = v
            if (v > max) max = v
        }
        if (min == max) return

        val hash = com.seedsigner.core.crypto.Hashes.sha256(frame)
        if (hash in seenHashes) return
        seenHashes.add(hash)
        pool.add(frame)

        activity.runOnUiThread {
            status?.text = "${pool.size} / $PREVIEW_POOL_SIZE"
            if (pool.size == PREVIEW_POOL_SIZE) {
                done = true
                controller.imageEntropyPreviewFrames = pool.toList()
                stopScanner()
                controller.onScreenResult(0)
            }
        }
    }

    private fun stopScanner() {
        scanner?.setEntropyFrameListener(null)
        scanner?.stop()
        scanner = null
    }

    override fun onResult(index: Int): Destination {
        stopScanner()
        if (index == Ui.RET_CODE_BACK) {
            controller.imageEntropyPreviewFrames = null
            return Destination(BackStackView::class.java)
        }
        // The pool must be full; anything else is a collection failure.
        if (controller.imageEntropyPreviewFrames?.size != PREVIEW_POOL_SIZE) {
            throw Exception("Entropy collection failed. Expected $PREVIEW_POOL_SIZE preview frames, got ${controller.imageEntropyPreviewFrames?.size ?: 0}")
        }
        return Destination(ToolsImageEntropyFinalImageView::class.java)
    }
}

/**
 * Upstream ToolsImageEntropyFinalImageView: take the final full-res still
 * and show it (upstream boosts contrast for presentation; the raw frame is
 * shown here — the boosted copy never affects the entropy).
 */
class ToolsImageEntropyFinalImageView : View() {

    override fun run(activity: Activity): Destination? {
        if (controller.imageEntropyFinalImage != null) {
            val y = controller.imageEntropyFinalImage!!
            val screen = Ui.screen(activity, "Final image")
            val image = android.widget.ImageView(activity)
            image.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            image.setImageBitmap(yToBitmap(y, 1600))
            screen.body.addView(image, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { gravity = android.view.Gravity.CENTER })
            screen.addButtons(listOf("Retake", "Use this image"))
            controller.showScreen(screen)
            return null
        }

        // No still yet: open the camera, let exposure settle, take one shot.
        val screen = Ui.screen(activity, "Take the final image")
        val preview = android.view.TextureView(activity)
        screen.body.addView(preview, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        screen.addText("Capturing...")
        controller.showScreen(screen)

        val cam = com.seedsigner.app.camera.CameraScanner(activity, preview) { }
        val takeShot = {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                cam.captureStill { y ->
                    controller.imageEntropyFinalImage = y
                    cam.stop()
                    run(activity)
                }
            }, 800L) // let the camera session + exposure settle
        }
        preview.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: android.graphics.SurfaceTexture, w: Int, h: Int) {
                cam.start()
                takeShot()
            }
            override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {}
        }
        if (preview.isAvailable) {
            cam.start()
            takeShot()
        }
        return null
    }

    private fun yToBitmap(y: ByteArray, size: Int): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        for (i in pixels.indices) pixels[i] = 0xFF000000.toInt() or (y[i].toInt() and 0xFF)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) {
            // Reshoot (upstream: back to the live preview and reshoot).
            controller.imageEntropyFinalImage = null
            return Destination(BackStackView::class.java)
        }
        return if (index == 0) {
            controller.imageEntropyFinalImage = null
            Destination(ToolsImageEntropyLivePreviewView::class.java)
        } else {
            Destination(ToolsImageEntropyMnemonicLengthView::class.java)
        }
    }
}

/**
 * Upstream ToolsImageEntropyMnemonicLengthView: 12/24 words, then the
 * entropy chain. Upstream seeds the chain with the CPU serial; Android
 * exposes no equivalent without privileged access, so ANDROID_ID (no
 * permission needed, per-device per-app) stands in — documented deviation,
 * same role (hardware-level uniqueness).
 */
class ToolsImageEntropyMnemonicLengthView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Mnemonic Length")
        screen.addButtons(listOf("12 words", "24 words"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val mnemonicLength = if (index == 0) 12 else 24

        var hash: ByteArray
        try {
            val id = android.provider.Settings.Secure.getString(
                activity.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
            hash = com.seedsigner.core.crypto.Hashes.sha256(id.toByteArray())
        } catch (e: Exception) {
            hash = com.seedsigner.core.crypto.Hashes.sha256("0".toByteArray())
        }

        // Modest entropy via current time (upstream: millis since power on).
        hash = com.seedsigner.core.crypto.Hashes.sha256(hash + System.currentTimeMillis().toString().toByteArray())

        // Chain the preview frames.
        for (frame in controller.imageEntropyPreviewFrames ?: emptyList()) {
            hash = com.seedsigner.core.crypto.Hashes.sha256(hash + frame)
        }

        // The headline entropy: the full-res still.
        hash = com.seedsigner.core.crypto.Hashes.sha256(hash + (controller.imageEntropyFinalImage ?: ByteArray(0)))
        if (mnemonicLength == 12) {
            hash = hash.copyOfRange(0, 16)
        }

        val mnemonic = MnemonicGeneration.generateMnemonicFromBytes(hash)

        // The images should never stick around in memory.
        controller.imageEntropyPreviewFrames = null
        controller.imageEntropyFinalImage = null

        controller.storage.setPendingSeed(Bip39Seed(mnemonic = mnemonic))
        return Destination(SeedWordsWarningView::class.java, viewArgs = mapOf("seed" to null, "bip85ChildIndex" to null, "bip85NumWords" to null), clearHistory = true)
    }
}

/** Upstream ToolsDiceEntropyMnemonicLengthView. */
class ToolsDiceEntropyMnemonicLengthView : View() {

    override fun run(activity: Activity): Destination? {
        val buttons = listOf(
            "12 words (${MnemonicGeneration.DICE__NUM_ROLLS__12WORD} rolls)",
            "24 words (${MnemonicGeneration.DICE__NUM_ROLLS__24WORD} rolls)"
        )
        val screen = Ui.screen(activity, "Mnemonic Length")
        screen.addButtons(buttons)
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val totalRolls = if (index == 0) MnemonicGeneration.DICE__NUM_ROLLS__12WORD
                         else MnemonicGeneration.DICE__NUM_ROLLS__24WORD
        return Destination(ToolsDiceEntropyEntryView::class.java, viewArgs = mapOf("totalRolls" to totalRolls))
    }
}

/**
 * Upstream ToolsDiceEntropyEntryView: enter the rolls one digit at a time
 * (upstream: physical 1-6 keys; here: an on-screen 3x2 grid). After
 * `totalRolls` digits the mnemonic is generated straight from the digit
 * string (SHA256 of the string, iancoleman "Base 10" mode).
 *
 * The entry pad is self-contained (taps update the screen in place, like the
 * mnemonic keyboard); the View only routes on completion or cancel.
 */
class ToolsDiceEntropyEntryView : View() {

    private var totalRolls: Int = 0
    private var rolls = ""
    private var status: android.widget.TextView? = null

    override fun run(activity: Activity): Destination? {
        totalRolls = viewArgs["totalRolls"] as Int

        val screen = Ui.screen(activity, "Roll the dice")
        status = screen.addText("0 / $totalRolls", 18f)

        val density = activity.resources.displayMetrics.density
        val grid = GridLayout(activity).apply { columnCount = 3 }
        fun padButton(label: String, action: () -> Unit) {
            val b = Button(activity)
            b.text = label
            b.isAllCaps = false
            b.textSize = 20f
            b.setOnClickListener { action() }
            val lp = GridLayout.LayoutParams()
            lp.width = (density * 90).toInt()
            lp.height = (density * 56).toInt()
            lp.setMargins((density * 6).toInt(), (density * 6).toInt(), (density * 6).toInt(), (density * 6).toInt())
            grid.addView(b, lp)
        }
        for (digit in 1..6) {
            padButton(digit.toString()) {
                if (rolls.length < totalRolls) {
                    rolls += digit
                    status?.text = "${rolls.length} / $totalRolls  [$rolls]"
                    if (rolls.length == totalRolls) controller.onScreenResult(0)
                }
            }
        }
        padButton("⌫") {
            if (rolls.isNotEmpty()) {
                rolls = rolls.substring(0, rolls.length - 1)
                status?.text = "${rolls.length} / $totalRolls  [$rolls]"
            }
        }
        padButton("Cancel") { controller.onScreenResult(Ui.RET_CODE_BACK) }
        screen.body.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.CENTER })

        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        // Completed: generate the seed from the digit string.
        val mnemonic = MnemonicGeneration.generateMnemonicFromDice(rolls)
        controller.storage.setPendingSeed(Bip39Seed(mnemonic = mnemonic))
        return Destination(SeedWordsWarningView::class.java, viewArgs = mapOf("seed" to null, "bip85ChildIndex" to null, "bip85NumWords" to null), clearHistory = true)
    }
}

/** Upstream ToolsCalcFinalWordNumWordsView. */
class ToolsCalcFinalWordNumWordsView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Mnemonic Length")
        screen.addButtons(listOf("12 words", "24 words"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val numWords = if (index == 0) 12 else 24
        controller.storage.initPendingMnemonic(numWords = numWords)
        return Destination(SeedMnemonicEntryView::class.java, viewArgs = mapOf("curWordIndex" to 0, "isCalcFinalWord" to true))
    }
}

/** Upstream ToolsCalcFinalWordFinalizePromptView. */
class ToolsCalcFinalWordFinalizePromptView : View() {

    override fun run(activity: Activity): Destination? {
        val mnemonicLength = controller.storage.pendingMnemonicLength
        val numEntropyBits = if (mnemonicLength == 12) 7 else 3
        val screen = Ui.screen(activity, "Finalize the seed")
        screen.addText("The final word needs $numEntropyBits bits of entropy:", 15f)
        screen.addButtons(listOf("Coin flip entropy", "Word selection entropy", "Finalize with zeros"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val mnemonicLength = controller.storage.pendingMnemonicLength
        return when (index) {
            0 -> Destination(ToolsCalcFinalWordCoinFlipsView::class.java)
            1 -> {
                // Clear the final word slot, just in case we're returning via BACK
                controller.storage.updatePendingMnemonic(null, mnemonicLength - 1)
                Destination(SeedMnemonicEntryView::class.java,
                    viewArgs = mapOf("isCalcFinalWord" to true, "curWordIndex" to (mnemonicLength - 1)))
            }
            else -> {
                // All-zeros entropy; piggy-back on the coin-flips path.
                val numEntropyBits = if (mnemonicLength == 12) 7 else 3
                controller.storage.updatePendingMnemonic(Bip39.WORDLIST[0], mnemonicLength - 1)
                Destination(ToolsCalcFinalWordShowFinalWordView::class.java,
                    viewArgs = mapOf("coinFlips" to "0".repeat(numEntropyBits)))
            }
        }
    }
}

/**
 * Upstream ToolsCalcFinalWordCoinFlipsView: 7 or 3 coin flips. The pad is
 * self-contained (like the dice pad); the View routes on completion/cancel.
 */
class ToolsCalcFinalWordCoinFlipsView : View() {

    private var flips = ""
    private var status: android.widget.TextView? = null
    private val totalFlips: Int get() = if (controller.storage.pendingMnemonicLength == 12) 7 else 3

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Coin flips")
        screen.addText("Toss a coin $totalFlips times: heads=1, tails=0", 14f)
        status = screen.addText("[  ]", 22f)

        val density = activity.resources.displayMetrics.density
        val grid = GridLayout(activity).apply { columnCount = 2 }
        fun padButton(label: String, action: () -> Unit) {
            val b = Button(activity)
            b.text = label
            b.isAllCaps = false
            b.textSize = 20f
            b.setOnClickListener { action() }
            val lp = GridLayout.LayoutParams()
            lp.width = (density * 120).toInt()
            lp.height = (density * 56).toInt()
            lp.setMargins((density * 6).toInt(), (density * 6).toInt(), (density * 6).toInt(), (density * 6).toInt())
            grid.addView(b, lp)
        }
        padButton("0 (tails)") {
            if (flips.length < totalFlips) {
                flips += "0"
                status?.text = "[ $flips ]"
                if (flips.length == totalFlips) controller.onScreenResult(0)
            }
        }
        padButton("1 (heads)") {
            if (flips.length < totalFlips) {
                flips += "1"
                status?.text = "[ $flips ]"
                if (flips.length == totalFlips) controller.onScreenResult(0)
            }
        }
        padButton("⌫") {
            if (flips.isNotEmpty()) {
                flips = flips.substring(0, flips.length - 1)
                status?.text = "[ $flips ]"
            }
        }
        padButton("Cancel") { controller.onScreenResult(Ui.RET_CODE_BACK) }
        screen.body.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.CENTER })

        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return Destination(ToolsCalcFinalWordShowFinalWordView::class.java,
            viewArgs = mapOf("coinFlips" to flips))
    }
}

/**
 * Upstream ToolsCalcFinalWordShowFinalWordView. Upstream does the storage
 * work in __init__; here in run() (same ordering: before the screen shows).
 */
class ToolsCalcFinalWordShowFinalWordView : View() {

    private var selectedFinalWord: String? = null
    private var selectedFinalBits = ""
    private var actualFinalWord = ""
    private var checksumBits = ""

    override fun run(activity: Activity): Destination? {
        val coinFlips = viewArgs["coinFlips"] as? String
        val wordlist = Bip39.WORDLIST
        val storage = controller.storage

        if (coinFlips != null) {
            selectedFinalWord = null
            selectedFinalBits = coinFlips
            // The pre-checksum word for these flips fills the final slot.
            storage.updatePendingMnemonic(MnemonicGeneration.getPartialFinalWord(coinFlips), storage.pendingMnemonicLength - 1)
        } else {
            selectedFinalWord = storage.pendingMnemonic[storage.pendingMnemonicLength - 1]
            selectedFinalBits = wordlist.indexOf(selectedFinalWord!!).toString(2).padStart(11, '0')
        }

        // The REAL final word with a proper checksum.
        val finalMnemonic = MnemonicGeneration.calculateChecksum(storage.pendingMnemonic.map { it ?: "" })
        storage.updatePendingMnemonic(finalMnemonic.last(), storage.pendingMnemonicLength - 1)

        actualFinalWord = finalMnemonic.last()
        val numChecksumBits = if (storage.pendingMnemonicLength == 12) 4 else 8
        val finalBits = wordlist.indexOf(actualFinalWord).toString(2).padStart(11, '0')
        checksumBits = finalBits.substring(finalBits.length - numChecksumBits)

        val screen = Ui.screen(activity, "Final Word Calc")
        if (selectedFinalWord != null) {
            screen.addText("Your word: $selectedFinalWord", 18f)
            screen.addText("bits: $selectedFinalBits", 14f, 0xFF9E9E9E.toInt())
        } else {
            screen.addText("Your flips: $selectedFinalBits", 18f)
            screen.addText("pre-checksum word: ${MnemonicGeneration.getPartialFinalWord(selectedFinalBits)}", 14f, 0xFF9E9E9E.toInt())
        }
        screen.addText("checksum bits: $checksumBits", 14f, 0xFF9E9E9E.toInt())
        screen.addText("Final word: $actualFinalWord", 22f, 0xFF81C784.toInt())
        screen.addButtons(listOf("Next"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return Destination(ToolsCalcFinalWordDoneView::class.java)
    }
}

/** Upstream ToolsCalcFinalWordDoneView. */
class ToolsCalcFinalWordDoneView : View() {

    override fun run(activity: Activity): Destination? {
        val mnemonic = controller.storage.pendingMnemonic.map { it ?: "" }
        val network = settings.getValue<String>(Settings.Const.SETTING__NETWORK)
        val screen = Ui.screen(activity, "Calc Final Word")
        screen.addText("Final word: ${mnemonic.last()}", 20f, 0xFF81C784.toInt())
        screen.addText(controller.storage.getPendingMnemonicFingerprint(network)?.let { "Fingerprint: $it" } ?: "", 14f, 0xFF9E9E9E.toInt())
        screen.addButtons(listOf("Load seed", "Discard"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        // Upstream: LOAD -> SeedFinalizeView; DISCARD -> SeedDiscardView
        // (pending-seed flavor).
        return if (index == 0) {
            controller.storage.convertPendingMnemonicToPendingSeed()
            Destination(SeedFinalizeView::class.java)
        } else {
            Destination(SeedDiscardView::class.java)
        }
    }
}
