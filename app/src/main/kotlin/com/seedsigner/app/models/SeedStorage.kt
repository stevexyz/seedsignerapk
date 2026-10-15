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

    fun validateMnemonic(mnemonic: List<String>, isElectrum: Boolean = _pendingIsElectrum): Boolean =
        try {
            if (isElectrum) ElectrumSeed(mnemonic) else Bip39Seed(mnemonic)
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

    /**
     * Replaces the nth word in the pending mnemonic. Negative indices count
     * from the end, like Python (upstream passes -1 for the final word).
     */
    fun updatePendingMnemonic(word: String?, index: Int) {
        val i = if (index < 0) _pendingMnemonic.size + index else index
        if (i < 0 || i >= _pendingMnemonic.size) return
        _pendingMnemonic[i] = word
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
            (if (_pendingIsElectrum) ElectrumSeed(_pendingMnemonic.map { it!! })
                else Bip39Seed(_pendingMnemonic.map { it!! })).getFingerprint(network)
        } catch (e: Exception) {
            null
        }
    }

    fun convertPendingMnemonicToPendingSeed() {
        pendingSeed = if (_pendingIsElectrum) ElectrumSeed(_pendingMnemonic.map { it!! })
            else Bip39Seed(_pendingMnemonic.map { it!! })
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
