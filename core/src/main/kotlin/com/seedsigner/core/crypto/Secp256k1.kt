package com.seedsigner.core.crypto

import java.math.BigInteger

/**
 * secp256k1 + ECDSA, byte-identical to upstream (embit 0.8.0, which wraps
 * libsecp256k1): RFC-6979 deterministic nonces, canonical low-s signatures,
 * and the same signature "grinding" (re-sign with an incrementing aux
 * counter until the DER serialization is <= 70 bytes).
 *
 * Field/scalar arithmetic uses java.math.BigInteger (JDK, not Android):
 * correctness and auditability over speed — this is a single-user,
 * offline device, and signing latency of a few ms is not a concern.
 */
object Secp256k1 {

    val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    val Gx = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    val Gy = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    val G = Point(Gx, Gy)

    data class Point(val x: BigInteger, val y: BigInteger) {
        companion object {
            val INF = Point(BigInteger.ZERO, BigInteger.ZERO)
        }
        val isInfinity get() = this === INF
        fun toBytes(): ByteArray = if (isInfinity) ByteArray(33) else {
            byteArrayOf((0x02 xor (if (y.testBit(0)) 1 else 0)).toByte()) + x.toByteArray32()
        }
        fun toXOnlyBytes(): ByteArray = x.toByteArray32()
    }

    // ---------- group arithmetic (affine, modular inverse) ----------

    fun pointAdd(a: Point, b: Point): Point {
        if (a.isInfinity) return b
        if (b.isInfinity) return a
        if (a.x == b.x) {
            if (a.y == b.y) return pointDouble(a)
            return Point.INF
        }
        val lambda = (b.y - a.y).mod(P)
            .multiply((b.x - a.x).mod(P).modInverse(P)).mod(P)
        val x = (lambda.multiply(lambda).subtract(a.x).subtract(b.x)).mod(P)
        val y = (lambda.multiply(a.x - x).subtract(a.y)).mod(P)
        return Point(x, y)
    }

    fun pointDouble(a: Point): Point {
        if (a.isInfinity || a.y.signum() == 0) return Point.INF
        val lambda = BigInteger.valueOf(3).multiply(a.x.multiply(a.x)).mod(P)
            .multiply(BigInteger.valueOf(2).multiply(a.y).mod(P).modInverse(P)).mod(P)
        val x = (lambda.multiply(lambda).subtract(BigInteger.valueOf(2).multiply(a.x))).mod(P)
        val y = (lambda.multiply(a.x - x).subtract(a.y)).mod(P)
        return Point(x, y)
    }

    /** k * point, double-and-add (MSB first). */
    fun scalarMul(k: BigInteger, point: Point = G): Point {
        var result = Point.INF
        var addend = point
        var kk = k.mod(N)
        while (kk.signum() > 0) {
            if (kk.testBit(0)) result = pointAdd(result, addend)
            addend = pointDouble(addend)
            kk = kk.shiftRight(1)
        }
        return result
    }

    fun publicFromPrivate(privateKey: ByteArray): Point {
        val d = BigInteger(1, privateKey)
        require(d > BigInteger.ZERO && d < N) { "private key out of range" }
        return scalarMul(d)
    }

    fun isValidPoint(p: Point): Boolean {
        if (p.isInfinity) return false
        if (p.x <= BigInteger.ZERO || p.x >= P || p.y <= BigInteger.ZERO || p.y >= P) return false
        return p.y.multiply(p.y).mod(P)
            .equals(p.x.multiply(p.x).multiply(p.x).add(BigInteger.valueOf(7)).mod(P))
    }

    // ---------- ECDSA ----------

    data class Signature(val r: BigInteger, val s: BigInteger)

    /**
     * Deterministic ECDSA (RFC-6979, HMAC-SHA256), low-s canonical form,
     * exactly as libsecp256k1/ecdsa_sign(msg_hash, key, aux).
     * [aux] is the 32-byte aux_rand (null for the first attempt; embit
     * grinds with counter.to_bytes(32, 'little') until DER length <= 70).
     */
    fun sign(msgHash: ByteArray, privateKey: ByteArray, aux: ByteArray? = null): Signature {
        val d = BigInteger(1, privateKey)
        val e = bits2left(msgHash, N)
        while (true) {
            val k = rfc6979(msgHash, d, aux)
            val rp = scalarMul(k)
            val r = rp.x.mod(N)
            if (r.signum() == 0) continue
            var s = k.modInverse(N).multiply(e.add(r.multiply(d))).mod(N)
            if (s.signum() == 0) continue
            if (s > N.shiftRight(1)) s = N.subtract(s)
            return Signature(r, s)
        }
    }

    /** Upstream `PrivateKey.sign(msg_hash, grind=True)`. */
    fun signWithGrind(msgHash: ByteArray, privateKey: ByteArray): Signature {
        var sig = sign(msgHash, privateKey, null)
        var counter = 1
        while (signatureToDer(sig).size > 70 && counter <= 200) {
            sig = sign(msgHash, privateKey, counter.toByteVector32LE())
            counter++
        }
        return sig
    }

    fun verify(msgHash: ByteArray, sig: Signature, publicKey: Point): Boolean {
        val z = bits2left(msgHash, N)
        val r = sig.r; val s = sig.s
        if (r <= BigInteger.ZERO || r >= N || s <= BigInteger.ZERO || s >= N) return false
        val sInv = s.modInverse(N)
        val u1 = z.multiply(sInv).mod(N)
        val u2 = r.multiply(sInv).mod(N)
        val p = pointAdd(scalarMul(u1), scalarMul(u2, publicKey))
        if (p.isInfinity) return false
        return p.x.mod(N) == r
    }

    fun verify(msgHash: ByteArray, sig: Signature, publicKeyCompressed: ByteArray): Boolean {
        return verify(msgHash, sig, pointFromBytes(publicKeyCompressed))
    }

    /**
     * RFC-6979 nonce, libsecp256k1 variant (verified byte-identical against
     * embit's prebuilt libsecp256k1-zkp .so, 2021 build).
     * keydata = key(32) || msg(32) || aux(32, if present); standard chain
     * K = HMAC(K, V||0x00||keydata), V = HMAC_K(V), K = HMAC_K(V||0x01||keydata),
     * V = HMAC_K(V), then V = HMAC_K(V) candidates.
     * (msg is used raw; C reduces it mod n, a no-op for any 32-byte digest < n.)
     */
    fun rfc6979(msgHash: ByteArray, d: BigInteger, aux: ByteArray?): BigInteger {
        val x = d.toByteArray32()
        val keydata = if (aux != null) x + msgHash + aux else x + msgHash
        var v = ByteArray(32) { 0x01 }
        var k = ByteArray(32)
        k = Hashes.hmacSha256(k, v + byteArrayOf(0) + keydata)
        v = Hashes.hmacSha256(k, v)
        k = Hashes.hmacSha256(k, v + byteArrayOf(1) + keydata)
        v = Hashes.hmacSha256(k, v)
        while (true) {
            v = Hashes.hmacSha256(k, v)
            val candidate = BigInteger(1, v)
            if (candidate > BigInteger.ZERO && candidate < N) return candidate
            k = Hashes.hmacSha256(k, v + byteArrayOf(0))
            v = Hashes.hmacSha256(k, v)
        }
    }

    // ---------- DER / byte encoding ----------

    fun signatureToDer(sig: Signature): ByteArray {
        val rB = minimalBytes(sig.r)
        val sB = minimalBytes(sig.s)
        val body = byteArrayOf(0x02.toByte(), rB.size.toByte()) + rB +
            byteArrayOf(0x02.toByte(), sB.size.toByte()) + sB
        return byteArrayOf(0x30.toByte(), body.size.toByte()) + body
    }

    fun parseDer(der: ByteArray): Signature {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "not DER signature" }
        var i = 2
        fun readInt(): BigInteger {
            require(der[i] == 0x02.toByte()) { "bad DER int tag" }
            val len = der[i + 1].toInt() and 0xff
            val bytes = der.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
            return BigInteger(1, bytes)
        }
        val r = readInt()
        val s = readInt()
        return Signature(r, s)
    }

    fun pointFromBytes(b: ByteArray): Point {
        require(b.size == 33) { "expected 33-byte compressed pubkey" }
        val prefix = b[0].toInt() and 0xff
        require(prefix == 0x02 || prefix == 0x03) { "bad compressed prefix" }
        val x = BigInteger(1, b.copyOfRange(1, 33))
        require(x < P) { "x out of range" }
        val ySq = x.multiply(x).multiply(x).add(BigInteger.valueOf(7)).mod(P)
        // p ≡ 3 (mod 4): sqrt(a) = a^((p+1)/4) when a is a quadratic residue.
        var y = ySq.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        require(y.multiply(y).mod(P) == ySq) { "x is not on the curve" }
        if (y.testBit(0) != (prefix == 0x03)) y = P.subtract(y)
        return Point(x, y)
    }

    // ---------- helpers ----------

    /** BIP-339 bits2left: int(data) reduced to the size of [order]. */
    private fun bits2left(data: ByteArray, order: BigInteger): BigInteger {
        val x = BigInteger(1, data)
        val overflow = x.bitLength() - order.bitLength()
        return if (overflow > 0) x.shiftRight(overflow) else x
    }

    private fun minimalBytes(v: BigInteger): ByteArray {
        var b = v.toByteArray()
        if (b.size > 1 && b[0] == 0.toByte() && (b[1].toInt() and 0x80) == 0) {
            b = b.copyOfRange(1, b.size)
        }
        return b
    }

    private fun BigInteger.toByteArray32(): ByteArray {
        val b = toByteArray()
        return when {
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            b.size < 32 -> ByteArray(32 - b.size) + b
            else -> b
        }
    }

    private fun Int.toByteVector32LE(): ByteArray {
        val out = ByteArray(32)
        var v = this
        var i = 0
        while (i < 4 && v != 0) {
            out[i] = (v and 0xff).toByte()
            v = v ushr 8
            i++
        }
        return out
    }

    // ------------------------------------------------------------------
    // BIP-340 Schnorr signatures (spec: bip-0340.mediawiki, bitcoin/bips)
    // ------------------------------------------------------------------

    /** lift_x from BIP-340: returns the point with even y for a valid x, else null. */
    fun liftX(x: BigInteger): Point? {
        if (x.signum() < 0 || x >= P) return null
        val ySq = x.multiply(x).multiply(x).add(BigInteger.valueOf(7)).mod(P)
        // p ≡ 3 (mod 4): y = ySq^((p+1)/4); if x is valid this is always on-curve
        val y = ySq.modPow(P.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
        if (y.multiply(y).mod(P) != ySq) return null
        val candidate = Point(x, y)
        if (!isValidPoint(candidate) || y.testBit(0)) {
            return if (isValidPoint(Point(x, P.subtract(y)))) Point(x, P.subtract(y)) else null
        }
        return candidate
    }

    /** 32-byte x-only pubkey from a 32-byte x-only input (validates lift). */
    fun fromXonly(x: ByteArray): Point {
        require(x.size == 32)
        return liftX(BigInteger(1, x)) ?: throw IllegalArgumentException("Invalid x-only pubkey")
    }

    fun xonlyOf(point: Point): ByteArray = point.toXOnlyBytes()

    /**
     * BIP-340 default signing with deterministic aux (32 zero bytes by default),
     * i.e. rand = tagged_hash("BIP0340/nonce", t || bytes(P) || m) where
     * t = bytes(d) xor tagged_hash("BIP0340/aux", aux) when [aux] is given,
     * t = bytes(d) when it is null (embit `key.py sign_schnorr` with aux=None,
     * the source-verified reference; the BIP-340 official vectors use aux = 32 zero bytes).
     * Returns the 64-byte signature.
     */
    fun schnorrSign(message: ByteArray, secret: ByteArray, aux: ByteArray? = null): ByteArray {
        val dPrime = BigInteger(1, secret)
        require(dPrime > BigInteger.ZERO && dPrime < N) { "Invalid secret key" }
        var P = scalarMul(dPrime)
        var d = dPrime
        if (P.y.testBit(0)) {
            d = N.subtract(dPrime)
            P = Point(P.x, Secp256k1.P.subtract(P.y))
        }
        val px = P.toXOnlyBytes()
        val dd = d.toByteArray32()
        val t = if (aux != null) {
            val ta = Hashes.taggedHash("BIP0340/aux", aux)
            ByteArray(32) { (dd[it].toInt() xor ta[it].toInt()).toByte() }
        } else dd
        val rand = Hashes.taggedHash("BIP0340/nonce", t + px + message)
        var k = BigInteger(1, rand).mod(N)
        var R = scalarMul(k)
        if (R.y.testBit(0)) {
            k = N.subtract(k)
            R = Point(R.x, Secp256k1.P.subtract(R.y))
        }
        val e = BigInteger(1, Hashes.taggedHash("BIP0340/challenge", R.toXOnlyBytes() + px + message)).mod(N)
        val sig = R.toXOnlyBytes() + k.add(e.multiply(d)).mod(N).toByteArray32()
        require(schnorrVerify(message, sig, px)) { "Schnorr signature failed self-verification" }
        return sig
    }

    /** BIP-340 verification. [xonlyPub] is the 32-byte x-only pubkey. */
    fun schnorrVerify(message: ByteArray, sig: ByteArray, xonlyPub: ByteArray): Boolean {
        if (sig.size != 64 || xonlyPub.size != 32) return false
        val P = liftX(BigInteger(1, xonlyPub)) ?: return false
        val r = BigInteger(1, sig.copyOfRange(0, 32))
        val s = BigInteger(1, sig.copyOfRange(32, 64))
        if (r >= Secp256k1.P || s >= N) return false
        val e = BigInteger(1, Hashes.taggedHash("BIP0340/challenge", sig.copyOfRange(0, 32) + xonlyPub + message)).mod(N)
        val R = pointAdd(scalarMul(s), scalarMul(N.subtract(e), P))
        if (R.isInfinity || R.y.testBit(0)) return false
        return R.x == r
    }

    // ------------------------------------------------------------------
    // BIP-341 Taproot tweaks (spec: bip-0341.mediawiki, bitcoin/bips)
    // ------------------------------------------------------------------

    /**
     * `taproot_tweak_pubkey`: t = tagged_hash("TapTweak", pubkey || h);
     * returns the 32-byte x-only of Q = P + t*G.
     */
    fun taprootTweakPub(xonly: ByteArray, h: ByteArray = ByteArray(0)): ByteArray {
        val t = BigInteger(1, Hashes.taggedHash("TapTweak", xonly + h))
        require(t > BigInteger.ZERO && t < N) { "Tweak is too large" }
        val P = liftX(BigInteger(1, xonly)) ?: throw IllegalArgumentException("Invalid x-only pubkey")
        val Q = pointAdd(P, scalarMul(t))
        return Q.toXOnlyBytes()
    }

    /**
     * `taproot_tweak_seckey`: negates the secret if the pubkey has odd y,
     * then adds t mod n. Returns the 32-byte tweaked secret.
     */
    fun taprootTweakSec(secret: ByteArray, h: ByteArray = ByteArray(0)): ByteArray {
        val sec = publicFromPrivate(secret)
        val x = sec.toXOnlyBytes()
        val t = BigInteger(1, Hashes.taggedHash("TapTweak", x + h))
        require(t > BigInteger.ZERO && t < N) { "Tweak is too large" }
        var d = BigInteger(1, secret)
        if (sec.y.testBit(0)) d = N.subtract(d)
        return d.add(t).mod(N).toByteArray32()
    }
}
