package com.seedsigner.core.qr

import java.util.Base64
import java.util.zip.Inflater

/**
 * Segment accumulators for the split (animated) PSBT QR formats that are not
 * UR-types, ported 1:1 from upstream decode_qr.py
 * (`SpecterPsbtQrDecoder` / `BBQRPsbtQrDecoder`). Pure + stateful so the view
 * layer can keep one instance alive across frames.
 *
 * Both follow upstream's `BaseAnimatedQrDecoder.add` semantics: the first
 * segment fixes the total; a later segment claiming a different total is an
 * error; re-sent segments are no-ops; completion is when every slot is filled.
 */
class SplitQrException(message: String) : Exception(message)

enum class SegmentStatus { PART_COMPLETE, PART_EXISTING, COMPLETE, INVALID }

/** Specter Desktop animated PSBT: frames "p{N}of{M} {base64}". */
class SpecterPsbtDecoder {
    private var totalSegments: Int? = null
    private val segments = HashMap<Int, String>()

    /** Upstream classification: `^p(\d+)of(\d+) <base64-only>$` (case-insensitive). */
    fun isSpecterPsbtSegment(s: String): Boolean =
        Regex("^p(\\d+)of(\\d+) [A-Za-z0-9+/=]+$", RegexOption.IGNORE_CASE).matches(s)

    fun currentSegmentNum(segment: String): Int =
        Regex("^p(\\d+)of(\\d+) ", RegexOption.IGNORE_CASE).find(segment)!!.groupValues[1].toInt()

    fun totalSegmentNums(segment: String): Int =
        Regex("^p(\\d+)of(\\d+) ", RegexOption.IGNORE_CASE).find(segment)!!.groupValues[2].toInt()

    fun add(segment: String): SegmentStatus {
        val total = totalSegmentNums(segment)
        totalSegments?.let { if (it != total) throw SplitQrException("Segment total changed unexpectedly") }
        totalSegments = total
        val num = currentSegmentNum(segment)
        if (num !in 1..total) throw SplitQrException("Segment number out of range")
        val b64 = segment.split(" ").last().trim()
        if (segments.putIfAbsent(num, b64) != null) return SegmentStatus.PART_EXISTING
        if (segments.size == total) {
            val data = try {
                Base64.getDecoder().decode(segments.keys.sorted().joinToString("") { segments[it]!! })
            } catch (e: IllegalArgumentException) {
                return SegmentStatus.INVALID
            }
            // stash for data()
            assembled = data
            return SegmentStatus.COMPLETE
        }
        return SegmentStatus.PART_COMPLETE
    }

    private var assembled: ByteArray? = null

    /** Upstream get_data: the decoded bytes once complete, else null. */
    fun data(): ByteArray? = assembled
}

/**
 * BBQr animated PSBT (BBQr.md): frames "B${E}{T}{XX}{YY}{data}" where E is
 * the encoding (H=hex, P=base32, Z=base32+zlib-deflate), T the file type
 * (P=psbt), XX the total and YY the current index (both base36 uppercase),
 * per https://github.com/coinkite/BBQr.
 */
class BbqrDecoder {
    private var totalSegments: Int? = null
    private var encoding: Char? = null
    private val segments = HashMap<Int, String>()

    /**
     * Upstream classification is `^B\$[2HZ]P[0-9A-Z]{4}` — a typo (`2` for
     * `P`) that silently rejects base32 ('P'-encoded) BBQr frames. We use the
     * intended `[PHZ]` so all three BBQr encodings are accepted (recorded in
     * REASONING.md as a deliberate upstream deviation).
     */
    fun isBbqrSegment(s: String): Boolean = Regex("^B\\$[PHZ]P[0-9A-Z]{4}").find(s) != null

    fun currentSegmentNum(segment: String): Int = base36(segment.substring(6, 8)) + 1

    fun totalSegmentNums(segment: String): Int = base36(segment.substring(4, 6))

    fun add(segment: String): SegmentStatus {
        val total = totalSegmentNums(segment)
        totalSegments?.let { if (it != total) throw SplitQrException("Segment total changed unexpectedly") }
        totalSegments = total
        val enc = segment[2]
        encoding?.let { if (it != enc) throw SplitQrException("Encoding changed unexpectedly") }
        encoding = enc
        val num = currentSegmentNum(segment)
        if (num !in 1..total) throw SplitQrException("Segment number out of range")
        val data = segment.substring(8).trim()
        if (segments.putIfAbsent(num, data) != null) return SegmentStatus.PART_EXISTING
        if (segments.size == total) {
            val ordered = segments.keys.sorted().map { segments[it]!! }
            val bytes = try {
                when (enc) {
                    'H' -> ordered.joinToString("") { it }.hexToBytes()
                    else -> {
                        // 'P' and 'Z' are base32 per segment (pad per segment,
                        // like upstream)
                        val parts = ordered.map { s ->
                            val padding = (8 - (s.length % 8)) % 8
                            base32Decode(s + "=".repeat(padding))
                        }
                        val raw = parts.fold(ByteArray(0)) { acc, p -> acc + p }
                        if (enc == 'Z') raw.rawInflate() else raw
                    }
                }
            } catch (e: Exception) {
                return SegmentStatus.INVALID
            }
            assembled = bytes
            return SegmentStatus.COMPLETE
        }
        return SegmentStatus.PART_COMPLETE
    }

    private var assembled: ByteArray? = null

    /** Upstream get_data: the decoded bytes once complete, else null. */
    fun data(): ByteArray? = assembled

    private fun base36(s: String): Int = s.toInt(36)
}

/** Decodes a base16 string (odd lengths are rejected, like bytes.fromhex). */
internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Odd-length hex string" }
    return ByteArray(length / 2) { i ->
        (this.substring(i * 2, i * 2 + 2).toInt(16)).toByte()
    }
}

/** RFC 4648 base32 (A-Z, 2-7), no checksum; '=' padding allowed at the end. */
internal fun base32Decode(s: String): ByteArray {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val clean = s.trimEnd('=')
    var bits = 0
    var value = 0
    val out = ByteArray((clean.length * 5 / 8))
    var outPos = 0
    for (c in clean.uppercase()) {
        val idx = alphabet.indexOf(c)
        require(idx >= 0) { "Invalid base32 character: $c" }
        value = (value shl 5) or idx
        bits += 5
        if (bits >= 8) {
            bits -= 8
            out[outPos++] = ((value shr bits) and 0xFF).toByte()
        }
    }
    return out
}

/** Raw (no zlib header) deflate inflate — upstream zlib.decompressobj(wbits=-10). */
internal fun ByteArray.rawInflate(): ByteArray {
    val inflater = Inflater(true)
    inflater.setInput(this)
    val out = ByteArray(this.size.coerceAtLeast(64) * 2)
    val n = inflater.inflate(out)
    inflater.end()
    return out.copyOf(n)
}
