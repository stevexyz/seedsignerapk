package com.seedsigner.core.crypto

import java.math.BigInteger

/**
 * BIP-32 hierarchical deterministic keys.
 *
 * Byte-identical to upstream (embit 0.8.0 `bip32`): same HMAC-SHA512
 * child-derivation, same serialization layout.
 */
object Bip32 {

    const val HARDENED: Int = -0x80000000

    class HDKey(
        val version: Int,
        val depth: Int,
        val fingerprint: Int,
        val childNumber: Int,
        val chainCode: ByteArray,
        val privateKey: ByteArray?,          // 32 bytes, null for public-only keys
        val publicKey: ByteArray,            // 33 bytes compressed
    ) {
        init {
            require(publicKey.size == 33)
            require(privateKey == null || privateKey.size == 32)
        }

        val isPrivate get() = privateKey != null

        /** 1:1 port of embit `HDKey.my_fingerprint`: hash160 of our own pubkey, first 4 bytes. */
        val myFingerprint: Int
            get() {
                val h = Hashes.hash160(publicKey)
                return ((h[0].toInt() and 0xff) shl 24) or ((h[1].toInt() and 0xff) shl 16) or
                       ((h[2].toInt() and 0xff) shl 8) or (h[3].toInt() and 0xff)
            }

        /** Public-only view of this key (same public key, no private material). */
        fun toPublic(): HDKey =
            HDKey(version, depth, fingerprint, childNumber, chainCode, null, publicKey)

        fun fingerprintOfChild(): Int =
            Hashes.hash160(publicKey).let {
                // Parentheses required: `or` binds tighter than `shl` in Kotlin
                ((it[0].toInt() and 0xff) shl 24) or
                    ((it[1].toInt() and 0xff) shl 16) or
                    ((it[2].toInt() and 0xff) shl 8) or
                    (it[3].toInt() and 0xff)
            }

        /**
         * BIP-32 serialization, base58check. [versionName] e.g. "xpub"/"zpub".
         * The version kind must match the key kind (embit raises HDError otherwise):
         * use [toPublic] before serializing a private key with a public version.
         */
        fun serialize(network: Networks.Network, versionName: String): String {
            val version = network.versions[versionName]
                ?: throw IllegalArgumentException("unknown version name: $versionName")
            val versionIsPublic = versionName.endsWith("pub")
            require(versionIsPublic == (privateKey == null)) {
                "cannot serialize ${if (privateKey == null) "public" else "private"} key with ${if (versionIsPublic) "public" else "private"} version"
            }
            val payload = ByteArray(78)
            var o = 0
            payload[o++] = (version ushr 24).toByte(); payload[o++] = (version ushr 16).toByte()
            payload[o++] = (version ushr 8).toByte(); payload[o++] = version.toByte()
            payload[o++] = depth.toByte()
            payload[o++] = (fingerprint ushr 24).toByte(); payload[o++] = (fingerprint ushr 16).toByte()
            payload[o++] = (fingerprint ushr 8).toByte(); payload[o++] = fingerprint.toByte()
            payload[o++] = (childNumber ushr 24).toByte(); payload[o++] = (childNumber ushr 16).toByte()
            payload[o++] = (childNumber ushr 8).toByte(); payload[o++] = childNumber.toByte()
            System.arraycopy(chainCode, 0, payload, o, 32); o += 32
            if (privateKey != null) {
                // 0x00 + 32-byte secret = 33 bytes
                payload[o++] = 0x00
                System.arraycopy(privateKey, 0, payload, o, 32)
            } else {
                // 33-byte compressed pubkey (prefix included) = 33 bytes
                System.arraycopy(publicKey, 0, payload, o, 33)
            }
            return Base58.encodeCheck(payload)
        }

        companion object {
            /**
         * 1:1 port of embit `HDKey(pubkey_bytes)`: public-only master key with a
         * zero chain code. Used for PSBT global xpubs, which are bare 33-byte keys.
         */
        fun fromPublicKey(publicKey: ByteArray, version: Int = Networks.MAINNET.versions["xpub"]!!): HDKey =
            HDKey(
                version = version,
                depth = 0,
                fingerprint = 0,
                childNumber = 0,
                chainCode = ByteArray(32),
                privateKey = null,
                publicKey = publicKey,
            )

        fun fromSeed(seed: ByteArray, version: Int = Networks.MAINNET.versions["xprv"]!!): HDKey {
                val i = Hashes.hmacSha512("Bitcoin seed".toByteArray(Charsets.UTF_8), seed)
                val il = i.copyOfRange(0, 32)
                val ir = i.copyOfRange(32, 64)
                val d = BigInteger(1, il)
                require(d > BigInteger.ZERO && d < Secp256k1.N) { "invalid seed" }
                return HDKey(
                    version = version,
                    depth = 0,
                    fingerprint = 0,
                    childNumber = 0,
                    chainCode = ir,
                    privateKey = il,
                    publicKey = Secp256k1.publicFromPrivate(il).toBytes(),
                )
            }

            fun parse(serialized: String): HDKey = parseBytes(Base58.decodeCheck(serialized))

            /** Parse a 78-byte BIP-32 serialization payload (post-version, raw). */
            fun parseBytes(payload: ByteArray): HDKey {
                require(payload.size == 78) { "invalid key length" }
                val version = ((payload[0].toInt() and 0xff) shl 24) or
                    ((payload[1].toInt() and 0xff) shl 16) or
                    ((payload[2].toInt() and 0xff) shl 8) or
                    (payload[3].toInt() and 0xff)
                val depth = payload[4].toInt() and 0xff
                val fingerprint = ((payload[5].toInt() and 0xff) shl 24) or
                    ((payload[6].toInt() and 0xff) shl 16) or
                    ((payload[7].toInt() and 0xff) shl 8) or
                    (payload[8].toInt() and 0xff)
                val childNumber = ((payload[9].toInt() and 0xff) shl 24) or
                    ((payload[10].toInt() and 0xff) shl 16) or
                    ((payload[11].toInt() and 0xff) shl 8) or
                    (payload[12].toInt() and 0xff)
                val chainCode = payload.copyOfRange(13, 45)
                val keyBytes = payload.copyOfRange(45, 78)
                val privateKey = if (keyBytes[0] == 0x00.toByte()) {
                    keyBytes.copyOfRange(1, 33)
                } else {
                    null
                }
                val publicKey = if (privateKey != null) {
                    Secp256k1.publicFromPrivate(privateKey).toBytes()
                } else {
                    keyBytes
                }
                return HDKey(version, depth, fingerprint, childNumber, chainCode, privateKey, publicKey)
            }
        }
    }

    /** BIP-32 CKD_priv. [index] may be hardened (>= 0x80000000, as a positive Int). */
    fun ckdPriv(key: HDKey, index: Int): HDKey {
        require(key.privateKey != null) { "cannot derive from public-only key" }
        val data = if (index and HARDENED != 0) {
            require(index < HARDENED * 2) { "index out of range" }
            byteArrayOf(0) + key.privateKey!! + index.toByteVector4BE()
        } else {
            key.publicKey + index.toByteVector4BE()
        }
        val i = Hashes.hmacSha512(key.chainCode, data)
        val il = BigInteger(1, i.copyOfRange(0, 32))
        require(il < Secp256k1.N) { "invalid child key (IL >= N)" }
        val childPriv = il.add(BigInteger(1, key.privateKey!!)).mod(Secp256k1.N)
        require(childPriv.signum() != 0) { "invalid child key" }
        val priv = childPriv.toByteArray32()
        return HDKey(
            version = key.version,
            depth = key.depth + 1,
            fingerprint = key.fingerprintOfChild(),
            childNumber = index,
            chainCode = i.copyOfRange(32, 64),
            privateKey = priv,
            publicKey = Secp256k1.publicFromPrivate(priv).toBytes(),
        )
    }

    /** BIP-32 CKD_pub (non-hardened only). */
    fun ckdPub(key: HDKey, index: Int): HDKey {
        require(index and HARDENED == 0) { "cannot derive hardened from public key" }
        val i = Hashes.hmacSha512(key.chainCode, key.publicKey + index.toByteVector4BE())
        val il = BigInteger(1, i.copyOfRange(0, 32))
        require(il < Secp256k1.N) { "invalid child key (IL >= N)" }
        val childPub = Secp256k1.pointAdd(
            Secp256k1.scalarMul(il),
            Secp256k1.pointFromBytes(key.publicKey),
        )
        return HDKey(
            version = key.version,
            depth = key.depth + 1,
            fingerprint = key.fingerprintOfChild(),
            childNumber = index,
            chainCode = i.copyOfRange(32, 64),
            privateKey = null,
            publicKey = childPub.toBytes(),
        )
    }

    /** 1:1 port of embit `HDKey.child(index)`: ckdPriv/ckdPub by key type. */
    fun child(key: HDKey, index: Int): HDKey =
        if (key.isPrivate) ckdPriv(key, index) else ckdPub(key, index)

    /** 1:1 port of embit `bip32.path_to_str(path)`. */
    fun pathToStr(path: List<Int>, fingerprint: ByteArray? = null): String {
        val sb = StringBuilder(
            if (fingerprint == null) "m"
            else fingerprint.joinToString("") { "%02x".format(it) }
        )
        for (el in path) {
            sb.append(if (el < 0) "/%dh".format(el and 0x7FFFFFFF) else "/$el")
        }
        return sb.toString()
    }

    /**
     * Derive a key along a path like "m/84h/0h/0h/0/5".
     * Accepts `h`/`H`/`'` as the hardened marker.
     */
    fun derive(key: HDKey, path: String): HDKey {
        require(path.startsWith("m/") || path == "m") { "path must start with m" }
        var current = key
        for (component in path.removePrefix("m").split("/").filter { it.isNotEmpty() }) {
            val hardened = component.endsWith("h") || component.endsWith("H") || component.endsWith("'")
            val num = component.dropLast(if (hardened) 1 else 0).toInt()
            val index = if (hardened) num + HARDENED else num
            current = if (current.isPrivate) ckdPriv(current, index) else ckdPub(current, index)
        }
        return current
    }

    /**
     * 1:1 port of embit `bip32.detect_version(path, default, network)`
     * (SLIP-132 version detection from derivation path).
     */
    fun detectVersion(path: String, default: String = "xprv", network: Networks.Network): String {
        val indices = if (path == "m") {
            emptyList()
        } else {
            path.removePrefix("m").split("/").filter { it.isNotEmpty() }.map { component ->
                val hardened = component.endsWith("h") || component.endsWith("H") || component.endsWith("'")
                val num = component.dropLast(if (hardened) 1 else 0).toInt()
                if (hardened) num + HARDENED else num
            }
        }
        var key = default
        if (indices.isEmpty()) return key
        when (indices[0]) {
            HARDENED + 84 -> key = "z" + default.drop(1)
            HARDENED + 49 -> key = "y" + default.drop(1)
            HARDENED + 48 -> {
                if (indices.size >= 4) {
                    when (indices[3]) {
                        HARDENED + 1 -> key = "Y" + default.drop(1)
                        HARDENED + 2 -> key = "Z" + default.drop(1)
                    }
                }
            }
        }
        return key
    }

    private fun Int.toByteVector4BE(): ByteArray = byteArrayOf(
        (this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte()
    )

    private fun BigInteger.toByteArray32(): ByteArray {
        val b = toByteArray()
        return when {
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            b.size < 32 -> ByteArray(32 - b.size) + b
            else -> b
        }
    }
}
