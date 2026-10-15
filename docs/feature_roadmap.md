# Feature Roadmap

Binding requirements: [spec.md](spec.md). First real milestone: end-to-end flow in testnet (load seed → export xpub → scan PSBT → review → sign → show signed PSBT).

## v0.0 — Self-contained `core`

* `crypto`: secp256k1, BIP39, BIP32, SLIP-0132 — in-repo
* `psbt`: parse, validate, sign — in-repo
* `qr`: encoder (UR2, SeedQR, animated chunking) — in-repo
* `qr`: decoder from camera frames — **vendored ZXing** (Apache-2.0), attribution in `VENDORED.md`
* Every routine tested against the repo test vectors ([dice_verification.md](dice_verification.md)) and differential-tested against `seedsigner_orig`

## v0.1 — Core signing flow (testnet) ✅ (code complete; device E2E pending)

* ✅ `app` shell: one Activity, classic framework Views, manifest with camera permission only
* ✅ Stateless in-memory seed holder
* ✅ Camera QR scanning (camera2 + `core` decoder), animated on-screen QR display
* ✅ Load seed: word entry + SeedQR scan
* ✅ xpub export (single sig + multisig)
* ✅ PSBT scan → review → sign
* ✅ Settings (network), plain-file persistence
* ✅ CI: build + unit tests (`.github/workflows/ci.yml`: `:core:test` + `:app:assembleDebug`)

## v0.2 — Seed creation (mostly done; CompactSeedQR dot-grid deferred)

* ✅ New seed from dice rolls (50/99 rolls, [dice_verification.md](dice_verification.md) vectors pinned in the crosscheck)
* ✅ New seed from camera image entropy (50-frame pool + full-res still, NIST-style flat/repeat rejection)
* ✅ Calc 12th/24th word (coin-flip / word / zero entropy + checksum display)
* ✅ Generated-words review: warning, paged display, optional 4-option backup quiz
* ✅ BIP39 passphrase (13th/25th word) — landed in v0.1 (load-seed flow)
* ✅ Seed import via QR: word entry, UR `crypto-hdkey` scan (v0.1), numeric SeedQR format (48/96 digits)
* ⏳ CompactSeedQR dot-grid import — needs custom dot-pattern image processing; deferred (roadmap item, tracked in current-task.md)

## v0.3 — Verification and utilities (complete)

* ✅ Address scan and verification (`ScanAddressView`, single-sig brute-force thread, nested-segwit sig-type chooser; `:core:crosscheck-address-verification` 83/83)
* ✅ Full PSBT change-address verification + multisig descriptor model (`core/descriptor/Descriptor.kt`, `:core:crosscheck-descriptor` 96/96 byte-identical; descriptor scan: generic / multisig-setup-file / Specter animated; `PSBTChangeDetailsView` verifies via `verifyMultisigOutput`)
* ✅ Address Explorer (seed sources + descriptor sources, receive/change pages, per-address QR)
* ✅ Message signing (`signmessage` QR + seed-menu flow; compact signatures byte-identical to upstream, `:core:crosscheck-message-sign` 36/36)
* ✅ BIP85 child seeds (39/39 incl. official spec vectors)
* ✅ Custom derivation paths (xpub export, landed in v0.1 Chunk E)

## v0.4 — Polish

* ✅ Electrum seed support (12-word Segwit format, `ElectrumSeed` model + load flow; non-standard seeds excluded from PSBT signing)
* ✅ SettingsQR scanning (`settings::v1` parse + apply, 1:1 of upstream `Settings.parse_settingsqr`)
* ✅ Seed backup flow (SeedQR transcribe chain: format → warning → whole QR → zoomed-in zone view → scan-to-confirm; compact SeedQR display via new vendored byte-mode encoder)
* ⏳ Multisig: sign with multiple keys at once (signing one key per PSBT pass already works; the upstream "multiple cosigner seeds in one pass" UX is the remaining delta)
* ⏳ Localization (English first — string pass is in place, no i18n framework)
* ⏳ UI refinements (device E2E on a real phone will drive these)

## Beyond

* Emulated device: draw the SeedSigner case on screen (case + buttons + inner display) for a faithful emulation feel
* PGP message signing
* QR display controls (framerate, density)
* Reproducible signed release APKs
