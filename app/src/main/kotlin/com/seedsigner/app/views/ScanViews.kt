package com.seedsigner.app.views

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.TextureView
import android.widget.FrameLayout
import com.seedsigner.app.BackStackView
import com.seedsigner.app.Controller
import com.seedsigner.app.Destination
import com.seedsigner.app.Ui
import com.seedsigner.app.View
import com.seedsigner.app.camera.CameraScanner
import com.seedsigner.core.qr.BbqrDecoder
import com.seedsigner.core.qr.SegmentStatus
import com.seedsigner.core.qr.SpecterPsbtDecoder
import com.seedsigner.core.qr.SplitQrException
import com.seedsigner.core.qr.ur2.UR
import com.seedsigner.core.qr.ur2.URDecoder

/**
 * Upstream ScanView (scan_views.py): camera up, decode QRs, accumulate UR
 * parts until the payload is complete.
 */
open class ScanView : View() {

    private var scanner: CameraScanner? = null
    private val decoder = URDecoder()
    private val seenParts = mutableSetOf<String>()
    protected var decodedUr: UR? = null

    override fun run(activity: Activity): Destination? {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            controller.requestCameraPermission { granted ->
                // The dialog can outlive this screen (user backed out); a
                // stale grant/deny must not route into the new current view.
                if (controller.currentView !== this) return@requestCameraPermission
                if (granted) run(activity) else controller.onScreenResult(Ui.RET_CODE_BACK)
            }
            return null
        }

        val screen = Ui.screen(activity, "Scan")

        val preview = TextureView(activity)
        screen.body.addView(preview, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        screen.addText("Point the camera at the QR code")
        screen.addButtons(listOf("Cancel"))
        controller.showScreen(screen)

        val cam = CameraScanner(activity, preview) { bytes ->
            handleQr(activity, bytes)
        }
        scanner = cam

        // start the camera only once the preview surface exists; stop it
        // when the surface goes away (e.g. activity destroy/rotation) so the
        // camera session is never leaked or double-opened
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: android.graphics.SurfaceTexture, w: Int, h: Int) {
                cam.start()
            }
            override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture): Boolean {
                cam.stop()
                return true
            }
            override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {}
        }
        if (preview.isAvailable) cam.start()
        return null
    }

    private fun handleQr(activity: Activity, bytes: ByteArray) {
        // ISO-8859-1 is a lossless byte<->String bridge: keys stay
        // byte-unique, and ASCII UR part strings read identically
        val text = String(bytes, Charsets.ISO_8859_1)
        if (text in seenParts) return
        seenParts.add(text)

        val ur: UR? = if (text.startsWith("ur:")) {
            // single-part, or one part of a multi-part sequence
            if (decoder.receivePart(text) && decoder.isComplete() && !decoder.isFailure()) {
                decoder.resultMessage()
            } else {
                null
            }
        } else {
            // non-UR QR: report the raw bytes exactly as encoded (byte-mode
            // payloads like PSBTs and compact SeedQRs are not UTF-8 text)
            UR("raw", bytes)
        }
        if (ur != null) {
            decodedUr = ur
            stopScanner()
            // The decode may complete after the user already left this
            // screen (Cancel during an in-flight frame); route only if this
            // view is still the current one.
            activity.runOnUiThread { if (controller.currentView === this) controller.onScreenResult(0) }
        }
    }

    protected fun stopScanner() {
        scanner?.stop()
        scanner = null
    }

    override fun onResult(index: Int): Destination {
        stopScanner()
        val ur = decodedUr
        return if (ur != null) {
            Destination(ScanResultView::class.java, viewArgs = mapOf("ur" to ur))
        } else {
            Destination(BackStackView::class.java)
        }
    }
}

/**
 * Upstream ScanView result routing (scan_views.py): the decoded QR type
 * decides the next step.
 *   psbt (any format) -> load it as the working PSBT, pick a signing seed
 *   crypto-hdkey      -> BIP-39 SeedQR: becomes the pending seed, finalize it
 *   anything else     -> show what we got
 */
class ScanResultView : View() {

    companion object {
        /** 4-letter BIP-39 prefix -> word (first match wins, like upstream). */
        private val FOUR_LETTER_PREFIX: Map<String, String> =
            com.seedsigner.core.crypto.Bip39.WORDLIST
                .map { it.take(4) to it }
                .distinctBy { it.first }
                .associate { it }
    }

    override fun run(activity: Activity): Destination? {
        val ur = viewArgs["ur"] as com.seedsigner.core.qr.ur2.UR

        // Raw single-QR PSBT: standard byte-mode BIP-174 export (e.g.
        // Sparrow's static PSBT QR). Upstream PSBTQrDecoder: magic
        // "psbt\xff" (v1) or "psbt\x00\xff" (v2). Checked on the raw bytes
        // before any text interpretation.
        if (isPsbtMagic(ur.cbor)) {
            return loadScannedPsbt(ur.cbor)
        }

        when (ur.type) {
            "crypto-psbt" -> loadScannedPsbt(ur.cbor)
            "crypto-hdkey" -> {
                val words = String(ur.cbor, Charsets.UTF_8).trim().split(" ")
                return if (words.size != 12 && words.size != 24 || !controller.storage.validateMnemonic(words)) {
                    Destination(ErrorView::class.java,
                        viewArgs = mapOf(
                            "title" to "Error",
                            "headline" to "Invalid seed",
                            "text" to "The scanned seed QR is not a valid BIP-39 mnemonic."
                        ))
                } else {
                    // A freshly-scanned seed is pending until finalized
                    // (passphrase step).
                    controller.storage.setPendingSeed(com.seedsigner.app.models.Bip39Seed(mnemonic = words))
                    Destination(SeedFinalizeView::class.java, skipCurrentView = true)
                }
            }
        }

        // CompactSeedQR entropy (upstream SEED__COMPACTSEEDQR). Upstream
        // reaches it only when the payload is NOT valid UTF-8 (its text path
        // runs first); we try it first for 16/32-byte payloads because a
        // genuine entropy payload may happen to be valid UTF-8, and the text
        // checks below stay as the fallback — behavior differs only for
        // measure-zero random payloads.
        if (ur.cbor.size == 16 || ur.cbor.size == 32) {
            SeedQrDecoderKt.decodeCompactSeedQr(ur.cbor)?.let { words ->
                return loadScannedSeed(words)
            }
            // checksum failed: fall through — a 16/32-byte payload may still
            // be a text QR (e.g. a 32-char bech32 address)
        }

        // Non-UR text QR: strict UTF-8 decode; a byte-mode payload that is
        // not valid UTF-8 skips the text checks entirely.
        val raw = runCatching {
            Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(ur.cbor)).toString()
        }.getOrNull()
        if (raw != null) {
            return routeTextQr(activity, raw, ur)
        }

        return dumpRawQr(activity, ur)
    }

    /** Upstream text-format detection order (decode_qr.py detect_segment_type). */
    private fun routeTextQr(activity: Activity, raw: String, ur: com.seedsigner.core.qr.ur2.UR): Destination? {
        // signmessage {derivation_path} ascii:{message}
        if (raw.startsWith("signmessage")) {
            val parts = raw.split(" ")
            if (parts.size >= 3) {
                val fmt = parts[2].split(":")[0]
                if (fmt == "ascii") {
                    val derivationPath = parts[1].replace("h", "'")
                    val message = raw.split("$fmt:")[1]
                    return Destination(SeedSignMessageStartView::class.java,
                        viewArgs = mapOf("derivationPath" to derivationPath, "message" to message))
                }
            }
            return Destination(ErrorView::class.java,
                viewArgs = mapOf(
                    "title" to "Error",
                    "headline" to "Invalid message QR",
                    "text" to "Expected: signmessage {derivation_path} ascii:{message}"
                ))
        }

        // A SettingsQR (upstream SettingsQrDecoder: "settings::v1 ...").
        if (raw.startsWith("settings::")) {
            return Destination(SettingsIngestSettingsQRView::class.java,
                viewArgs = mapOf("data" to raw))
        }

        // Split (animated) PSBT QRs, checked BEFORE the descriptor checks
        // like upstream: a "p{N}of{M} <base64>" frame is a Specter PSBT when
        // its payload is base64-only, and a Specter wallet when it isn't.
        if (SpecterPsbtDecoder().isSpecterPsbtSegment(raw)) {
            return Destination(ScanSpecterPsbtView::class.java,
                viewArgs = mapOf("fresh" to true), skipCurrentView = true)
        }
        if (BbqrDecoder().isBbqrSegment(raw)) {
            return Destination(ScanBbqrPsbtView::class.java,
                viewArgs = mapOf("fresh" to true), skipCurrentView = true)
        }

        // A wallet descriptor (upstream DecodeQR WALLET__* types): generic
        // sortedmulti text, a multisig setup file, or a Specter QR.
        if (DescriptorQr.isGeneric(raw) || DescriptorQr.isConfigFile(raw) || DescriptorQr.isSpecter(raw)) {
            // The descriptor scanner owns the single-frame handling and the
            // multi-part Specter accumulation; for single-frame input it
            // completes in one scan.
            return Destination(ScanWalletDescriptorView::class.java,
                viewArgs = mapOf("fresh" to true), skipCurrentView = true)
        }

        // A Bitcoin address (upstream BitcoinAddressQrDecoder).
        val addrTriple = com.seedsigner.core.crypto.AddressType.detectWithAddress(raw)
        if (addrTriple != null) {
            return Destination(AddressVerificationStartView::class.java,
                viewArgs = mapOf(
                    "address" to addrTriple.first,
                    "scriptType" to addrTriple.second,
                    "network" to addrTriple.third
                ), skipCurrentView = true)
        }

        // A SeedQR is a single frame of 48 (12 words) or 96 (24
        // words) digits, four digits per word index (upstream DecodeQR
        // SEED__SEEDQR).
        if (raw.matches(Regex("\\d{48,96}")) && raw.length % 4 == 0) {
            val numWords = raw.length / 4
            if (numWords == 12 || numWords == 24) {
                val words = mutableListOf<String>()
                var valid = true
                for (i in 0 until numWords) {
                    val idx = raw.substring(i * 4, i * 4 + 4).toInt()
                    if (idx !in 0 until com.seedsigner.core.crypto.Bip39.WORDLIST.size) {
                        valid = false
                        break
                    }
                    words.add(com.seedsigner.core.crypto.Bip39.WORDLIST[idx])
                }
                if (valid && controller.storage.validateMnemonic(words)) {
                    return loadScannedSeed(words)
                }
            }
        }

        // A plain BIP-39 mnemonic string (upstream SEED__MNEMONIC): every
        // whitespace-separated token is a full wordlist word.
        val tokens = raw.trim().split(" ")
        if (tokens.size in 12..24 && tokens.all { it.isNotEmpty() } &&
            tokens.all { it in com.seedsigner.core.crypto.Bip39.WORDLIST }) {
            return loadScannedSeed(tokens)
        }

        // A 4-letter mnemonic (upstream SEED__FOUR_LETTER_MNEMONIC): every
        // token is a 4-letter wordlist prefix.
        if (tokens.size in 12..24 && tokens.all { it.isNotEmpty() } &&
            tokens.all { it in FOUR_LETTER_PREFIX }) {
            return loadScannedSeed(tokens.mapNotNull { FOUR_LETTER_PREFIX[it] })
        }

        // A single-frame base64 PSBT (upstream PSBT__BASE64): base64 whose
        // payload parses as a PSBT; a parse failure falls through.
        if (raw.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))) {
            runCatching { android.util.Base64.decode(raw, android.util.Base64.NO_WRAP) }
                .getOrNull()
                ?.let { if (isPsbtMagic(it)) return loadScannedPsbt(it) }
        }

        // A single-frame base43 PSBT (upstream PSBT__BASE43).
        base43Decode(raw)?.let { if (isPsbtMagic(it)) return loadScannedPsbt(it) }

        return dumpRawQr(activity, ur)
    }

    /** Load a scanned seed phrase as the pending seed and finalize it. */
    private fun loadScannedSeed(words: List<String>): Destination {
        return if (words.size !in 12..24 || !controller.storage.validateMnemonic(words)) {
            Destination(ErrorView::class.java,
                viewArgs = mapOf(
                    "title" to "Error",
                    "headline" to "Invalid seed",
                    "text" to "The scanned seed is not a valid BIP-39 mnemonic."
                ))
        } else {
            controller.storage.setPendingSeed(com.seedsigner.app.models.Bip39Seed(mnemonic = words))
            Destination(SeedFinalizeView::class.java, skipCurrentView = true)
        }
    }

    /** Parse a scanned PSBT payload and start the signing flow. */
    private fun loadScannedPsbt(payload: ByteArray): Destination = loadScannedPsbtTop(controller, payload)

    /**
     * Base43 with upstream's alphabet (decode_qr.py base43_decode);
     * null if any character is outside the alphabet.
     */
    private fun base43Decode(s: String): ByteArray? {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ$*+-./:"
        var value = java.math.BigInteger.ZERO
        for (c in s) {
            val digit = alphabet.indexOf(c)
            if (digit < 0) return null
            value = value.multiply(java.math.BigInteger.valueOf(43)).add(java.math.BigInteger.valueOf(digit.toLong()))
        }
        val bytes = value.toByteArray()
        // BigInteger is signed: strip a leading 0x00 added for a high bit
        return if (bytes.size > 1 && bytes[0] == 0x00.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }


    /** Upstream QR hex dump for unrecognized payloads. */
    private fun dumpRawQr(activity: Activity, ur: com.seedsigner.core.qr.ur2.UR): Destination? {
        val screen = Ui.screen(activity, "Scanned")
        screen.addText(ur.type, 22f)
        screen.addText("${ur.cbor.size} bytes of payload", 14f)
        val hex = ur.cbor.joinToString("") { "%02x".format(it) }
        screen.addText(hex.take(64) + if (hex.length > 64) "…" else "", 12f, 0xFF9E9E9E.toInt())
        screen.addButtons(listOf("Back to Home"))
        controller.showScreen(screen)
        return null
    }

    override fun onResult(index: Int): Destination =
        Destination(MainMenuView::class.java)
}
/**
 * Upstream ScanSpecterPsbtView: a ScanView that accumulates Specter Desktop
 * split PSBT frames ("p{N}of{M} <base64>") — upstream PSBT__SPECTER.
 * Same companion-accumulation pattern as ScanWalletDescriptorView: each part
 * re-runs the view (fresh camera), the decoder survives.
 */
class ScanSpecterPsbtView(
    private val fresh: Boolean = true
) : ScanView() {

    companion object {
        private var decoder: SpecterPsbtDecoder? = null
        fun reset() { decoder = null }
    }

    override fun onResult(index: Int): Destination {
        stopScanner()
        if (fresh) reset()
        val ur = decodedUr ?: return Destination(BackStackView::class.java)
        val raw = String(ur.cbor, Charsets.ISO_8859_1).trim()
        val d = decoder ?: SpecterPsbtDecoder().also { decoder = it }
        // Ignore QRs of other types while waiting for frames (any QR the
        // camera happens to catch is forwarded here by the base scanner);
        // keep scanning without touching the accumulator.
        if (!d.isSpecterPsbtSegment(raw)) {
            return Destination(ScanSpecterPsbtView::class.java,
                viewArgs = mapOf("fresh" to false), skipCurrentView = true)
        }
        return try {
            when (d.add(raw)) {
                SegmentStatus.COMPLETE -> {
                    val bytes = d.data()
                    reset()
                    if (bytes != null) loadScannedPsbtTop(controller, bytes)
                    else Destination(ErrorView::class.java, viewArgs = mapOf(
                        "title" to "Error", "headline" to "Invalid PSBT",
                        "text" to "The scanned PSBT segments did not reassemble."
                    ))
                }
                SegmentStatus.INVALID -> {
                    reset()
                    Destination(ErrorView::class.java, viewArgs = mapOf(
                        "title" to "Error", "headline" to "Invalid PSBT",
                        "text" to "The scanned PSBT segments are not valid base64."
                    ))
                }
                // PART_COMPLETE / PART_EXISTING: keep scanning
                else -> Destination(ScanSpecterPsbtView::class.java,
                    viewArgs = mapOf("fresh" to false), skipCurrentView = true)
            }
        } catch (e: SplitQrException) {
            reset()
            Destination(ErrorView::class.java, viewArgs = mapOf(
                "title" to "Error", "headline" to "Invalid PSBT",
                "text" to (e.message ?: "Inconsistent PSBT segments.")
            ))
        }
    }
}

/**
 * Upstream ScanBbqrPsbtView: a ScanView that accumulates BBQr split PSBT
 * frames ("B$<enc>P<total><current><data>") — upstream PSBT__BBQR.
 */
class ScanBbqrPsbtView(
    private val fresh: Boolean = true
) : ScanView() {

    companion object {
        private var decoder: BbqrDecoder? = null
        fun reset() { decoder = null }
    }

    override fun onResult(index: Int): Destination {
        stopScanner()
        if (fresh) reset()
        val ur = decodedUr ?: return Destination(BackStackView::class.java)
        val raw = String(ur.cbor, Charsets.ISO_8859_1).trim()
        val d = decoder ?: BbqrDecoder().also { decoder = it }
        // Ignore QRs of other types while waiting for frames (see
        // ScanSpecterPsbtView); keep scanning without touching the accumulator.
        if (!d.isBbqrSegment(raw)) {
            return Destination(ScanBbqrPsbtView::class.java,
                viewArgs = mapOf("fresh" to false), skipCurrentView = true)
        }
        return try {
            when (d.add(raw)) {
                SegmentStatus.COMPLETE -> {
                    val bytes = d.data()
                    reset()
                    if (bytes != null) loadScannedPsbtTop(controller, bytes)
                    else Destination(ErrorView::class.java, viewArgs = mapOf(
                        "title" to "Error", "headline" to "Invalid PSBT",
                        "text" to "The scanned BBQr segments did not reassemble."
                    ))
                }
                SegmentStatus.INVALID -> {
                    reset()
                    Destination(ErrorView::class.java, viewArgs = mapOf(
                        "title" to "Error", "headline" to "Invalid PSBT",
                        "text" to "The scanned BBQr segments could not be decoded."
                    ))
                }
                else -> Destination(ScanBbqrPsbtView::class.java,
                    viewArgs = mapOf("fresh" to false), skipCurrentView = true)
            }
        } catch (e: SplitQrException) {
            reset()
            Destination(ErrorView::class.java, viewArgs = mapOf(
                "title" to "Error", "headline" to "Invalid PSBT",
                "text" to (e.message ?: "Inconsistent BBQr segments.")
            ))
        }
    }
}

/** Magic prefix of a byte-mode PSBT QR (upstream PSBTQrDecoder). */
internal fun isPsbtMagic(b: ByteArray): Boolean =
    b.size >= 6 &&
    b[0] == 0x70.toByte() && b[1] == 0x73.toByte() &&
    b[2] == 0x62.toByte() && b[3] == 0x74.toByte() &&
    (b[4] == 0xff.toByte() || (b[4] == 0x00.toByte() && b[5] == 0xff.toByte()))

/** Parse a scanned PSBT payload and start the signing flow. */
internal fun loadScannedPsbtTop(controller: Controller, payload: ByteArray): Destination {
    val psbt = try {
        com.seedsigner.core.psbt.Psbt.parse(payload)
    } catch (e: Exception) {
        null
    }
    return if (psbt == null) {
        Destination(ErrorView::class.java,
            viewArgs = mapOf(
                "title" to "Error",
                "headline" to "Invalid PSBT",
                "text" to "The scanned PSBT could not be parsed."
            ))
    } else {
        controller.psbt = psbt
        controller.psbtParser = null
        Destination(PSBTSelectSeedView::class.java, skipCurrentView = true)
    }
}
