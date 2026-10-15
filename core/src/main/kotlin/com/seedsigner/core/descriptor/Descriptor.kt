package com.seedsigner.core.descriptor

import com.seedsigner.core.crypto.Bip32
import com.seedsigner.core.crypto.Networks
import com.seedsigner.core.crypto.Secp256k1
import com.seedsigner.core.tx.Script

/**
 * 1:1 port of the embit `descriptor` package, restricted to what SeedSigner
 * actually uses (source-verified against embit 0.8.0's descriptor package):
 *
 *  - wrappers: sh(...), wsh(...), sh(wsh(...))
 *  - miniscript: only multi(k, ...) / sortedmulti(k, ...) — every other
 *    operator is rejected, exactly like upstream's is_basic_multisig gate
 *  - keys: [origin_fingerprint/origin_path]xpub with an allowed derivation
 *    (fixed indexes, branch sets {a,b} / <a;b>, one wildcard)
 *
 * What is deliberately NOT ported: bare hex/WIF keys, single-sig (pkh/wpkh),
 * taproot, KeyHash, other miniscript operators. Upstream rejects those at
 * the same points (NotYetImplementedView / is_basic_multisig), so behavior
 * is equivalent for every input the app can receive.
 */

/**
 * Allowed derivation indexes. Each element:
 *  - Int: fixed index (may be hardened, i.e. >= 0x80000000)
 *  - List<Int>: branch set {a,b} / <a;b>
 *  - null: wildcard (*)
 */
class AllowedDerivation(val indexes: List<Any?>) {

    companion object {
        const val HARDENED_INDEX: Int = Int.MIN_VALUE

        fun parseElement(d: String, allowHardened: Boolean = false, allowSet: Boolean = true): Any? {
            if (d == "*") return null
            if (d.startsWith("{") && d.endsWith("}")) {
                check(allowSet) { "Set is not allowed in derivation $d" }
                return d.substring(1, d.length - 1).split(",").map { parseElement(it, allowHardened, false) as Int }
            }
            if (d.startsWith("<") && d.endsWith(">")) {
                check(allowSet) { "Set is not allowed in derivation $d" }
                return d.substring(1, d.length - 1).split(";").map { parseElement(it, allowHardened, false) as Int }
            }
            var idx = 0
            var dd = d
            if (dd.endsWith("h") || dd.endsWith("H") || dd.endsWith("'")) {
                check(allowHardened) { "Hardened derivation is not allowed in $d" }
                idx = HARDENED_INDEX
                dd = dd.substring(0, dd.length - 1)
            }
            val i = dd.toInt()
            val unsigned = i.toLong() and 0xFFFFFFFFL
            require(unsigned < 0x80000000L) { "Derivation index out of range: element=$d" }
            return idx + i.toInt()
        }

        /** Upstream: None when der is empty. */
        fun fromString(der: String, allowHardened: Boolean = false, allowSet: Boolean = true): AllowedDerivation? {
            if (der.isEmpty()) return null
            val indexes = der.split("/").map { parseElement(it, allowHardened, allowSet) }
            return AllowedDerivation(indexes)
        }
    }

    // Upstream __init__ checks.
    init {
        check(indexes.count { it == null || (it is List<*> && it.contains(null)) } <= 1) {
            "Only one wildcard is allowed"
        }
        check(indexes.count { it is List<*> } <= 1) { "Only one set of branches is allowed" }
    }

    val isWildcard: Boolean get() = indexes.any { it == null }

    val branches: List<Int>?
        get() = indexes.firstOrNull { it is List<*> } as? List<Int>

    /** Upstream fill(): resolve wildcard + branch set to concrete indexes. */
    fun fill(idx: Int?, branchIndex: Int?): List<Int> {
        if (idx != null && (idx.toLong() and 0xFFFFFFFFL) >= 0x80000000L) {
            throw Exception("Hardened indexes are not allowed in wildcard")
        }
        return indexes.map { el ->
            when {
                el == null -> idx!!
                el is List<*> -> {
                    val set = el as List<Int>
                    if (branchIndex == null) set[0]
                    else {
                        require(branchIndex in 0 until set.size) { "Invalid branch index" }
                        set[branchIndex]
                    }
                }
                else -> el as Int
            }
        }
    }

    fun branch(branchIndex: Int): AllowedDerivation =
        AllowedDerivation(fill(null, branchIndex))

    /** Upstream check_derivation(): (index, branchIndex) or null. */
    fun checkDerivation(derivation: List<Int>): Pair<Int, Int>? {
        if (derivation.size != indexes.size) return null
        var branchIdx = 0 // default branch if no branches in descriptor
        var idx: Int? = null
        for (i in indexes.indices) {
            val der = derivation[i]
            val el = indexes[i]
            when {
                el is Int -> if (el != der) return null
                el is List<*> -> {
                    val set = el as List<Int>
                    if (der !in set) return null
                    branchIdx = set.indexOf(der)
                }
                el == null -> idx = der
            }
        }
        if (idx == null) return null
        return idx to branchIdx
    }
}

/**
 * One descriptor key: [origin]xpub[/allowed_derivation].
 * Mirrors embit descriptor.arguments.Key (xpub-only subset).
 */
class Key(
    val hdkey: Bip32.HDKey,
    val originFingerprint: ByteArray?,
    val originDerivation: List<Int>,
    val allowedDerivation: AllowedDerivation?
) {

    val isExtended: Boolean get() = true

    val myFingerprint: ByteArray
        get() = intToBe4(hdkey.fingerprintOfChild())

    val fingerprint: ByteArray?
        get() = originFingerprint ?: myFingerprint

    val derivation: List<Int>
        get() = if (originFingerprint == null) emptyList() else originDerivation

    /** Upstream check_derivation(derivation_path: DerivationPath). */
    fun checkDerivation(derivationPathFp: ByteArray, derivationPath: List<Int>): Pair<Int, Int>? {
        var rest: List<Int>? = null
        // full derivation path
        if (fingerprint != null && fingerprint.contentEquals(derivationPathFp)) {
            val origin = derivation
            if (origin == derivationPath.subList(0, minOf(origin.size, derivationPath.size)) &&
                derivationPath.size >= origin.size
            ) {
                rest = derivationPath.subList(origin.size, derivationPath.size)
            }
        }
        // short derivation path
        if (myFingerprint.contentEquals(derivationPathFp)) {
            rest = derivationPath
        }
        if (allowedDerivation == null || rest == null) return null
        return allowedDerivation.checkDerivation(rest)
    }

    /** The 33-byte compressed public key. */
    val publicKey: ByteArray
        get() = hdkey.publicKey

    fun compile(): ByteArray = byteArrayOf(0x21) + publicKey

    /** Upstream derive(idx, branch_index). */
    fun derive(idx: Int, branchIndex: Int?): Key {
        val der = allowedDerivation!!.fill(idx, branchIndex)
        val k = Bip32.derive(hdkey, Bip32.pathToStr(der))
        val newOriginFp: ByteArray
        val newOriginDerivation: List<Int>
        if (originFingerprint != null) {
            newOriginFp = originFingerprint
            newOriginDerivation = originDerivation + der
        } else {
            newOriginFp = intToBe4(hdkey.fingerprintOfChild())
            newOriginDerivation = der
        }
        return Key(k, newOriginFp, newOriginDerivation, null)
    }

    companion object {
        fun intToBe4(i: Int): ByteArray = byteArrayOf(
            (i shr 24).toByte(), (i shr 16).toByte(), (i shr 8).toByte(), i.toByte()
        )

        /**
         * Parses "[fphex/path]xpub" — the form every SeedSigner descriptor
         * uses. Bare keys are rejected (see file doc).
         */
        fun parseWithOrigin(keyStr: String): Key {
            var rest = keyStr
            var originFp: ByteArray? = null
            var originDerivation = emptyList<Int>()
            if (rest.startsWith("[")) {
                val end = rest.indexOf(']')
                require(end > 0) { "Invalid key - missing ]" }
                val prefix = rest.substring(1, end)
                rest = rest.substring(end + 1)
                val parts = prefix.split("/")
                require(parts[0].length == 8) { "Invalid origin fingerprint" }
                originFp = java.util.HexFormat.of().parseHex(parts[0])
                originDerivation = parts.drop(1).map { parseIndex(it) }
            }
            require(rest.length > 4 && rest.substring(1, 4) == "pub") {
                "Expected an xpub, got ${rest.take(8)}…"
            }
            var allowed: AllowedDerivation? = null
            // Split off the allowed derivation — the xpub is base58; the
            // derivation, if present, follows it directly.
            val keyBody = extractXpub(rest)
            val derStr = rest.substring(keyBody.length)
            if (derStr.isNotEmpty()) {
                require(derStr.startsWith("/")) { "Unexpected characters after xpub" }
                allowed = AllowedDerivation.fromString(derStr.substring(1))
                    ?: throw Exception("Empty derivation")
            }
            return Key(Bip32.HDKey.parse(keyBody), originFp, originDerivation, allowed)
        }

        private fun parseIndex(s: String): Int {
            var idx = 0
            var d = s
            if (d.endsWith("h") || d.endsWith("H") || d.endsWith("'")) {
                idx = AllowedDerivation.HARDENED_INDEX
                d = d.substring(0, d.length - 1)
            }
            return idx + d.toInt()
        }

        // Base58 alphabet; an xpub body ends where non-alphabet chars start.
        private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        private fun extractXpub(s: String): String {
            var i = 0
            while (i < s.length && B58.indexOf(s[i]) >= 0) i++
            return s.substring(0, i)
        }

        /** Upstream AllowedDerivation.__str__. */
        fun toStringAd(ad: AllowedDerivation?): String {
            if (ad == null) return ""
            val sb = StringBuilder()
            for (idx in ad.indexes) {
                when {
                    idx == null -> sb.append("/*")
                    idx is List<*> -> {
                        sb.append("/<")
                        sb.append((idx as List<Int>).joinToString(";") { if (it < 0) (it - AllowedDerivation.HARDENED_INDEX).toString() + "h" else it.toString() })
                        sb.append(">")
                    }
                    else -> {
                        val i = idx as Int
                        sb.append(if (i < 0) "/${i - AllowedDerivation.HARDENED_INDEX}h" else "/$i")
                    }
                }
            }
            return sb.toString()
        }
    }
}

/**
 * multi(k, key, ..., key) / sortedmulti(k, key, ..., key).
 * Mirrors embit descriptor.miniscript.Multi/Sortedmulti (compile + verify).
 */
class Multi(
    val threshold: Int,
    val keys: List<Key>,
    val sorted: Boolean = false
) {

    init {
        // Multi.verify(): 1 <= k <= n
        require(threshold in 1 until keys.size) {
            "multi: 1 <= k <= ${keys.size - 1}, got $threshold"
        }
    }

    val args: List<Any> get() = listOf(threshold) + keys

    /** Number.compile() — threshold and n are always single digits here. */
    private fun numberCompile(n: Int): ByteArray {
        if (n == 0) return byteArrayOf(0)
        require(n <= 16) { "multi: numbers above 16 need push data" }
        return byteArrayOf((0x50 + n).toByte())
    }

    /** inner_compile(): k, keys (sorted for sortedmulti), n, OP_CHECKMULTISIG. */
    fun compile(): ByteArray {
        val keyScripts = keys.map { it.compile() }
        // Unsigned byte-wise sort, exactly like Python's sorted() on bytes.
        val bytewise = Comparator<ByteArray> { a, b ->
            val n = minOf(a.size, b.size)
            val i = (0 until n).firstOrNull { a[it] != b[it] } ?: n
            if (i < n) (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            else a.size - b.size
        }
        val body = if (sorted) keyScripts.sortedWith(bytewise) else keyScripts
        return numberCompile(threshold) + body.fold(ByteArray(0)) { a, b -> a + b } +
            numberCompile(keys.size) + byteArrayOf(0xAE.toByte())
    }
}

/**
 * Mirrors embit descriptor.Descriptor for the basic-multisig subset.
 */
class Descriptor(
    val miniscript: Multi,
    val sh: Boolean,
    val wsh: Boolean
) {

    val isBasicMultisig: Boolean get() = true
    val isSorted: Boolean get() = miniscript.sorted

    val keys: List<Key> get() = miniscript.keys

    val threshold: Int get() = miniscript.threshold
    val numKeys: Int get() = miniscript.keys.size

    val isWildcard: Boolean get() = keys.any { it.allowedDerivation?.isWildcard == true }

    /** Upstream brief_policy, basic-multisig branch. */
    val briefPolicy: String
        get() = "$threshold of $numKeys multisig" + (if (isSorted) " (sorted)" else "")

    fun scriptPubkeyType(): String {
        if (sh) return "p2sh"
        // Upstream: a non-sh, non-pkh descriptor reports "p2wsh" even without
        // a wsh wrapper (1:1 of the p2wsh else-branch).
        return "p2wsh"
    }

    /**
     * Upstream get_multisig_address core: derive(index, branch_index).
     * script_pubkey(). Derives the keys and builds the wrapped script.
     */
    fun derivedScriptPubkey(index: Int, branchIndex: Int = 0): ByteArray {
        val derivedKeys = keys.map { it.derive(index, branchIndex) }
        val compiled = Multi(threshold, derivedKeys, miniscript.sorted).compile()
        return when {
            sh && wsh -> Script.p2sh(Script.p2wsh(compiled).data).data
            wsh -> Script.p2wsh(compiled).data
            sh -> Script.p2sh(compiled).data
            else -> compiled
        }
    }

    fun address(index: Int, branchIndex: Int = 0, network: Networks.Network): String =
        Script.fromBytes(derivedScriptPubkey(index, branchIndex)).address(network)

    /**
     * Upstream Descriptor.owns(psbt_scope) — basic-multisig / xpub-only path:
     * script type quick check, then each claimed BIP-32 derivation is checked
     * against our keys; a matching derivation must reproduce the exact
     * scriptPubKey.
     */
    fun owns(scriptPubKey: ByteArray?, bip32Derivations: Map<ByteArray, com.seedsigner.core.psbt.DerivationPath>): Boolean {
        if (scriptPubKey == null) return false
        if (scriptTypeOf(scriptPubKey) != scriptPubkeyType()) return false
        for ((_, der) in bip32Derivations) {
            for (k in keys) {
                val res = k.checkDerivation(der.fingerprint, der.derivation)
                if (res != null) {
                    val (idx, branchIdx) = res
                    val sc = derivedScriptPubkey(idx, branchIdx)
                    // if derivation is found but scriptpubkey doesn't match - fail
                    return sc.contentEquals(scriptPubKey)
                }
            }
        }
        return false
    }

    companion object {
        /** Simple script-classification mirroring embit script.Script.script_type(). */
        fun scriptTypeOf(script: ByteArray): String {
            return when {
                script.size == 25 && script[0] == 0x76.toByte() -> "p2pkh"
                script.size == 23 && script[0] == 0xA9.toByte() && script[22] == 0x87.toByte() -> "p2sh"
                script.size == 22 && script[0] == 0x00.toByte() && script[1] == 0x14.toByte() -> "p2wpkh"
                script.size == 34 && script[0] == 0x00.toByte() && script[1] == 0x20.toByte() -> "p2wsh"
                script.size == 43 && script[0] == 0x51.toByte() && script[1] == 0x20.toByte() -> "p2tr"
                else -> "unknown"
            }
        }

        /**
         * Upstream Descriptor.from_string for the supported subset:
         * [sh(][wsh(](sorted)multi(k, key, ..., key)[)][)] with an optional
         * trailing #checksum (accepted but not verified — 1:1 of embit).
         */
        fun fromString(desc: String): Descriptor {
            var s = desc.trim()
            // Trailing comment/checksum, per embit from_string.
            val hash = s.indexOf('#')
            if (hash >= 0) s = s.substring(0, hash)

            var i = 0
            var sh = false
            var wsh = false
            if (s.startsWith("sh(wsh(", i)) {
                sh = true; wsh = true; i += 7
            } else if (s.startsWith("wsh(", i)) {
                wsh = true; i += 4
            } else if (s.startsWith("sh(", i)) {
                sh = true; i += 3
            } else {
                throw Exception("Invalid descriptor (starts with '${s.substring(0, minOf(7, s.length))}')")
            }

            require(s.startsWith("sortedmulti(", i) || s.startsWith("multi(", i)) {
                "Only (sorted)multi() descriptors are supported"
            }
            val sorted = s.startsWith("sortedmulti(", i)
            i += if (sorted) 12 else 6

            // threshold
            require(s[i].isDigit()) { "Expected threshold" }
            var threshold = 0
            while (i < s.length && s[i].isDigit()) {
                threshold = threshold * 10 + s[i].digitToInt(); i++
            }
            require(s[i] == ',') { "Expected comma after threshold" }
            i++

            val keyStrings = mutableListOf<String>()
            while (true) {
                i = skipSpaces(s, i)
                require(s[i] != ')') { "Empty key in descriptor" }
                val start = i
                // A key is: [origin]xpub[/derivation]; scan to the separator.
                val sep = findKeyEnd(s, i)
                keyStrings.add(s.substring(start, sep))
                i = sep
                if (s[i] == ')') break
                require(s[i] == ',') { "Expected , or ) in descriptor" }
                i++
            }
            i++ // closing paren of multi

            i = skipSpaces(s, i)
            val closers = if (sh && wsh) "))" else ")"
            require(s.substring(i) == closers) {
                "Invalid descriptor (expected '${closers}' but ends with '${s.substring(i)})"
            }

            val keys = keyStrings.map { Key.parseWithOrigin(it.trim()) }
            // Upstream Descriptor.__init__ also requires a single wildcard
            // count and identical branch counts across keys (branches check).
            val branchCounts = keys.map { it.allowedDerivation?.branches?.size ?: 1 }.toSet()
            check(branchCounts.size <= 1) { "All branches should have the same length" }

            return Descriptor(Multi(threshold, keys, sorted), sh, wsh)
        }

        // Scans a "[origin]xpub[/derivation]" token; returns index of the
        // first ',' or ')' after it.
        private fun findKeyEnd(s: String, start: Int): Int {
            var i = start
            if (s[i] == '[') {
                val close = s.indexOf(']', i)
                require(close > 0) { "Invalid key - missing ]" }
                i = close + 1
            }
            // xpub body (base58)
            while (i < s.length && B58_ALPHABET.indexOf(s[i]) >= 0) i++
            // optional allowed derivation (embit read_until logic): each
            // path element is separated by '/'; a set element '{a,b}' /
            // '<a;b>' must be skipped to its closing bracket (its comma is
            // not a key separator).
            if (i < s.length && s[i] == '/') {
                i++
                while (i < s.length && s[i] != ',' && s[i] != ')') {
                    if (s[i] == '{' || s[i] == '<') {
                        val close = if (s[i] == '{') '}' else '>'
                        val end = s.indexOf(close, i)
                        require(end > i) { "Missing $close in key derivation" }
                        i = end + 1
                    } else {
                        i++
                    }
                }
            }
            require(i < s.length) { "Unterminated key in descriptor" }
            return i
        }

        private fun skipSpaces(s: String, i: Int): Int {
            var j = i
            while (j < s.length && (s[j] == ' ' || s[j] == '\n' || s[j] == '\r' || s[j] == '\t')) j++
            return j
        }

        private const val B58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    }
}

/**
 * Upstream embit_utils.get_multisig_address. The ported subset is exactly
 * the derivable set (p2wsh, p2sh-p2wsh, legacy p2sh), so the upstream
 * is_segwit/is_legacy branch is always taken here.
 */
fun getMultisigAddress(descriptor: Descriptor, index: Int = 0, isChange: Boolean = false, embitNetwork: Networks.Network): String {
    val branchIndex = if (isChange) 1 else 0
    return descriptor.address(index, branchIndex, embitNetwork)
}

/** Upstream embit_utils.get_multisig_policy: (threshold, n) as strings. */
fun getMultisigPolicy(descriptor: Descriptor): Pair<String, String> =
    descriptor.threshold.toString() to descriptor.keys.size.toString()
