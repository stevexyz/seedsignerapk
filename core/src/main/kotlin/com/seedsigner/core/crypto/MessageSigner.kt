package com.seedsigner.core.crypto

import com.seedsigner.core.crypto.Hashes.sha256
import java.util.Base64

/**
 * Bitcoin signed messages (compact signature), 1:1 port of upstream
 * seedsigner helpers/embit_utils.py `sign_message`, which itself ports
 * cryptoadvance/specter-diy's signmessage app.
 *
 * Signature = base64( flag || r(32) || s(32) ), flag = 27 + recovery +
 * (4 if compressed else 0).
 */
object MessageSigner {

    private val MAGIC = byteArrayOf(0x18) + "Bitcoin Signed Message:\n".toByteArray()

    /** Bitcoin CompactSize (minimal big-endian varint) of a byte length. */
    fun compactSize(n: Int): ByteArray = when {
        n < 0xFD -> byteArrayOf(n.toByte())
        n <= 0xFFFF -> byteArrayOf(0xFD.toByte(), (n and 0xFF).toByte(), (n ushr 8 and 0xFF).toByte())
        else -> byteArrayOf(
            0xFE.toByte(),
            (n and 0xFF).toByte(), (n ushr 8 and 0xFF).toByte(),
            (n ushr 16 and 0xFF).toByte(), (n ushr 24 and 0xFF).toByte()
        )
    }

    fun signMessage(
        seedBytes: ByteArray,
        derivation: String,
        msg: ByteArray,
        compressed: Boolean = true,
        network: Networks.Network = Networks.MAINNET
    ): String {
        val payload = MAGIC + compactSize(msg.size) + msg
        val msghash = sha256(sha256(payload))

        val root = Bip32.HDKey.fromSeed(seedBytes, version = network.versions["xprv"]!!)
        val key = Bip32.derive(root, derivation)
        require(key.privateKey != null) { "need a private key to sign" }

        val (sig, recId) = Secp256k1.signRecoverable(msghash, key.privateKey!!)
        val flag = (27 + recId + (if (compressed) 4 else 0)).toByte()
        val ser = byteArrayOf(flag) + toBe32(sig.r) + toBe32(sig.s)
        return Base64.getEncoder().encodeToString(ser)
    }

    /** BigInteger -> 32-byte big-endian (unsigned). */
    private fun toBe32(i: java.math.BigInteger): ByteArray {
        var b = i.toByteArray()
        if (b.size == 33 && b[0].toInt() == 0) b = b.copyOfRange(1, 33)
        require(b.size <= 32) { "value does not fit in 32 bytes" }
        val out = ByteArray(32)
        System.arraycopy(b, 0, out, 32 - b.size, b.size)
        return out
    }
}
