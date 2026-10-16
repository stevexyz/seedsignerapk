# Current Task

**Status: [in progress]** finishing device E2E. All code and docs work is
complete and committed (v0.4 scope + three audit passes); `:core:test` +
`:app:test` (both variants) + `:app:assembleDebug` are green; on-emulator
E2E is partially verified (table below). The remaining items need a camera
source on the dev host (webcam).

## Restart here

| Thing | State |
| ----- | ----- |
| Emulator | `seedtest` AVD (pixel_7, android-34 google_apis x86_64, KVM, headless) in `~/toolchain/android-sdk/`; boot: `~/toolchain/android-sdk/emulator/emulator -avd seedtest -no-window -no-audio -gpu swiftshader_indirect -camera-back emulated`; wait until `adb shell getprop sys.boot_completed` = 1 |
| Camera | `emulated` — no webcam on the dev host; QR scan-in flows blocked (see Next) |
| APK | debug APK with all audit fixes installed on `emulator-5554`, CAMERA granted; UI driven via `adb shell input tap` (1080×2400) + `uiautomator dump` |
| On device | settings = Testnet (persisted in `files/settings.json`); no seeds loaded (the E2E seed `65fb43fe` was discarded at the end of the session) |
| Env | `export JAVA_HOME=$HOME/toolchain/jdk-17.0.20.1+1 PATH=$JAVA_HOME/bin:$PATH` and `export PATH=$HOME/toolchain/android-sdk/platform-tools:$PATH` (adb) |

## Next (ordered)

1. User pushes the commits (the first push also first-runs CI with the new `:app:test` step).
2. QR scan-in E2E — blocked until a webcam is attached (restart emulator with `-camera-back webcam0`) or an emulated QR surface is picked.
3. PSBT E2E (embit-built testnet PSBT → scan → review → sign → read back).
4. A1 pending-seed branch on device (Tools → Calc 12th/24th word → 11 words → Discard).
5. Close out the "device E2E" milestone (roadmap v0.1/v0.4 lines); re-run the build_instructions verification suite once per change.

## Known deltas vs upstream (deliberate, documented)

| Item | Note |
| ---- | ---- |
| CompactSeedQR **dot-grid import** | Deferred (custom dot-pattern image processing). Display works; numeric-SeedQR, compact-bytes (entropy round-trip via `mnemonicFromEntropy`) and plain-mnemonic scan work (confirm-scan path) |
| Multisig "sign with multiple keys at once" | One cosigner seed per PSBT pass ported; the batched multi-seed UX is the remaining delta |
| PSBT parse runs inline | Upstream: thread + loading screen. Ours: inline (fast) |
| Platform entropy mapping | ANDROID_ID serial + Y-plane frames (REASONING.md) |

## On-device E2E (latest pass, `seedtest` AVD, headless)

| Check | Result |
| ----- | ------ |
| App launch + Home | ✅ renders |
| Settings → Advanced → Bitcoin network → Testnet (after A6 fix) | ✅ |
| A2: force-stop → `run-as … cat files/settings.json` → relaunch | ✅ `"network":"T"`, checkmark on Testnet |
| Dice 50×1 → warning (partial-args kwargs path) → words (3×4) → quiz Skip → finalize | ✅ fingerprint `65fb43fe` |
| SeedOptions → Export xpubs → Single Sig → Native Segwit → Static → QR | ✅ QR renders (exercises the `QrDisplayView` lazy fix) |
| A1 stored-seed branch: Discard seed → Remove | ✅ correct seed targeted; Seeds menu empty; no FATAL in logcat |
| A1 pending-seed branch (calc-12th-word Discard) | code-reviewed; needs an 11-word typed entry on device — Next item 4 |

## Verification numbers (all re-run green after every change)

209 BIP39 · 8 PSBT · 34 PSBT parser · 10 urtypes · 277/284 QR (7 pinned
upstream-limitation set) · 48 mnemonic gen · 39 BIP-85 · 36 message sign ·
96 descriptor · 83 address verification · 38 `:core:test` · 9 `:app:test`.
Exact commands: [build_instructions.md](build_instructions.md).

## History (newest first)

### Final structure review (this commit)

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
