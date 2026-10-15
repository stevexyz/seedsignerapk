# Current Task

**Status: [done] v0.3 complete; v0.4 feature scope (Electrum, SettingsQR, seed backup/SeedQR transcribe) complete. All commits land through `90b7afb`. Remaining: on-device E2E on the user's phone (v0.1 → v0.4), then the user-requested report.**

## This pass (v0.3 → v0.4)

| Item | State | Commit |
| ---- | ----- | ------ |
| Address scan & single-sig verification (`ScanAddressView`, `AddressVerificationStartView`, sig-type chooser, brute-force thread with Skip 10/Cancel, success view) + core `Address.singleSigAddress` / `AddressType` (83/83 crosscheck) | done | `6d3f0f5` |
| Address Explorer (select source: loaded seed / scan / descriptor / 12-24 entry; receive/change pages of 10; per-address QR; resume hooks) | done | `a80807c` |
| Multisig descriptor model (`core/descriptor/Descriptor.kt`, 1:1 of embit basic-multisig: parse/to_string, `derivedScriptPubkey`, `owns`, policy) + `PsbtParser.verifyMultisigOutput` (96/96 byte-identical crosscheck) | done | `bfa6261` |
| Descriptor app flows (`ScanWalletDescriptorView` — generic + setup-file + Specter animated accumulation; `LoadMultisigWalletDescriptorView`; `MultisigWalletDescriptorView` context routing; PSBT change-details real verification + failure path; multisig address brute-force; explorer descriptor sources) | done | `3b6620d` |
| Electrum seeds (`ElectrumSeed`: version-100 check + PBKDF2 2048, 1:1; `Seed` → abstract + `Bip39Seed`; warning + 12-word entry flow) | done | `ee4bbcc` |
| Seed backup flow (upstream `SeedOptionsView` button order + BACK→MainMenu; `SeedBackupView`; full SeedQR transcribe chain: format/whole/zoomed-in with zone guidelines/confirm-scan/wrong/success/invalid; `SeedQrEncoder` standard+compact; vendored ZXing byte-mode `encodeBytes` — documented in `VENDORED.md`) | done | `ee4bbcc` |
| SettingsQR ingest (`SettingsQr.parse` 1:1; `SettingsIngestSettingsQRView`; `settings::` scan routing; unit tests) | done | `90b7afb` |

All crosschecks re-verified green after the vendored-ZXing change:
209 BIP39 · 8 PSBT · 34 PSBT parser · 10 urtypes · 277/284 QR (7 pinned
upstream-limitation set) · 48 mnemonic gen · 39 BIP-85 · 36 message sign ·
96 descriptor · 83 address verification · 38 `:core:test` · 7 `:app:test`.

## Known deltas vs upstream (documented, deliberate)

| Item | Note |
| ---- | ---- |
| CompactSeedQR **dot-grid import** (scan a physical compact SeedQR) | Still deferred: needs custom dot-pattern image processing. Compact SeedQR **display** works; numeric-SeedQR + compact-bytes + plain-mnemonic scan works for confirmation |
| Multisig "sign with multiple keys at once" | One cosigner seed per PSBT pass is ported (upstream behavior when one matching seed is loaded); the batched multi-seed UX is the remaining delta |
| PSBT parse runs inline | Upstream runs it on a thread behind a loading screen; ours is inline (fast) |
| Platform entropy mapping | Image-entropy uses ANDROID_ID serial + Y-plane frames (REASONING.md) |

## Next (when the user is ready)

1. On-device E2E: `adb install app/build/outputs/apk/debug/app-debug.apk`, then run the flows: load seed (words / ScanQR / dice / image), xpub, PSBT sign (single + multisig w/ descriptor), message sign, address verify (scan a wallet address), explorer, SeedQR transcribe + confirm, SettingsQR.
2. Write the user-requested report.
