# Current Task

**Status: [in progress] v0.3 — BIP-85 ✅ and message signing ✅ done this pass. Next: address scan & verification, then the multisig descriptor model (PSBT change verification), then Address Explorer. v0.1/v0.2 device E2E still pending the user's phone.**

## v0.3 progress (this pass)

| Item | State | Notes |
| ---- | ----- | ----- |
| BIP-85 child seeds | done, commit `55759a8` | `core` `Bip85.kt` (1:1 of embit bip85.py); 39/39 crosscheck incl. all 3 official BIP-85 spec vectors; num-words → child-index → shared words/quiz flow |
| Message signing | done, commit `2ba8761` | `Secp256k1.signRecoverable` + `MessageSigner` (1:1 of `embit_utils.sign_message`); 36/36 byte-identical incl. 0xfd/0xfe compact-size branches; `signmessage` QR + seed-menu flow; settings-gated (upstream default disabled) |
| Address scan & verification | next | upstream `ScanAddressView` + `SeedAddressVerificationView` + flow plumbing |
| Multisig descriptor change verification | pending | upstream `multisig_wallet_descriptor.py` (1185 lines) + `verify_multisig_output` + descriptor scan flow |
| Address Explorer | pending | upstream `ToolsAddressExplorer*` (4 views) |

## v0.2 chunk plan (scope checked against `seedsigner_orig` this session)

| Chunk | Scope | State |
| ----- | ----- | ----- |
| A | `core`: `MnemonicGeneration.kt` 1:1 port of `helpers/mnemonic_generation.py` (dice 50/99 rolls, coin flips 128/256, bytes, checksum w/ 11/23-word `0000` injection, partial final word) + differential crosscheck | done — 48/48 identical incl. the documented 50-roll vector (`:core:crosscheck-mnemonic-gen`), commit `f2aeb80` |
| B | Views: real `ToolsMenuView` (Image/Dice/CalcFinalWord; Address* stay v0.3 stubs), dice length+entry (on-screen 1-6 pad), calc-final-word 4 views, `SeedWordsWarningView`/`SeedWordsView` (4/page), `SeedWordsBackupTestPromptView`/`SeedWordsBackupTestView`/Mistake/Success (4-option word quiz); pending-seed path wired: entropy → words → quiz → `SeedFinalizeView`; entry view gains the two calc-final-word routes | done — see commit after C |
| C | Image entropy: `CameraScanner` entropy-frame mode (Y-plane forward, no decode) + flat-frame rejection + sha256 de-dup pool of 50 + single 1600² still capture; sha256 chain (ANDROID_ID + millis + frames + still) → 16/32 bytes → mnemonic. Platform mapping documented in REASONING.md | done — see commit after C |
| D | Docs + commit | done — commits `9ad2f2a` + docs |

**v0.2 outcome: everything done except the CompactSeedQR dot-grid import (deferred, tracked above).**

Deferrals: CompactSeedQR (dot grid) import needs custom dot-pattern image processing (upstream `decode_qr.py`) — deferred. Seed import via QR covers: word entry, UR `crypto-hdkey`, and the numeric SeedQR format (48/96 digits, 4 per word — ported in Chunk B). BIP-85 child seeds (also a consumer of the words/quiz views) stay v0.3.

## v0.1 closeout (done, device E2E pending user's phone)


## Chunk F — PSBT sign flow [done: code committed, device test pending]

Core: `PsbtParser.kt` — full 1:1 port of upstream `psbt_parser.py` (policy
extraction, fingerprint/derivation verification, change detection, fee math,
OP_RETURN, forged-claim rejection). Differential: `tools/crosscheck_psbt_parser.py`
+ `:core:crosscheck-psbt-parser` → **34/34 cases field-for-field identical** to
the upstream `PSBTParser` (all 7 script types × receive/self-transfer/full-spend,
2-input, taproot, OP_RETURN, 5 forged-claim / malformed exception classes).

App: `PsbtViews.kt` — all 21 upstream PSBT views (select seed → overview →
warnings → math → address/change details → OP_RETURN → finalize → signed QR),
plus `ErrorView`, scan routing (`crypto-psbt` → sign flow, `crypto-hdkey` →
pending seed → finalize), and the `SeedOptionsView` resume hook (newly loaded
seed that matches the pending PSBT jumps straight into the overview).

Known scope deferrals (documented in the code):

| Item | Status |
| ---- | ------ |
| Multisig descriptor loading + change verification (`LoadMultisigWalletDescriptorView`) | v0.3 per roadmap; the button exists and reports the deferral |
| PSBT parse behind a loading screen (upstream runs it in a thread) | parse is inline; fast on modern hardware |

**Bug found in E:** `Networks.fromName` only accepted "mainnet" while `mapNetworkToEmbit` returns "main" → every `Seed.getFingerprint`/`getXpub` call would have thrown at runtime. Fixed in `core` (`fromName` now accepts both). Caught by the new `:app:testDebugUnitTest` (first app-module unit tests).

## Progress so far (v0.0)

| Step | State | Notes |
| ---- | ----- | ----- |
| Build toolchain (JDK 17, Android SDK 34, adb) | done | Non-root install in `~/toolchain` (no sudo on dev machine) — see `docs/build_instructions.md` |
| Gradle project scaffold (`core` module, wrapper) | done | `./gradlew :core:test :app:assembleDebug` green; `aapt dump permissions` shows only CAMERA; minSdk 28. Versions: Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24, SDK 34 |
| `crypto`: BIP39 (wordlist, entropy↔mnemonic, PBKDF2 seed) | done | 9 JVM tests green + differential: `tools/crosscheck_bip39.py` → 209/209 byte-identical to upstream embit. Wordlist: official BIP39 English, `core/src/main/resources/wordlist/english.txt` (SHA-256 `2f5eed53...b24dbda`) |
| `crypto`: hashes (SHA-256/512, HMAC, RIPEMD-160), Base58, Bech32/32m | done | RIPEMD-160 ported from embit's `py_ripemd160.py` (Pieter Wuille, MIT); official test vectors + 1,000,000×'a' multi-block case green |
| `crypto`: secp256k1, BIP32, SLIP-0132, addresses | done | All `:core:test` green (38 at v0.1), incl. fingerprint `8d9cced8`, documented BIP84 zpub + 10 receive/change addresses; BIP39 crosscheck 209/209 byte-identical to embit. `core/build/libs/core.jar` is the testable artifact |
| `psbt`: parse, validate, sign | done | BIP-174 v0+v2 parse/serialize, sighash (legacy, BIP-143, BIP-341), ECDSA sign with embit's DER-length grind loop, BIP-340 tapkey/leaf-sign. Differential: `tools/crosscheck_psbt.py` → 8/8 cases byte-identical to embit (p2pkh, p2sh-p2wpkh, p2wpkh, p2wsh multisig, taproot, multi-input/output, explicit sighash, PSBTv2). Reference forced to embit's pure-Python secp256k1 — no prebuilt `.so` in the verification path (REASONING.md #23) |
| `qr`: UR2 data layer (bytewords, CRC32, CBOR-lite, xoshiro256, fountain encode/decode, single+multi-part UR) | done | 1:1 port of `seedsigner_orig/src/seedsigner/helpers/ur2/` (9 modules) to `core/src/main/kotlin/com/seedsigner/core/qr/ur2/`. Differential: `tools/crosscheck_ur2.py` + `:core:crosscheck-ur2` → 5/5 cases byte-identical to the Python reference (decode with parts fed in reversed order + re-encode). 8 JVM round-trip tests incl. fountain recovery from dropped frames. SeedQR *display* framing is an `app`-layer concern (v0.1), not `core` |
| `qr`: vendored ZXing 3.5.4 decoder (source-only) + `VENDORED.md` | done | Unmodified Java source from tag `zxing-3.5.4`; decoder (55 files) + encoder (11 files) vendored in main (the app renders QRs). Real-path crosscheck: `tools/crosscheck_qr.py` + `:core:crosscheck-qr` — Python `qrcode` renders all UR2 parts at upstream densities (10/30/120 cbor bytes) → vendored decoder reads 277/284; the 7 failures are a pinned upstream ZXing 3.5.4 detector limitation (pristine ZXing fails identically; OpenCV decodes them fine), mitigated in the app with rotation/offset retries. JVM tests: `ZxingDecodeTest` (round-trip, PNG re-encode, noise + rescale, garbage rejection). Upstream density sizes sourced from `seedsigner_orig/.../encode_qr.py` (`qr_max_fragment_size`) |

## Progress so far (v0.1)

| Step | State | Notes |
| ---- | ----- | ----- |
| A: `core` UR payload builders (`Cbor.kt`, `UrTypes.kt`) | done | 10/10 byte-identical vs the commit-pinned `urtypes` (`:core:crosscheck-urtypes`), commit `ce14bbf` |
| B: `app` shell (MainActivity, Controller, View/Ui, Settings, SeedStorage, main/seed/settings views) | done | `:app:assembleDebug` green, APK permissions = CAMERA only, commit `77b0642` |
| C: camera2 scanner + QR display | done | `CameraScanner` (YUV_420_888 → `GlobalHistogramBinarizer` → `QRCodeReader`, 4-rotation sweep), `QrDisplay` (bitmap + `ur2Parts` at 10/30/120), `ScanView` (live preview, UR-part accumulation, runtime CAMERA permission), `QrDisplayView` (2 s/part), commit `92837fa` |
| D: load-seed flow | done | mnemonic keyboard, invalid/finalize/passphrase/discard views, commit `9edade9` |
| E: xpub export flow | done | 7 views 1:1, `Derivation` table, 3 QR formats (static, Specter pXofY, UR crypto-account), `XpubTest` pins the dice vectors, commit `3b227f8` |
| F: PSBT sign flow | done | `PsbtParser.kt` 34/34 crosscheck (`:core:crosscheck-psbt-parser`), `PsbtViews.kt` (21 views), scan routing, resume hooks, commit `7037407` |
| G: CI + docs | done | `.github/workflows/ci.yml` (`:core:test` + `:app:assembleDebug` + APK artifact), roadmap/README/code-structure updated, commit `85981f9` |

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

## v0.1 build plan — all chunks done

The plan (user: "finish all development" — v0.1 → v0.4 in order) is complete; the per-chunk state is in the v0.1 table above. Implementation notes worth keeping:

- Controller maps `Destination.viewArgs` → View constructor params via reflection (`-java-parameters`); every viewArgs key must name a real constructor parameter.
- E: `Derivation.standardDerivationPath` (44'/49'/84'/86'/45'/48' table); `LEGACY_P2PKH`/`CUSTOM_DERIVATION` constants added (defaults unchanged). BIP-85 child seeds are v0.3 scope.
- F: upstream `PSBTParser.__init__` parses immediately — the Kotlin port mirrors this, so verification exceptions come out of the constructor.

Then v0.2 (word picker, dice, camera entropy, passphrase, SeedQR/CompactSeedQR transcription), v0.3 (address verification, explorer, message signing, BIP85, custom paths), v0.4 (multi-key signing, Electrum, SettingsQR, polish).

Upstream references (trusted, in-session checked): `seedsigner_orig/src/seedsigner/views/*.py` (52 views), `models/encode_qr.py` (QR payload contract: static xpub string, Specter pXofY, UR crypto-account with script-expression tags 400/401/403/404/409, UR crypto-psbt), `urtypes` source at commit `7fb280ea` (pinned in upstream requirements.txt) → `/tmp/urtypes-7fb280ea*/`.

Repo note: upstream CI (`.github/`) and `.gitmodules` were removed as Python-specific; `.gitignore` is now Android-oriented. Our CI (`.github/workflows/ci.yml`) landed in Chunk G.

## Working rules

* Repo: <https://github.com/stevexyz/seedsignerapk> — **never push**, the user pushes.
* Never enter `old*` directories; never read `PRIVATE*` files.
* `seedsigner_orig/` is read-only.
* Identical-to-upstream docs stay referenced, not copied.
* Keep everything as simple as possible — always.
