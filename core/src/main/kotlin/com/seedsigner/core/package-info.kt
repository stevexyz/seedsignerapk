@file:JvmName("Core")

/**
 * `core` — pure Kotlin, no Android imports (docs/code_structure.md).
 *
 * Packages:
 *  - `crypto`  secp256k1, BIP39, BIP32, SLIP-0132
 *  - `psbt`    parse, validate, sign
 *  - `qr`      encoder (UR2, SeedQR, animated chunking); decoder (vendored)
 *  - `settings` settings model
 *
 * Every routine must produce byte-identical results to upstream
 * (docs/spec.md §3).
 */
