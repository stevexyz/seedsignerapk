# Current Task

**Status: [in progress] v0.0 — `core` module. Crypto + PSBT complete and cross-verified; QR layer next.**

## Progress so far (v0.0)

| Step | State | Notes |
| ---- | ----- | ----- |
| Build toolchain (JDK 17, Android SDK 34, adb) | done | Non-root install in `~/toolchain` (no sudo on dev machine) — see `docs/build_instructions.md` |
| Gradle project scaffold (`core` module, wrapper) | done | `./gradlew :core:test :app:assembleDebug` green; `aapt dump permissions` shows only CAMERA; minSdk 28. Versions: Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24, SDK 34 |
| `crypto`: BIP39 (wordlist, entropy↔mnemonic, PBKDF2 seed) | done | 9 JVM tests green + differential: `tools/crosscheck_bip39.py` → 309/309 byte-identical to upstream embit. Wordlist: official BIP39 English, `core/src/main/resources/wordlist/english.txt` (SHA-256 `2f5eed53...b24dbda`) |
| `crypto`: hashes (SHA-256/512, HMAC, RIPEMD-160), Base58, Bech32/32m | done | RIPEMD-160 ported from embit's `py_ripemd160.py` (Pieter Wuille, MIT); official test vectors + 1,000,000×'a' multi-block case green |
| `crypto`: secp256k1, BIP32, SLIP-0132, addresses | done | All 21 `:core:test` green, incl. fingerprint `8d9cced8`, documented BIP84 zpub + 10 receive/change addresses; BIP39 crosscheck 209/209 byte-identical to embit. `core/build/libs/core.jar` is the testable artifact |
| `psbt`: parse, validate, sign | done | BIP-174 v0+v2 parse/serialize, sighash (legacy, BIP-143, BIP-341), ECDSA sign with embit's DER-length grind loop, BIP-340 tapkey/leaf-sign. Differential: `tools/crosscheck_psbt.py` → 8/8 cases byte-identical to embit (p2pkh, p2sh-p2wpkh, p2wpkh, p2wsh multisig, taproot, multi-input/output, explicit sighash, PSBTv2). Reference forced to embit's pure-Python secp256k1 — no prebuilt `.so` in the verification path (REASONING.md #23) |
| `qr`: encoder (UR2, SeedQR, chunking) | not started | |
| `qr`: vendored ZXing decoder + `VENDORED.md` | not started | |
| `app` shell (v0.1) | not started | |

## The doc set

| File | Role |
| ---- | ---- |
| `README.md` | Overview + doc map |
| `docs/spec.md` | **Binding requirements** (isolation, statelessness, no external deps, minimal Google dep, correctness) |
| `docs/code_structure.md` | Architecture: pure-Kotlin `core` + thin classic-Views `app` shell |
| `docs/feature_roadmap.md` | Milestones v0.0 → v0.4 |
| `docs/build_instructions.md` | Build, install, audit, release verification |
| `docs/developer_tips.md` | Test-seed generation, dev loop |
| `docs/debug_crash.md` | Crash triage via adb |
| `docs/dice_verification.md` | Seed-generation verification + fixed test vectors |
| `REASONING.md` | Why the decisions were made + open questions |
| `SECURITY.md`, `LICENSE.md` | Reporting policy, MIT |
| `seedsigner_orig/docs/` | Unchanged upstream docs, referenced not copied: QR formats, SeedQR, SLIP-0132 paths, Electrum |

## How to verify the docs

1. Every link in `README.md` resolves.
2. `grep -ri "raspberry\|joystick\|SD card\|HDMI\|GPIO" docs/ README.md` returns only deliberate mentions of the upstream project, no user-facing instructions.
3. No constraint is stated in more than one place with different wording — `docs/spec.md` is the source of truth.

## Next step

1. Implement `qr` encoder in `core` (UR2, SeedQR, chunking) — port of `seedsigner_orig/src/seedsigner/models/qrcode.py` + upstream QR helpers; pure Kotlin, tested against embit/upstream output.
2. Vendor ZXing decoder + write `VENDORED.md`.
3. Then the `app` shell (v0.1): one Activity, upstream-View-mirroring screens, emulated buttons (spec §4).

Repo note: upstream CI (`.github/`) and `.gitmodules` were removed as Python-specific; `.gitignore` is now Android-oriented. CI returns at v0.1.

## Working rules

* Repo: <https://github.com/stevexyz/seedsignerapk> — **never push**, the user pushes.
* Never enter `old*` directories; never read `PRIVATE*` files.
* `seedsigner_orig/` is read-only.
* Identical-to-upstream docs stay referenced, not copied.
* Keep everything as simple as possible — always.
