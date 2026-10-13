# Current Task

**Status: [in progress] v0.0 — `core` module. Crypto + PSBT + UR2 + QR decode all complete and cross-verified; `app` shell (v0.1) next.**

## Progress so far (v0.0)

| Step | State | Notes |
| ---- | ----- | ----- |
| Build toolchain (JDK 17, Android SDK 34, adb) | done | Non-root install in `~/toolchain` (no sudo on dev machine) — see `docs/build_instructions.md` |
| Gradle project scaffold (`core` module, wrapper) | done | `./gradlew :core:test :app:assembleDebug` green; `aapt dump permissions` shows only CAMERA; minSdk 28. Versions: Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24, SDK 34 |
| `crypto`: BIP39 (wordlist, entropy↔mnemonic, PBKDF2 seed) | done | 9 JVM tests green + differential: `tools/crosscheck_bip39.py` → 309/309 byte-identical to upstream embit. Wordlist: official BIP39 English, `core/src/main/resources/wordlist/english.txt` (SHA-256 `2f5eed53...b24dbda`) |
| `crypto`: hashes (SHA-256/512, HMAC, RIPEMD-160), Base58, Bech32/32m | done | RIPEMD-160 ported from embit's `py_ripemd160.py` (Pieter Wuille, MIT); official test vectors + 1,000,000×'a' multi-block case green |
| `crypto`: secp256k1, BIP32, SLIP-0132, addresses | done | All 21 `:core:test` green, incl. fingerprint `8d9cced8`, documented BIP84 zpub + 10 receive/change addresses; BIP39 crosscheck 209/209 byte-identical to embit. `core/build/libs/core.jar` is the testable artifact |
| `psbt`: parse, validate, sign | done | BIP-174 v0+v2 parse/serialize, sighash (legacy, BIP-143, BIP-341), ECDSA sign with embit's DER-length grind loop, BIP-340 tapkey/leaf-sign. Differential: `tools/crosscheck_psbt.py` → 8/8 cases byte-identical to embit (p2pkh, p2sh-p2wpkh, p2wpkh, p2wsh multisig, taproot, multi-input/output, explicit sighash, PSBTv2). Reference forced to embit's pure-Python secp256k1 — no prebuilt `.so` in the verification path (REASONING.md #23) |
| `qr`: UR2 data layer (bytewords, CRC32, CBOR-lite, xoshiro256, fountain encode/decode, single+multi-part UR) | done | 1:1 port of `seedsigner_orig/src/seedsigner/helpers/ur2/` (9 modules) to `core/src/main/kotlin/com/seedsigner/core/qr/ur2/`. Differential: `tools/crosscheck_ur2.py` + `:core:crosscheck-ur2` → 5/5 cases byte-identical to the Python reference (decode with parts fed in reversed order + re-encode). 8 JVM round-trip tests incl. fountain recovery from dropped frames. SeedQR *display* framing is an `app`-layer concern (v0.1), not `core` |
| `qr`: vendored ZXing 3.5.4 decoder (source-only, 55 files) + `VENDORED.md` | done | Unmodified Java source from tag `zxing-3.5.4`; encoder (11 files) vendored test-only. Real-path crosscheck: `tools/crosscheck_qr.py` + `:core:crosscheck-qr` — Python `qrcode` renders all UR2 parts at upstream densities (10/30/120 cbor bytes) → vendored decoder reads 277/284; the 7 failures are a pinned upstream ZXing 3.5.4 detector limitation (pristine ZXing fails identically; OpenCV decodes them fine), mitigated in the app with rotation/offset retries. JVM tests: `ZxingDecodeTest` (round-trip, PNG re-encode, noise + rescale, garbage rejection). Upstream density sizes sourced from `seedsigner_orig/.../encode_qr.py` (`qr_max_fragment_size`) |
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

1. The `app` shell (v0.1): one Activity, upstream-View-mirroring screens, emulated buttons (spec §4), SeedQR display framing on top of the `core` UR2 encoder, camera capture feeding the vendored ZXing decoder (with rotation/offset retries).

Repo note: upstream CI (`.github/`) and `.gitmodules` were removed as Python-specific; `.gitignore` is now Android-oriented. CI returns at v0.1.

## Working rules

* Repo: <https://github.com/stevexyz/seedsignerapk> — **never push**, the user pushes.
* Never enter `old*` directories; never read `PRIVATE*` files.
* `seedsigner_orig/` is read-only.
* Identical-to-upstream docs stay referenced, not copied.
* Keep everything as simple as possible — always.
