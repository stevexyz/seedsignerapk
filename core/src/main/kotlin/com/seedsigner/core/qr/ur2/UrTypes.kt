package com.seedsigner.core.qr.ur2

/**
 * UR crypto payload builders — 1:1 port of the `urtypes` crypto types
 * (commit 7fb280ea, MIT) plus the payload-construction logic of
 * `seedsigner_orig/src/seedsigner/models/encode_qr.py`
 * (`UrXpubQrEncoder` / `UrPsbtQrEncoder`).
 *
 * Tags (from urtypes registry):
 *   crypto-hdkey=303, crypto-keypath=304, crypto-coin-info=305,
 *   crypto-output=308, crypto-psbt=310, crypto-account=311
 * Script-expression tags: sh=400, wsh=401, pkh=403, wpkh=404, tr=409.
 */
object UrTypes {

    const val TAG_HDKEY = 303L
    const val TAG_KEYPATH = 304L
    const val TAG_COIN_INFO = 305L
    const val TAG_OUTPUT = 308L // only used on decode; encode nests script tags directly
    const val TAG_PSBT = 310L
    const val TAG_ACCOUNT = 311L

    const val SCRIPT_SH = 400L
    const val SCRIPT_WSH = 401L
    const val SCRIPT_PKH = 403L
    const val SCRIPT_WPKH = 404L
    const val SCRIPT_TR = 409L

    /** crypto-psbt: the raw PSBT bytes as a CBOR byte string. */
    fun psbtCbor(psbtBytes: ByteArray): ByteArray {
        val w = CborWriter()
        Cbor.encodeBytes(w, psbtBytes)
        return w.out.toByteArray()
    }

    /**
     * crypto-account for an xpub export.
     *
     * @param rootFingerprint 4-byte fingerprint of the master key (root.child(0).fingerprint upstream)
     * @param key             33-byte compressed pub key of the derived xpub
     * @param chainCode       32-byte chain code of the derived xpub
     * @param parentFingerprint 4-byte fingerprint of the xpub's parent
     * @param derivation      e.g. "m/84'/0'/0'"
     * @param network         "mainnet" -> no coin-info; else CoinInfo(network=1)
     * @param scriptTags      one entry per Output: the ordered script-expression
     *                        tags for that Output (innermost last), e.g.
     *                        listOf(listOf(404)) for wpkh, listOf(listOf(400, 404)) for
     *                        sh(wpkh). Empty list = no outputs (upstream's behavior for
     *                        unknown derivations).
     */
    fun xpubAccountCbor(
        rootFingerprint: ByteArray,
        key: ByteArray,
        chainCode: ByteArray,
        parentFingerprint: ByteArray,
        derivation: String,
        network: String,
        scriptTags: List<List<Long>>
    ): ByteArray {
        val w = CborWriter()

        // outputs: one Output per entry selected by the caller
        val outputs = scriptTags

        // account map: {1: master fingerprint, 2: [outputs]}
        Cbor.encodeMapHeader(w, 2)
        Cbor.encodeInt(w, 1)
        Cbor.encodeInt(w, rootFingerprint.toUInt())
        Cbor.encodeInt(w, 2)
        Cbor.encodeArrayHeader(w, outputs.size)
        for (tags in outputs) {
            // Output.to_data_item(): key map tagged 303 (crypto-hdkey), then wrapped
            // in each script tag, innermost first
            var tagged = CborWriter()
            Cbor.encodeTag(tagged, TAG_HDKEY)
            tagged.bytes(hdKeyCbor(key, chainCode, parentFingerprint, derivation, rootFingerprint, network))
            var inner: ByteArray = tagged.out.toByteArray()
            for (tag in tags.reversed()) {
                val tw = CborWriter()
                Cbor.encodeTag(tw, tag)
                tw.bytes(inner)
                inner = tw.out.toByteArray()
            }
            w.bytes(inner)
        }
        return w.out.toByteArray()
    }

    /** crypto-hdkey map for a derived public key (urtypes HDKey.to_data_item, non-master). */
    private fun hdKeyCbor(
        key: ByteArray,
        chainCode: ByteArray,
        parentFingerprint: ByteArray,
        derivation: String,
        rootFingerprint: ByteArray,
        network: String
    ): ByteArray {
        val w = CborWriter()
        // map entries in urtypes order: 3 (key), 4 (chain), 5 (coin-info), 6 (origin), 8 (parent fp)
        val entries = 4 + if (network != "mainnet") 1 else 0
        Cbor.encodeMapHeader(w, entries)

        Cbor.encodeInt(w, 3)
        Cbor.encodeBytes(w, key)
        Cbor.encodeInt(w, 4)
        Cbor.encodeBytes(w, chainCode)

        if (network != "mainnet") {
            // CoinInfo(type=None, network=1) -> tag 305 wrapping {2: 1}
            Cbor.encodeInt(w, 5)
            Cbor.encodeTag(w, TAG_COIN_INFO)
            Cbor.encodeMapHeader(w, 1)
            Cbor.encodeInt(w, 2)
            Cbor.encodeInt(w, 1)
        }

        // origin: tag 304 wrapping keypath map {1: components, 2: source fp, 3: depth}
        Cbor.encodeInt(w, 6)
        Cbor.encodeTag(w, TAG_KEYPATH)
        val comps = parseDerivation(derivation)
        Cbor.encodeMapHeader(w, 3)
        Cbor.encodeInt(w, 1)
        Cbor.encodeArrayHeader(w, comps.size * 2)
        for ((index, hardened) in comps) {
            Cbor.encodeInt(w, index.toLong())
            Cbor.encodeBoolean(w, hardened)
        }
        Cbor.encodeInt(w, 2)
        Cbor.encodeInt(w, rootFingerprint.toUInt())
        Cbor.encodeInt(w, 3)
        Cbor.encodeInt(w, comps.size.toLong())

        Cbor.encodeInt(w, 8)
        Cbor.encodeInt(w, parentFingerprint.toUInt())
        return w.out.toByteArray()
    }

    /** "m/84'/0'/0'" -> [(84,true),(0,true),(0,true)] (trailing slash tolerated, per upstream). */
    fun parseDerivation(derivation: String): List<Pair<Int, Boolean>> {
        var arr = derivation.split("/")
        if (arr[0] == "m") arr = arr.drop(1)
        if (arr.last() == "") arr = arr.dropLast(1)
        return arr.map { e ->
            if (e.endsWith("h") || e.endsWith("'")) e.dropLast(1).toInt() to true
            else e.toInt() to false
        }
    }

    private fun ByteArray.toUInt(): Long {
        require(size == 4)
        return ((this[0].toLong() and 0xFF) shl 24) or
               ((this[1].toLong() and 0xFF) shl 16) or
               ((this[2].toLong() and 0xFF) shl 8) or
               (this[3].toLong() and 0xFF)
    }
}
