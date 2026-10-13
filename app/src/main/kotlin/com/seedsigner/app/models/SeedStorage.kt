package com.seedsigner.app.models

/**
 * 1:1 port of seedsigner_orig/src/seedsigner/models/seed_storage.py.
 *
 * Memory-only (spec §1.2): the seed list lives in process memory and is
 * wiped on process death. Nothing here touches disk.
 */
class SeedStorage {

    val seeds: MutableList<Seed> = mutableListOf()
    var pendingSeed: Seed? = null
        private set
    private var _pendingMnemonic: MutableList<String?> = mutableListOf()
    private var _pendingIsElectrum: Boolean = false

    fun setPendingSeed(seed: Seed) {
        pendingSeed = seed
    }

    /** Store the pending seed and return it (upstream finalize_pending_seed). */
    fun finalizePendingSeed(): Seed {
        val seed = pendingSeed!!
        if (seeds.none { it == seed }) seeds.add(seed)
        pendingSeed = null
        return seed
    }

    fun clearPendingSeed() {
        pendingSeed = null
    }

    fun validateMnemonic(mnemonic: List<String>): Boolean =
        try {
            Seed(mnemonic)
            true
        } catch (e: Exception) {
            false
        }

    val numSeeds: Int get() = seeds.size

    val pendingMnemonic: List<String?> get() = _pendingMnemonic.toList()
    val pendingMnemonicLength: Int get() = _pendingMnemonic.size

    fun initPendingMnemonic(numWords: Int = 12, isElectrum: Boolean = false) {
        _pendingMnemonic = MutableList(numWords) { null }
        _pendingIsElectrum = isElectrum
    }

    /** Replaces the nth word in the pending mnemonic. */
    fun updatePendingMnemonic(word: String, index: Int) {
        if (index >= _pendingMnemonic.size) return
        _pendingMnemonic[index] = word
    }

    fun getPendingMnemonicWord(index: Int): String? =
        if (index < _pendingMnemonic.size) _pendingMnemonic[index] else null

    /**
     * Fingerprint of the pending mnemonic's seed (null until the mnemonic is
     * complete and valid).
     */
    fun getPendingMnemonicFingerprint(network: String = Settings.Const.MAINNET): String? {
        return try {
            if (_pendingMnemonic.any { it == null }) return null
            Seed(_pendingMnemonic.map { it!! }).getFingerprint(network)
        } catch (e: Exception) {
            null
        }
    }

    fun convertPendingMnemonicToPendingSeed() {
        pendingSeed = Seed(_pendingMnemonic.map { it!! })
        discardPendingMnemonic()
    }

    fun discardPendingMnemonic() {
        _pendingMnemonic = mutableListOf()
        _pendingIsElectrum = false
    }

    /** Wipe everything (process-restart equivalent). */
    fun clearAll() {
        seeds.clear()
        pendingSeed = null
        discardPendingMnemonic()
    }
}
