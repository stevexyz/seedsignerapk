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

        val cam = CameraScanner(activity, preview) { text ->
            handleQr(activity, text)
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

    private fun handleQr(activity: Activity, text: String) {
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
            // non-UR QR: report raw
            UR("raw", text.toByteArray(Charsets.UTF_8))
        }
        if (ur != null) {
            decodedUr = ur
            stopScanner()
            activity.runOnUiThread { controller.onScreenResult(0) }
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
 * Upstream ScanView result routing (scan_views.py): the decoded UR type
 * decides the next step.
 *   crypto-psbt  -> load it as the working PSBT, pick a signing seed
 *   crypto-hdkey -> BIP-39 SeedQR: becomes the pending seed, finalize it
 *   anything else -> show what we got
 */
class ScanResultView : View() {

    override fun run(activity: Activity): Destination? {
        val ur = viewArgs["ur"] as com.seedsigner.core.qr.ur2.UR

        when (ur.type) {
            "crypto-psbt" -> {
                val psbt = try {
                    com.seedsigner.core.psbt.Psbt.parse(ur.cbor)
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

        // Non-UR QR, upstream QR type detection order (decode_qr.py):
        // signmessage {derivation_path} ascii:{message}
        val raw = String(ur.cbor, Charsets.UTF_8).trim()
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
                    controller.storage.setPendingSeed(com.seedsigner.app.models.Bip39Seed(mnemonic = words))
                    return Destination(SeedFinalizeView::class.java, skipCurrentView = true)
                }
            }
        }

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
