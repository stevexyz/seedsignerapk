package com.seedsigner.core.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Crosscheck reader for tools/crosscheck_qr.py:
 * decodes QR PNGs rendered by the Python `qrcode` library (the encoder
 * upstream SeedSigner uses) with the vendored ZXing decoder.
 *
 * args: inTsv (png_path<TAB>expected)  outTsv (decoded strings, same order)
 */
fun main(args: Array<String>) {
    val inTsv = args[0]
    val outTsv = args[1]
    val reader = QRCodeReader()
    val lines = java.io.File(inTsv).readLines()
    val out = StringBuilder()
    var ok = 0
    for (line in lines) {
        val (pngPath, expected) = line.split("\t")
        val img = ImageIO.read(java.io.File(pngPath))
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        val decoded = try {
            reader.decode(BinaryBitmap(GlobalHistogramBinarizer(RGBLuminanceSource(img.width, img.height, px)))).text
        } catch (e: Exception) {
            null
        }
        if (decoded == expected) ok++ else println("MISMATCH $pngPath: $decoded")
        out.append(decoded ?: "").append('\n')
    }
    java.io.File(outTsv).writeText(out.toString())
    println("Decoded $ok/${lines.size}")
}
