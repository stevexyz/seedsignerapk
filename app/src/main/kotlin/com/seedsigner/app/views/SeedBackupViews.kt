package com.seedsigner.app.views

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Paint
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Controller
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
import com.seedsigner.app.models.Bip39Seed
import com.seedsigner.app.models.ElectrumSeed
import com.seedsigner.app.models.Seed
import com.seedsigner.app.models.Settings

/**
 * 1:1 port of upstream SeedElectrumMnemonicStartView (seed_views.py:504).
 * Warning display before entering an Electrum seed, then word entry.
 */
class SeedElectrumMnemonicStartView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Electrum Warning", showBack = false)
        screen.addText("Some features are disabled for Electrum seeds.", 16f)
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        // Upstream: the screen has no buttons; any input proceeds, having
        // initialized the 12-word pending mnemonic as Electrum.
        controller.storage.initPendingMnemonic(numWords = 12, isElectrum = true)
        return Destination(SeedMnemonicEntryView::class.java,
            viewArgs = mapOf("curWordIndex" to 0, "isCalcFinalWord" to false))
    }
}

/**
 * 1:1 port of upstream SeedBackupView (seed_views.py:628): "View seed
 * words" + "Export as SeedQR" (the SeedQR part lands with the transcribe
 * flow; Electrum seeds have no SeedQR upstream either).
 */
class SeedBackupView : View() {

    private var seedIndex: Int = 0

    override fun run(activity: Activity): Destination? {
        seedIndex = (viewArgs["seedIndex"] as? Int) ?: 0
        val seed = controller.storage.seeds[seedIndex]

        val buttons = mutableListOf("View seed words")
        if (seed.seedqrSupported) {
            buttons.add("Export as SeedQR")
        }

        val screen = Ui.screen(activity, "Backup Seed")
        screen.addButtons(buttons)
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return when (index) {
            0 -> Destination(SeedWordsWarningView::class.java,
                viewArgs = mapOf("seedIndex" to seedIndex))
            else -> Destination(SeedTranscribeSeedQRFormatView::class.java,
                viewArgs = mapOf("seedIndex" to seedIndex))
        }
    }
}

/**
 * 1:1 port of upstream SeedTranscribeSeedQRFormatView (seed_views.py:1412):
 * choose the SeedQR grid size (standard vs compact, 12 vs 24 words) and go
 * to the warning screen.
 */
class SeedTranscribeSeedQRFormatView : View() {

    // Upstream STANDARD_12 / COMPACT_12 / STANDARD_24 / COMPACT_24 dims.
    private data class Dim(val label: String, val numModules: Int, val compact: Boolean)

    private var dims: List<Dim> = emptyList()
    private var seedIndex: Int = 0

    override fun run(activity: Activity): Destination? {
        seedIndex = (viewArgs["seedIndex"] as? Int) ?: 0
        val seed = controller.storage.seeds[seedIndex]

        if (settings.getValue<String>(Settings.Const.SETTING__COMPACT_SEEDQR) != Settings.Const.OPTION__ENABLED) {
            // Only configured for standard SeedQR.
            val num = if (seed.mnemonicList.size == 12) 25 else 29
            return Destination(SeedTranscribeSeedQRWarningView::class.java,
                viewArgs = mapOf("seedIndex" to seedIndex, "seedqrFormat" to false, "numModules" to num),
                skipCurrentView = true)
        }

        dims = if (seed.mnemonicList.size == 12) {
            listOf(Dim("Standard: 25x25", 25, false), Dim("Compact: 21x21", 21, true))
        } else {
            listOf(Dim("Standard: 29x29", 29, false), Dim("Compact: 25x25", 25, true))
        }

        val screen = Ui.screen(activity, "SeedQR Format")
        screen.addButtons(dims.map { it.label })
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        val d = dims[index]
        return Destination(SeedTranscribeSeedQRWarningView::class.java,
            viewArgs = mapOf("seedIndex" to seedIndex, "seedqrFormat" to d.compact, "numModules" to d.numModules))
    }
}

/**
 * 1:1 port of upstream SeedTranscribeSeedQRWarningView (seed_views.py:1478):
 * the dire "SeedQR is your private key!" warning, skippable with the
 * DIRE_WARNINGS setting.
 */
class SeedTranscribeSeedQRWarningView : View() {

    private var seedIndex: Int = 0
    private var seedqrFormat: Boolean = false
    private var numModules: Int = 25

    override fun run(activity: Activity): Destination? {
        seedIndex = (viewArgs["seedIndex"] as? Int) ?: 0
        seedqrFormat = viewArgs["seedqrFormat"] as? Boolean ?: false
        numModules = (viewArgs["numModules"] as? Int) ?: 25

        if (settings.getValue<String>(Settings.Const.SETTING__DIRE_WARNINGS) == Settings.Const.OPTION__DISABLED) {
            // Forward straight to transcribing the SeedQR.
            return Destination(SeedTranscribeSeedQRWholeQRView::class.java,
                viewArgs = viewArgs, skipCurrentView = true)
        }

        val screen = Ui.screen(activity, "SeedQR", showBack = false)
        screen.addText("SeedQR is your private key!", 18f, 0xFFCF6679.toInt())
        screen.addText("Never photograph or scan it into a device that connects to the internet.", 15f)
        screen.addButtons(listOf("I Understand"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return Destination(SeedTranscribeSeedQRWholeQRView::class.java,
            viewArgs = mapOf("seedIndex" to seedIndex, "seedqrFormat" to seedqrFormat, "numModules" to numModules),
            skipCurrentView = true)
    }
}

/**
 * SeedQR payload encoders (1:1 of encode_qr.py SeedQrEncoder /
 * CompactSeedQrEncoder): the whole grid is ONE QR of either the 4-digit
 * numeric word indices (standard) or the packed binary bits (compact).
 */
internal object SeedQrEncoder {

    /** Standard SeedQR: "00070141..." (4 digits per word). */
    fun standardSeed(mnemonic: List<String>): String {
        val wordlist = Seed.getWordlist()
        val sb = StringBuilder()
        for (word in mnemonic) {
            sb.append(wordlist.indexOf(word).toString().padStart(4, '0'))
        }
        return sb.toString()
    }

    /** Compact SeedQR: 11 bits per word, trailing checksum bits stripped. */
    fun compactSeed(mnemonic: List<String>): ByteArray {
        val wordlist = Seed.getWordlist()
        val bits = StringBuilder()
        for (word in mnemonic) {
            bits.append(wordlist.indexOf(word).toString(2).padStart(11, '0'))
        }
        // Exclude the BIP-39 checksum bits (8 for 24 words, 4 for 12).
        val stripped = when (mnemonic.size) {
            24 -> bits.substring(0, bits.length - 8)
            12 -> bits.substring(0, bits.length - 4)
            else -> bits
        }
        return ByteArray((stripped.length + 7) / 8) { i ->
            (stripped.substring(i * 8, minOf(i * 8 + 8, stripped.length))
                .let { it.padEnd(8, '0') }.toInt(2)).toByte()
        }
    }
}

/**
 * 1:1 port of upstream SeedTranscribeSeedQRWholeQRView (seed_views.py:1511):
 * display the full SeedQR grid as a single QR image to transcribe by hand;
 * "Begin NxN" -> the zoomed-in view.
 */
class SeedTranscribeSeedQRWholeQRView : View() {

    private var seedIndex: Int = 0
    private var seedqrFormat: Boolean = false
    private var numModules: Int = 25

    override fun run(activity: Activity): Destination? {
        seedIndex = (viewArgs["seedIndex"] as? Int) ?: 0
        seedqrFormat = viewArgs["seedqrFormat"] as? Boolean ?: false
        numModules = (viewArgs["numModules"] as? Int) ?: 25
        val seed = controller.storage.seeds[seedIndex]

        val data: Any = if (seedqrFormat) SeedQrEncoder.compactSeed(seed.mnemonicList)
        else SeedQrEncoder.standardSeed(seed.mnemonicList)
        val sizePx = (activity.resources.displayMetrics.density * 280).toInt()
        val bmp = if (data is String) {
            com.seedsigner.app.qr.QrDisplay.bitmap(data, sizePx)
        } else {
            com.seedsigner.app.qr.QrDisplay.bitmapBytes(data as ByteArray, sizePx)
        }

        val screen = Ui.screen(activity, "Transcribe SeedQR", showBack = false)
        screen.addImage(bmp)
        screen.addButtons(listOf("Begin ${numModules}x$numModules"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return Destination(SeedTranscribeSeedQRZoomedInView::class.java,
            viewArgs = mapOf("seedIndex" to seedIndex, "seedqrFormat" to seedqrFormat))
    }
}

/**
 * 1:1 port of upstream SeedTranscribeSeedQRZoomedInView (seed_views.py:1545):
 * shows the oversized, zone-guidelined SeedQR for careful hand transcription,
 * then moves on to the confirm step.
 */
class SeedTranscribeSeedQRZoomedInView : View() {

    private var seedIndex: Int = 0
    private var seedqrFormat: Boolean = false

    override fun run(activity: Activity): Destination? {
        seedIndex = (viewArgs["seedIndex"] as? Int) ?: 0
        seedqrFormat = viewArgs["seedqrFormat"] as? Boolean ?: false
        val seed = controller.storage.seeds[seedIndex]

        // Upstream module counts for the zoomed-in rendering.
        val numModules = when {
            seed.mnemonicList.size == 24 && seedqrFormat -> 25
            seed.mnemonicList.size == 24 -> 29
            seedqrFormat -> 21
            else -> 25
        }
        val modulesPerZone = if (numModules == 21) 7 else 5
        val pixelsPerModule = 24
        val borderModules = 4
        val totalPx = (borderModules + numModules + borderModules) * pixelsPerModule

        val data: Any = if (seedqrFormat) SeedQrEncoder.compactSeed(seed.mnemonicList)
        else SeedQrEncoder.standardSeed(seed.mnemonicList)
        val base = if (data is String) {
            com.seedsigner.app.qr.QrDisplay.bitmap(data, totalPx)
        } else {
            com.seedsigner.app.qr.QrDisplay.bitmapBytes(data as ByteArray, totalPx)
        }

        // Zone guideline grid (upstream draws #bbb lines between modules,
        // skipping the outer QR border) plus a heavier line every zone.
        val canvas = base.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint()
        val offset = borderModules * pixelsPerModule
        for (i in borderModules until (numModules + borderModules)) {
            paint.color = 0xFFBBBBBB.toInt()
            paint.strokeWidth = 1f
            val v = offset + (i - borderModules) * pixelsPerModule
            drawVLine(canvas, v, offset, totalPx - offset, paint)
            drawHLine(canvas, offset, totalPx - offset, v, paint)
        }
        for (i in (borderModules until (numModules + borderModules)).step(modulesPerZone)) {
            paint.color = 0xFF888888.toInt()
            paint.strokeWidth = 3f
            val v = offset + (i - borderModules) * pixelsPerModule
            drawVLine(canvas, v, offset, totalPx - offset, paint)
            drawHLine(canvas, offset, totalPx - offset, v, paint)
        }

        // Scale to the display: a big central zone (5x5 modules) plus its
        // 3-module neighborhood = 11 modules across, centered.
        val viewModules = modulesPerZone + 6
        val scaled = Bitmap.createScaledBitmap(canvas,
            (totalPx * 5.0f / viewModules).toInt().coerceAtMost(2200),
            (totalPx * 5.0f / viewModules).toInt().coerceAtMost(2200), true)

        val screen = Ui.screen(activity, "Transcribe SeedQR", showBack = false)
        screen.addImage(scaled)
        screen.addText("${numModules}x$numModules SeedQR; transcribe all ${seed.mnemonicList.size} words.", 13f)
        screen.addButtons(listOf("Done"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return Destination(SeedTranscribeSeedQRConfirmQRPromptView::class.java,
            viewArgs = mapOf("seedIndex" to seedIndex))
    }
}

/**
 * 1:1 port of upstream SeedTranscribeSeedQRConfirmQRPromptView
 * (seed_views.py:1585): "Confirm SeedQR" (scan what you transcribed) or
 * "Done".
 */
class SeedTranscribeSeedQRConfirmQRPromptView : View() {

    private var seedIndex: Int = 0

    override fun run(activity: Activity): Destination? {
        seedIndex = (viewArgs["seedIndex"] as? Int) ?: 0
        val screen = Ui.screen(activity, "Confirm SeedQR?")
        screen.addButtons(listOf("Confirm SeedQR", "Done"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return when (index) {
            0 -> Destination(SeedTranscribeSeedQRConfirmScanView::class.java,
                viewArgs = mapOf("seedIndex" to seedIndex))
            else -> Destination(SeedOptionsView::class.java,
                viewArgs = mapOf("seedIndex" to seedIndex), clearHistory = true)
        }
    }
}

/**
 * 1:1 port of upstream SeedTranscribeSeedQRConfirmScanView
 * (seed_views.py:1608): scan the hand-transcribed SeedQR back and compare
 * the recovered seed phrase to the original.
 */
class SeedTranscribeSeedQRConfirmScanView : ScanView() {

    override fun onResult(index: Int): Destination {
        stopScanner()
        val ur = decodedUr ?: return Destination(BackStackView::class.java)
        val seedIndex = (viewArgs["seedIndex"] as? Int) ?: 0
        val seed = controller.storage.seeds[seedIndex]
        val raw = String(ur.cbor, Charsets.UTF_8).trim()

        // Upstream SeedQrDecoder types: numeric SeedQR (12/24 words of 4
        // digits), compact binary bytes, or a plain mnemonic string.
        // Our scanner labels every non-UR QR "raw" (upstream distinguishes
        // SEED__SEEDQR from SEED__COMPACTSEEDQR at the protocol level), so
        // the compact path tries both: text first, then the raw bytes as
        // compact-SeedQR entropy (16/32 bytes -> 12/24 words, checksum
        // recomputed — exactly upstream's bip39.mnemonic_from_bytes).
        val recovered: List<String>? = SeedQrDecoderKt.decodeSeedQr(raw)
            ?: SeedQrDecoderKt.decodeCompactSeedQr(ur.cbor)
        if (recovered != null) {
            return if (recovered != seed.mnemonicList) {
                Destination(SeedTranscribeSeedQRConfirmWrongSeedView::class.java,
                    skipCurrentView = true)
            } else {
                Destination(SeedTranscribeSeedQRConfirmSuccessView::class.java,
                    viewArgs = mapOf("seedIndex" to seedIndex))
            }
        }
        return Destination(SeedTranscribeSeedQRConfirmInvalidQRView::class.java,
            skipCurrentView = true)
    }
}

/** Upstream SeedTranscribeSeedQRConfirmWrongSeedView (dire warning). */
class SeedTranscribeSeedQRConfirmWrongSeedView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Confirm SeedQR", showBack = false)
        screen.addText("Error!", 20f, 0xFFCF6679.toInt())
        screen.addText("Your transcribed SeedQR does not match your original seed!", 15f)
        screen.addButtons(listOf("Review SeedQR"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        // Skip BACK to the zoomed in transcription view.
        return Destination(BackStackView::class.java, skipCurrentView = true)
    }
}

/** Upstream SeedTranscribeSeedQRConfirmSuccessView. */
class SeedTranscribeSeedQRConfirmSuccessView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Confirm SeedQR", showBack = false)
        screen.addText("Success!", 20f, 0xFF81C784.toInt())
        screen.addText("Your transcribed SeedQR successfully scanned and yielded the same seed.", 15f)
        screen.addButtons(listOf("OK"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        val seedIndex = (viewArgs["seedIndex"] as? Int) ?: 0
        return Destination(SeedOptionsView::class.java, viewArgs = mapOf("seedIndex" to seedIndex))
    }
}

/** Upstream SeedTranscribeSeedQRConfirmInvalidQRView. */
class SeedTranscribeSeedQRConfirmInvalidQRView : View() {

    override fun run(activity: Activity): Destination? {
        val screen = Ui.screen(activity, "Confirm SeedQR", showBack = false)
        screen.addText("The scanned QR is not a SeedQR.", 15f)
        screen.addButtons(listOf("Try Again", "Back"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination {
        if (index == Ui.RET_CODE_BACK) return Destination(BackStackView::class.java)
        return if (index == 0) {
            Destination(SeedTranscribeSeedQRConfirmScanView::class.java,
                viewArgs = mapOf("seedIndex" to ((viewArgs["seedIndex"] as? Int) ?: 0)))
        } else {
            Destination(BackStackView::class.java)
        }
    }
}

/**
 * SeedQR decoding (1:1 of decode_qr.py SeedQrDecoder.add): returns the
 * recovered mnemonic, or null if the data is not a SeedQR.
 */
internal object SeedQrDecoderKt {

    fun decodeSeedQr(raw: String): List<String>? = try {
        val wordlist = com.seedsigner.app.models.Seed.getWordlist()
        when {
            // SeedQR numeric: 48 or 96 digits of word indices.
            raw.length == 48 || raw.length == 96 -> {
                if (raw.all { it.isDigit() }) {
                    val words = (0 until raw.length / 4).map { i ->
                        wordlist[raw.substring(i * 4, i * 4 + 4).toInt()]
                    }
                    if (words.size in listOf(12, 24)) words else null
                } else null
            }
            // Plain 12/24-word mnemonic string.
            raw.split(" ").size in listOf(12, 24) -> {
                val words = raw.trim().split(" ")
                if (words.all { it in wordlist }) words else null
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Compact SeedQR (byte mode) -> mnemonic. The grid holds the 11-bit
     * word indices minus the trailing checksum bits: 128 bits (16 bytes)
     * for 12 words, 256 bits (32 bytes) for 24 words — i.e. exactly the
     * BIP-39 entropy, so the checksum word is recomputed on the way back
     * (upstream: bip39.mnemonic_from_bytes). Returns null when the payload
     * is not a compact SeedQR. A corrupted read yields a wrong/invalid
     * mnemonic — a safe failure, the confirm step compares against the
     * known seed.
     */
    fun decodeCompactSeedQr(bytes: ByteArray): List<String>? {
        if (bytes.size != 16 && bytes.size != 32) return null
        return try {
            com.seedsigner.core.crypto.Bip39.mnemonicFromEntropy(bytes).split(" ")
        } catch (e: Exception) {
            null
        }
    }
}

private fun drawVLine(canvas: Bitmap, x: Int, y0: Int, y1: Int, paint: Paint) {
    val width = maxOf(1, paint.strokeWidth.toInt())
    for (yy in y0 until y1) {
        for (dx in 0 until width) {
            val xx = x + dx
            if (xx in 0 until canvas.width) canvas.setPixel(xx, yy, paint.color)
        }
    }
}

private fun drawHLine(canvas: Bitmap, x0: Int, x1: Int, y: Int, paint: Paint) {
    val height = maxOf(1, paint.strokeWidth.toInt())
    for (xx in x0 until x1) {
        for (dy in 0 until height) {
            val yy = y + dy
            if (yy in 0 until canvas.height) canvas.setPixel(xx, yy, paint.color)
        }
    }
}
