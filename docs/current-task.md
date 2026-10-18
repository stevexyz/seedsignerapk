# Current Task

**Status: done.** Every scan path is E2E-verified through the real camera
pipeline (table below), the final re-check is complete, and all bugs the
pass surfaced are fixed + regression-tested (mixed-segment QR truncation,
accumulation-view NPE, stale-APK/disk-full install). Committed
(`3c40e37`); remaining: user pushes.

## Restart here

| Thing | State |
| ----- | ----- |
| Emulator | `seedtest` AVD (pixel_7, android-34 google_apis x86_64, KVM, headless) in `~/toolchain/android-sdk/`; boot: `~/toolchain/android-sdk/emulator/emulator -avd seedtest -no-window -no-audio -gpu swiftshader_indirect -camera-back webcam0`; wait until `adb shell getprop sys.boot_completed` = 1. **Never move the webcam — ask the user first.** |
| Camera | webcam0 attached by the user; QR feed = `http://192.168.2.102:8000/scan.html` (auto-refresh, serves `/tmp/e2e/`; `cd /tmp/e2e && python3 -m http.server 8000`) |
| APK | debug APK with all audit fixes installed on `emulator-5554`, CAMERA granted; UI driven via `adb shell input tap` (1080×2400) + `uiautomator dump` |
| On device | settings = Testnet (persisted in `files/settings.json`); seeds are memory-only — the app is currently on the Select Signer screen after a binary-PSBT scan (no seed loaded in the last session; load via the mnemonic QR `cam_mnemonic.txt.png` → fingerprint `8d9cced8`) |
| Camera sizing | feed QRs must stay ≤ ~740 px on the 1280×960 canvas (the webcam FoV crops beyond that); binary PSBT QR rendered at 657 px. If a decode "mysteriously" fails, screencap the emulator and run ZXing on the preview before blaming the app |
| Env | `export JAVA_HOME=$HOME/toolchain/jdk-17.0.20.1+1 PATH=$JAVA_HOME/bin:$PATH` and `export PATH=$HOME/toolchain/android-sdk/platform-tools:$PATH` (adb) |

## Next (ordered)

1. User pushes the commits (the first push also first-runs CI with the new `:app:test` step).

## Known deltas vs upstream (deliberate, documented)

| Item | Note |
| ---- | ---- |
| CompactSeedQR **dot-grid import** | Deferred (custom dot-pattern image processing). Display works; numeric-SeedQR, compact-bytes (entropy round-trip via `mnemonicFromEntropy`) and plain-mnemonic scan work (confirm-scan path) |
| Multisig "sign with multiple keys at once" | One cosigner seed per PSBT pass ported; the batched multi-seed UX is the remaining delta |
| PSBT parse runs inline | Upstream: thread + loading screen. Ours: inline (fast) |
| Platform entropy mapping | ANDROID_ID serial + Y-plane frames (REASONING.md) |

## On-device E2E (webcam pass, `seedtest` AVD)

| Check | Result |
| ----- | ------ |
| App launch + Home, Settings → Testnet, A2 persistence, dice seed, xpub QR, A1 stored-seed Discard | ✅ (previous pass, all green) |
| F1 SeedQR (numeric mode) scan | ✅ fingerprint `8d9cced8` |
| F2 mnemonic text QR scan | ✅ `8d9cced8` |
| F3 4-letter mnemonic QR scan | ✅ `8d9cced8` |
| F4 compact 16-byte byte-mode SeedQR | ✅ (first device-level byte-mode decode; BYTE_SEGMENTS fix) |
| PSBT scan (byte mode) → Select Signer → `8d9cced8` recognized | ✅ |
| PSBT sign → all 8 UR2 parts captured → repo `URDecoder` reassembles (232 B, `psbt\xff`) | ✅ |
| **BIP143 signature, fully independent verification** (own preimage from official BIP vectors + pubkey verify) | ✅ app's partial sig VERIFIES — app signer is correct (the earlier "discrepancy" was the verification harness: BIP143 v0 uses plain dSHA256, not the BIP341 tagged hash; REASONING.md) |
| **Change detection** | ✅ after fixture fix: Overview shows Change 4,998 sats — was a **fixture bug** (change output had no `bip32_derivations`; plus a malformed 23-byte destination script). Parser unchanged; regression test added (`ChangeDetectionTest`, core) |
| psbt.b64 / psbt.b43 QR scan | ✅ both decode → same Overview values (Spend 0.5 BTC, Change 4,998, Fee 2,000) |
| **Specter split PSBT** (3 animated `pNofM` frames, `ScanSpecterPsbtView`) | ✅ reassembled on device → Select Signer → Overview correct. Surfaced the **mixed-segment truncation bug** (frame 3 = 107 B BYTE + 23 ALNUM; `byteSegments[0]` silently dropped the tail) → `extractPayload` fix + on-wire-matrix regression test |
| **BBQr split PSBT** (4 `B$HP…` hex frames, `ScanBbqrPsbtView`) | ✅ reassembled on device → Overview correct |
| Binary PSBT re-scan after the `extractPayload` change | ✅ (65-module QR at 657 px; the old 780 px render was cropped by the camera FoV — see restart table) |
| A1 pending-seed branch (calc-12th-word Discard) | ✅ typed 11 words via scripted `adb input tap` → finalize-with-zeros → final word `about` (fp `73c5da0a`) → Discard → clean exit to Home with **zero loaded seeds** (the exact former crash/wrong-seed scenario) |

## Verification numbers (all re-run green after every change)

209 BIP39 · 8 PSBT · 34 PSBT parser · 10 urtypes · 277/284 QR (7 pinned
upstream-limitation set) · 48 mnemonic gen · 39 BIP-85 · 36 message sign ·
96 descriptor · 83 address verification · 47 `:core:test` (incl.
`ChangeDetectionTest`, `SplitQrDecodersTest`) · 17 `:app:test` per variant
(both variants; incl. `CameraScannerDecodeTest`, `CameraScannerRotateTest`).
Exact commands:
[build_instructions.md](build_instructions.md).

## History (newest first)

### Documentation review pass 3 (this session)

Full re-read of every doc against code + `seedsigner_orig/`. Four fixes: E1 `VENDORED.md` EC-level claim was wrong (upstream pins L, `helpers/qr.py` — app already matched; sentence rewritten), E2 display QR quiet zone 1 → 3 modules (upstream `border=3`), E3 this file's stale numbers/status (47 core / 17 app; `3c40e37`), E4 `code_structure.md` `qr/zxing` phrase. Full finding table in REASONING.md.

### Camera E2E completion pass (this session)

- **All remaining scan paths verified through the real camera**: Specter split PSBT (3 frames), BBQr split PSBT (4 frames), b64/b43 PSBT, binary PSBT, A1 pending-seed Discard (scripted 11-word entry: dump → letter-key coords → one `adb input tap` burst per word, candidate tap at (540,484)).
- **Mixed-segment QR truncation bug found + fixed** (the Specter cycle kept failing as "not valid base64"): `qrcode` 8.x splits one payload into BYTE+ALPHANUMERIC segments; the old code returned the first byte segment (107 of 130 bytes). `CameraScanner.extractPayload` now: ASCII → `result.text`, binary → all `BYTE_SEGMENTS` concatenated; `TRY_HARDER` hint added. Regression test embeds the exact 49×49 module matrix of the failing frame as hex.
- **Accumulation-view NPE found + fixed**: the re-run loop forwards *any* decoded QR to the view's `onResult` — a mnemonic on the monitor mid-cycle crashed `ScanSpecterPsbtView` in `find()!!`. Both split views now ignore non-matching payloads; decoders throw the caught `SplitQrException` instead of NPE/IndexOutOfBounds.
- **Stale-APK lesson**: emulator internal storage was full (27 MB of on-device debug dumps), so `adb install` failed silently and three "decode" mysteries were one old binary. Rule recorded in REASONING.md: md5-compare the pulled `base.apk` after installs; clean device artifacts.
- **Camera FoV limit pinned**: 780 px QR cropped at right/bottom (screencap → ZXing on the preview showed the partial QR); fixtures now ≤ 740 px.

### Full re-check pass (this session)

- **Split PSBT QR formats ported** (were missing): `core/qr/SplitQrDecoders.kt` (Specter `p{N}of{M}` base64 + BBQr `B$…` H/P/Z, 1:1 of upstream's animated decoders; BBQr classifier fixed from upstream's `[2HZ]` typo to `[PHZ]`) + `ScanSpecterPsbtView`/`ScanBbqrPsbtView` (companion accumulation, like the existing Specter-descriptor view) + routing in `ScanResultView.routeTextQr` (before the descriptor checks, like upstream). 7 new core tests (SplitQrDecodersTest). Suite: 47 core / 16 app per variant, all green.

### Signature verification + change-detection fixture (this session)

- **BIP143 verified independently**: hand-rolled preimage (official BIP143 vectors pinned the final digest as plain dSHA256 — the BIP341 tagged hash is taproot-only) → byte-identical sighash to embit → app's partial sig verifies. No app change; the harness had two bugs (wrong final digest, scriptCode off-by-one).
- **"Change: 0 sat" was a fixture bug**: the E2E PSBT's change output carried no `bip32_derivations` (and the destination script had a stray varint byte). Regenerated `/tmp/e2e/psbt.{bin,b64,b43}` with full-from-root paths + root fingerprint `8d9cced8` on both input and change output. Added `core/…/ChangeDetectionTest.kt` (2 tests: claim→change, no claim→destination) embedding the fixture.
- **`/tmp/e2e/scan.html`** now self-reloads every 5s and reads its image from a `current_src` line — `/tmp/e2e/show.sh <basename>` switches the QR the webcam sees without a manual browser reload (the old page had no reload, which is why the camera kept showing a stale frame).

### Final structure review (commit `ca2efb2` or newer)

- This file restructured: status + restart + next on top, compact ledgers below (full finding texts live in the pass commits + REASONING.md).
- `build_instructions.md` release-verification section marked as future (no release exists yet); README status link de-duplicated.
- REASONING.md gained the A2 (persistence wiring) and A3 (type-tag reachability) pitfall rows, so the compressed ledgers keep their believed/actual/rule records.
- `dice_verification.md` re-verified in full: all 10 listed addresses + both menu labels recomputed byte-exact.

### Documentation review pass — `88f6bd6`

Full re-read of every doc; claims recomputed, not re-trusted.

| # | Fix |
| - | --- |
| D1 | `developer_tips.md` SeedQR example was truncated (42 of 48 digits — wouldn't scan); recomputed with embit: `163803200074026607961827144306700411123603780160`; one-liner → heredoc |
| D2 | this file's stale pre-audit restart block — replaced |
| D3 | README: pointed at non-existent releases; status now "partially verified" |
| D4 | `debug_crash.md` "debug builds log more" claim (false) — replaced with the never-log-seed-material rule |
| D5 | roadmap v0.1 device-E2E wording; CI task list |
| D6 | `code_structure.md`: vendored ZXing is a separate `com.google.zxing` package under `core/src/main/java/`, not `core.qr` |
| D7 | CI never ran `:app:test` — added |

Verified byte-exact: all `dice_verification.md` vectors (99/50-roll words,
fingerprint `8d9cced8`, zpub, all 10 addresses), 66 vendored files, 25
screenshots, all README links, 11 crosscheck tools, REASONING consistency.

### Audit-fix pass — `eeaaa16` (code) + `5e80cd3` (docs)

Full docs + source re-read; every item fixed, suite re-run green.
Believed/actual/rule records for the reflection bugs live in REASONING.md.

| # | Where — finding → fix |
| - | --------------------- |
| A1 | `SeedDiscardView` lacked upstream's pending-seed mode (calc-final-word "Discard" targeted seed 0 / crashed with none) → pending mode ported (`seedIndex` absent → wipe `pending_mnemonic`) |
| A2 | `Settings.load()` had zero call sites, `save()` one → persistence silently dead → load at start, save on every change; E2E-verified |
| A3 | `ur.type == "bytes"` branch dead (scanner only emits `"raw"`) → compact SeedQR fallback `decodeCompactSeedQr` (16/32-byte entropy → mnemonic) in confirm-scan |
| A4 | No orientation lock; camera not stopped on activity destroy → portrait lock + `onSurfaceTextureDestroyed` stops every scanner |
| A5 | `PowerOptionsView` unreachable and its "power off" (`finishAffinity`) can't wipe memory → deleted; `UnhandledExceptionView` "Power off" now `killProcess` |
| A6 | `viewArgs` read in plain property initializers always saw the empty map (assigned after construction): settings no-op loop, `QrDisplayView` NPE → `by lazy` + guard comment on `View.viewArgs` |
| B1 | `instantiateView` silently hit Kotlin's implicit no-arg ctor, dropping all viewArgs (E2E NPE) → Python-kwargs matcher + `ViewInstantiationTest` invariant (≤1 named ctor per View; every new View joins its list) |
| B2–B10 | `Locale.US` in `fmtAmount`; dead `psbt == null`; `ElectrumSeed` redundant init; broken `Verify addr` template; config-slot `require(slot >= 0)`; dead PSBT branch removed; `multisigWalletDescriptor: Descriptor?`; test-scope dep carve-out written into spec §1.3 |
| C1–C7 | developer_tips (offline-only, labels, logcat, am start); version aligned to 0.4.0 (build.gradle + VersionView + README); dice doc menu labels; code_structure/roadmap persistence wording; manifest comment |

### Docs audit — `7fe6eed`

- `build_instructions.md`: full verification suite (11 crosschecks, exact commands).
- Real bug fixed then: `CrosscheckPsbtParser` required `outPath` its documented invocation never passed → now optional (default `core/build/psbt-parsed.txt`).
- `developer_tips.md`: online generator link replaced with offline embit generation; real app id in adb examples.

## Pass log

| Item | Commit |
| ---- | ------ |
| Address scan & verification + `Address.singleSigAddress`/`AddressType` (83/83) | `6d3f0f5` |
| Address Explorer (seed/scan/descriptor sources; receive/change; per-address QR) | `a80807c` |
| Multisig descriptor model (`core/descriptor`, 96/96 byte-identical; `verifyMultisigOutput`) | `bfa6261` |
| Descriptor app flows (Specter accumulation, setup file, PSBT change verification, brute-force) | `3b6620d` |
| Electrum seeds (1:1) + seed backup flow + SeedQR transcribe chain + byte-mode ZXing | `ee4bbcc` |
| SettingsQR ingest (1:1) + docs closeout | `90b7afb` / `48e7301` |
| Docs audit + verification suite + `CrosscheckPsbtParser` fix | `7fe6eed` |
| Audit-fix pass (A1–A6, B1–B10, C1–C7) + kwargs `instantiateView` + `ViewInstantiationTest` | `eeaaa16` / `5e80cd3` |
| Documentation review pass (D1–D7; dice/tips values recomputed) + `:app:test` in CI | `88f6bd6` |
| Camera E2E completion: extractPayload mixed-segment fix + guards + TRY_HARDER + docs | `3c40e37` |
