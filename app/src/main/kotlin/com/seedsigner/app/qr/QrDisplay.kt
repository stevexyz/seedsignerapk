package com.seedsigner.app.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.seedsigner.app.models.Settings
import com.seedsigner.core.qr.ur2.UR
import com.seedsigner.core.qr.ur2.UREncoder

/**
 * QR display (upstream `models/encode_qr.py` presentation side):
 * render QR strings to bitmaps and build UR2 part sequences for animated
 * display. Decoding of what we display is cross-verified by
 * `:core:crosscheck-qr` (Python `qrcode` ↔ vendored ZXing decoder).
 */
object QrDisplay {

    /**
     * Upstream `qr_max_fragment_size` for UR2 payloads
     * (encode_qr.py: low=10, medium=30, high=120 cbor bytes).
     */
    fun maxFragmentSize(density: String): Int = when (density) {
        Settings.Const.DENSITY__LOW -> 10
        Settings.Const.DENSITY__MEDIUM -> 30
        else -> 120
    }

    /**
     * All UR2 parts for a payload, as displayable UR strings, in encode
     * order (upstream `EncodeQrSequence.next_part()` sequence).
     */
    fun ur2Parts(type: String, cbor: ByteArray, density: String): List<String> {
        val encoder = UREncoder(UR(type, cbor), maxFragmentSize(density))
        val parts = mutableListOf<String>()
        while (!encoder.isComplete()) {
            parts.add(encoder.nextPart())
        }
        return parts
    }

    /** Convenience: UR object → parts. */
    fun ur2Parts(ur: UR, density: String): List<String> = ur2Parts(ur.type, ur.cbor, density)

    /** Render any string as a QR bitmap (black on white, margin 1 module). */
    fun bitmap(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                pixels[y * sizePx + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        return bmp
    }

    /** Render raw bytes (byte-mode QR) as a bitmap, e.g. compact SeedQR. */
    fun bitmapBytes(data: ByteArray, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encodeBytes(data, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                pixels[y * sizePx + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        return bmp
    }
}
