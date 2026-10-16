# Current Task

**Status: [in progress] closing out the audit-fix pass (2026-07). All audit
items A1–A5, B1–B10, C1–C7 are FIXED in source (details in the "Audit
pass" section below); the instantiateView reflection fix from the E2E
session is included and now backed by `ViewInstantiationTest`. `:core:test`
+ `:app:test` (both variants) + `:app:assembleDebug` are green.
Remaining: commit, then on-device E2E (blocked on a webcam for QR scan-in;
see Next steps).**

## Where it stopped (restart here)

### E2E setup state

| Thing | State |
| ----- | ----- |
| Emulator | `seedtest` AVD (pixel_7, android-34 google_apis x86_64, KVM, headless) in `~/toolchain/android-sdk/`; boot: `~/toolchain/android-sdk/emulator/emulator -avd seedtest -no-window -no-audio -gpu swiftshader_indirect -camera-back emulated`; wait `adb shell getprop sys.boot_completed` = 1 |
| Camera | set to `emulated` (no webcam attached to the dev host yet; QR scan-in flows still blocked) |
| APK | debug APK installed on `emulator-5554`, CAMERA granted, app launches, menu driven via `adb shell input tap` (1080×2400; menu buttons y≈1572/1728/1884/2040/2196) |
| Seed entry | 12-word testnet seed entered via tap-driven on-screen keyboard: `smoke chimney announce candy glory tongue refuse fatigue cricket once consider beef`. Word screens + fingerprint screen render (fingerprint value changes with tap typos — entry is lossy but rendering is verified) |

### The bug found and fixed (UNCOMMITTED)

**Believed:** `Controller.instantiateView` matched a view constructor only
on an EXACT key-count + name match against viewArgs.
**Actual:** any call site whose viewArgs map doesn't carry ALL constructor
param names (e.g. `SeedBackupView` → `SeedWordsWarningView` passes only
`seedIndex`; the view has 4 params) silently fell back to the no-arg
constructor, leaving every constructor property null → NPE in
`SeedWordsView.run` (`activeSeed()` null) on
"seed options → View seed words → I Understand".
**Fix (in source now):**
1. `Controller.instantiateView` — Python-kwargs semantics: pick the most
   specific constructor whose parameters are ALL named in viewArgs
   (extras ignored); no-arg constructor as fallback.
2. `SeedWordsViews.kt` / `Bip85Views.kt` — the warning→words transitions
   now pass `seedIndex` through (full key sets).
**Rule that prevents the repeat:** never add a view constructor param
without (a) passing it from every constructing call site or (b) proving the
no-arg fallback can't be hit. Verify with a JVM test that simulates
`instantiateView` (a scratch `app/src/test/kotlin/com/seedsigner/app/ReproTest.kt`
was used for the diagnosis — **delete it before committing**).

### Next steps (numbered)

1. `rm app/src/test/kotlin/com/seedsigner/app/ReproTest.kt`
2. Rebuild + reinstall: `./gradlew :app:assembleDebug` then
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Re-enter the seed; verify the path that crashed: Seeds → (seed) →
   Backup seed → View seed words → I Understand → words pages → Done
4. Continue E2E: Export xpubs (screenshot), then QR scan-in.
5. QR scan-in needs a camera source: user's webcam (`-camera-back
   webcam0` after reboot) or an emulated QR surface — pick when the webcam
   lands.
6. PSBT E2E: build a testnet PSBT with `~/toolchain/venv` (embit), scan,
   review, sign, read back the signed PSBT.
7. Commit: instantiateView fix + seedIndex passthrough + this doc state.
   Then close out the E2E milestone and write the user-requested report.

### Env for restart

```
export JAVA_HOME=$HOME/toolchain/jdk-17.0.20.1+1 PATH=$JAVA_HOME/bin:$PATH
export PATH=$HOME/toolchain/android-sdk/platform-tools:$PATH   # adb
```

## History this session (before E2E)

### Docs audit (user: "recheck all documentation and update where needed") — commit `7fe6eed`

- `docs/build_instructions.md`: full verification suite (11 crosschecks,
  exact commands, all re-verified passing after the change).
- **Real bug fixed during audit:** `CrosscheckPsbtParser.kt` required
  `outPath` (args[1]) but its documented invocation passes only one arg →
  AIOOBE. `outPath` is now optional (default
  `core/build/psbt-parsed.txt`).
- `docs/developer_tips.md`: iancoleman.io link replaced with offline embit
  mnemonic generation (docs must not point at online services); real app
  id `com.seedsigner` in the adb examples.
- Re-read, no changes needed: README, spec, feature_roadmap,
  code_structure, REASONING, VENDORED, SECURITY, debug_crash,
  dice_verification.

### E2E verification numbers (all re-run green after every change)

209 BIP39 · 8 PSBT · 34 PSBT parser · 10 urtypes · 277/284 QR (7 pinned
upstream-limitation set) · 48 mnemonic gen · 39 BIP-85 · 36 message sign ·
96 descriptor · 83 address verification · 38 `:core:test` · 9 `:app:test`
(+2 `ViewInstantiationTest` from the audit pass).

## Audit pass (full docs + source re-read) — ALL FIXED this pass

Findings below, numbered for tracking (A = real bug, B = minor code,
C = doc incoherence). Every item was fixed and the suite re-run green
(`:core:test` + `:app:test` both variants + `:app:assembleDebug`). The B1
area surfaced a deeper reflection finding — see the new REASONING.md
pitfall row (Kotlin emits an implicit no-arg ctor for all-defaulted View
constructors; both the old exact-match AND the first kwargs matcher
silently hit it; the matcher is now invariant-guarded by
`ViewInstantiationTest`).

### Bugs (behavior)

| # | Where | Finding |
| - | ----- | ------- |
| A1 | `SeedViews.SeedDiscardView` / `ToolsViews.ToolsCalcFinalWordDoneView` | "Discard" in the calc-final-word flow opens `SeedDiscardView` with no viewArgs → `seedIndex` defaults to 0 → confirms deletion of the **first loaded seed** (crashes to UnhandledExceptionView when none loaded). Upstream `SeedDiscardView(seed=None)` discards the **pending seed** (`is_pending_seed`); our port lacks that mode. REASONING.md claimed this routing was verified 1:1 — it is not, because the view itself was never ported in its pending-seed form. |
| A2 | `Settings.kt` + `SettingsViews.kt` | `Settings.load(context)` is **never called** (no call site anywhere), and `save()` is called only on SettingsQR ingest. So (a) network/density/etc. changes made in the Settings UI are never persisted, (b) a SettingsQR write is wiped on next launch. Roadmap v0.1 claims "Settings (network), plain-file persistence ✅" — false. Fix: `Controller`/`MainActivity` calls `settings.load(this)` at start; `SettingsEntryUpdateView.onResult` + ingest call `settings.save(activity)`. |
| A3 | `SeedBackupViews.SeedTranscribeSeedQRConfirmScanView` | `if (ur.type == "bytes")` is dead: `ScanView.handleQr` only ever produces `UR("raw", …)` for non-UR QRs, never type `"bytes"`. A scanned **compact SeedQR** (byte-mode) therefore falls into `decodeSeedQr(String(bytes, UTF-8))` → garbage → "not a SeedQR". Contradicts current-task's "compact-bytes … scan works" and the REASONING.md v0.4 note. Fix: treat `ur.type == "raw"` whose bytes are not valid UTF-8 text as compact-SeedQR bytes (or always try `Bip39.mnemonicFromEntropy(ur.cbor)` when text decode fails). |
| A4 | `MainViews` / `MainActivity` | No `screenOrientation`/`configChanges` in the manifest. Mid-flow rotation recreates the Activity: `onCreate` re-runs `controller.start()` (flow restarts at splash; back stack survives until Home), View-local entry state (dice rolls, coin flips, typed passphrase, letters) is lost, and any running `CameraScanner` is **not stopped** on destroy (camera session leak, double-open on re-entry). Fix: lock portrait + `configChanges` on the activity, and/or stop scanners in `onSurfaceTextureDestroyed`/activity destroy. |
| A5 | `MainViews.PowerOptionsView` "Power off" = `activity.finishAffinity()` | Only finishes the Activity; the process (and every in-memory seed) typically survives in the background. Spec §1.2's "killing the process wipes everything" is the user's mental model for this button; upstream's Power off actually cuts power. Also, `PowerOptionsView` is currently **unreachable** (no Destination references it) — either wire it in (upstream: main menu) or delete it. |

### Minor code issues

| # | Where | Finding |
| - | ----- | ------- |
| B1 | `Controller.instantiateView` (new uncommitted matcher) | `.filter{ c.parameters.isNotEmpty() && c.parameters.all { p.name in args } }` silently accepts a *partial* key set as long as it is a superset of one constructor's params — combined with the no-arg fallback, a call site that forgot one key of a 2-ctor class picks the smaller ctor. Same class of bug as the one just fixed. Consider requiring `args.keys` ⊇ chosen ctor AND no other ctor with MORE covered params exists, plus a unit test enumerating every `Destination(...)` call site's keys against the target class's constructors (catches omissions at compile-test time). |
| B2 | `PsbtViews.fmtAmount` | `String.format("%.8f BTC…")` uses the default locale → comma decimal in e.g. de_DE. Use `Locale.US` (display-correctness, drop-in parity). |
| B3 | `PsbtParser` | `if (psbt == null) return false` compares a non-null `val` (dead check, Python vestige); `parse(): Boolean` always returns true. Cosmetic but misleading in security-critical code. |
| B4 | `ElectrumSeed` | Subclass `init { _generateSeed() }` is dead work: Kotlin dynamic dispatch already runs the *overridden* `_generateSeed` during the base `init`. The comment ("the base init generated the seed before this subclass existed") is wrong for Kotlin. Remove the re-call (saves a PBKDF2-2048) or fix the comment. |
| B5 | `DescriptorViews.MultisigWalletDescriptorView.run` | Button label string template: `"Verify addr ${controller.unverifiedAddress!!["address"]!!}.substring(0, 7)}"` — `.substring(0, 7)}` renders literally. The button reads `Verify addr bc1qxyz….substring(0, 7)}`. Fix: interpolate `(addr).take(7)` inside `${…}`. |
| B6 | `DescriptorViews.configFileToDescriptor` | When a 9th+ fingerprint line arrives with no empty slot, `xpubs.indexOfFirst { it.isEmpty() }` → −1 → `require(xpubs.size > -1)` passes → `xpubs[-1]` AIOOBE (ugly UnhandledExceptionView instead of the "bad or missing xpub" message). |
| B7 | `XpubViews` dead branch | `PSBTChangeDetailsView.onResult`'s final `else -> LoadMultisigWalletDescriptorView` is unreachable (both cases handled above). Dead but harmless; delete to avoid confusion. |
| B8 | `MainViews.PowerOptionsView` | Unreachable (no Destination) — see A5. |
| B9 | `app` test dep `junit:junit:4.13.2` + `core` `kotlin("test")` | Technically third-party artifacts in `dependencies {}`; spec §1.3 says "The build declares zero third-party dependencies" and audit §1.3 says "full dependency tree … only platform artifacts". Defensible (test-only, never in APK) but the spec text and REASONING should state the test-scope carve-out explicitly so an auditor isn't surprised. |
| B10 | `Controller` | `multisigWalletDescriptor: Any?` with a comment "typed when the descriptor model lands" — it landed in v0.3 (`Descriptor`). Type it `Descriptor?` (call sites already cast). |

### Bug found DURING the on-device verification (A6)

| # | Where | Finding / fix |
| - | ----- | ------------- |
| A6 | `SettingsMenuView.visibility`, `SettingsEntryUpdateView.attrName`, `QrDisplayView.parts/title/returnToMain` | Plain **property initializers** read `viewArgs` at construction, but `Controller.runView` assigns `view.viewArgs` AFTER `instantiateView` — so they always saw the empty map. Symptoms on device: tapping "Advanced" re-pushed the GENERAL settings menu forever (no-op loop); `QrDisplayView`/`SettingsEntryUpdateView` would have crashed (NPE on `as List<String>`/`as String`) on first navigation into any QR display or settings entry. Fix: `by lazy` (evaluated on first access inside `run()`) + a guard comment on `View.viewArgs` ("never read in a plain property initializer"). Verified on device: Advanced → Bitcoin network → Testnet now routes and the change persists across a force-stop (`run-as … cat files/settings.json` → `"network":"T"`; after relaunch the checkmark is on Testnet). |

### Doc incoherences

| # | Where | Finding |
| - | ----- | ------- |
| C1 | `docs/developer_tips.md` | (a) points at an online QR generator (`the-qrcode-generator.com`) — same class of link the docs audit removed (rule: docs must not point at online services); (b) the SeedQR snippet opens `english.txt` which does not exist in the repo (use the venv's `embit.bip39.WORDLIST`); (c) `adb logcat | grep -i seedsigner` matches nothing — the app's log tags are `Controller`/class names, not "seedsigner"; (d) the quick-install loop `adb shell am start -n com.seedsigner/.MainActivity` is missing the package-style dot (`com.seedsigner/com.seedsigner.MainActivity`). |
| C2 | `README.md` / `app/build.gradle.kts` / `VersionView` | README says "v0.4 feature scope complete"; `versionName = "0.0.1"` and `VersionView` hardcodes "SeedSigner v0.0.1 (Android port)". Pick one source (BuildConfig) and align. |
| C3 | `docs/dice_verification.md` | "Export Xpub → … → **Sparrow**" — the app's xpub QR format menu is "Animated (default) / Static / Specter legacy"; no "Sparrow" option exists. Also "New Seed (with the dice icon)" — there is no icon (text button). Screenshots are Pi reference (documented), but the step text should match the APK. |
| C4 | `docs/code_structure.md` | "Controller … holds the working flow state (seeds, working PSBT, back stack)" — seeds live in `SeedStorage`, not the Controller (minor). Also the `models` list omits that `SeedStorage` *is* there — it is listed, OK; but "Settings (plain file in app-private storage)" overstates: persistence is currently non-functional (see A2). |
| C5 | `docs/feature_roadmap.md` v0.1 | "Settings (network), plain-file persistence ✅" — see A2: not persisted on the main path. Mark it honestly. |
| C6 | `docs/current-task.md` "Known deltas" | "numeric-SeedQR + compact-bytes + plain-mnemonic scan works" — compact-bytes scan is broken (A3). |
| C7 | `AndroidManifest.xml` comment | Mentions `android:fullBackupContent` which is not present on the element (harmless: `allowBackup="false"` alone suffices; comment misleads auditors). |

### Fix results (all done in this pass)

| Item | Fix |
| ---- | ---- |
| A1 | `SeedDiscardView` gained the upstream pending-seed mode (no `seedIndex` → wipes `pending_mnemonic`; Remove → Home, Cancel → back). |
| A2 | `MainActivity.onCreate` calls `settings.load(this)` before start; `SettingsEntryUpdateView` saves on every change (SettingsQR ingest already saved). |
| A3 | Compact-SeedQR confirm-scan: `SeedQrDecoderKt.decodeCompactSeedQr` (16/32 bytes → `Bip39.mnemonicFromEntropy`, checksum recomputed — 1:1 of upstream `mnemonic_from_bytes`) runs as fallback after the text path. Comment documents the ZXing charset round-trip caveat (corrupt reads fail safe in the compare step). |
| A4 | Manifest: `screenOrientation="portrait"`; every `TextureView`-backed scanner stops the camera in `onSurfaceTextureDestroyed`. |
| A5 | `PowerOptionsView` deleted (unreachable; its "power off" was a no-op for statelessness). `UnhandledExceptionView` "Power off" now `killProcess` after `finishAffinity` — a true wipe per spec §1.2. |
| B1 | `instantiateView` rewritten (see REASONING.md 2026-07 entry: Kotlin emits an implicit no-arg ctor + named ctor + `argN` bridges; old and first kwargs matchers both silently hit the no-arg fallback). New invariant: named ctor is used when every viewArgs key names one of its params; absent keys tolerated only for reference-type params. `ViewInstantiationTest` pins the ≤1-named-ctor invariant for every View class + the kwargs binding. |
| B2–B10 | `Locale.US` in `fmtAmount`; dead `psbt == null` check removed; `ElectrumSeed` redundant init + wrong comment removed; `Verify addr` button template fixed; config-file slot `require(slot >= 0)`; dead `PSBTChangeDetailsView` branch removed; `multisigWalletDescriptor: Descriptor?`; test-scope dep carve-out now explicit in spec.md §1.3. |
| C1–C7 | Docs fixed (offline-only tips, logcat tags, am start target, "Static" menu label, dice button label, persistence wording, version aligned to 0.4.0 in build.gradle + VersionView + README-consistent, manifest comment). |

### On-device E2E results (this pass, `seedtest` AVD, headless)

| Check | Result |
| ----- | ------ |
| App launch + Home | ✅ renders |
| Settings → Advanced routing (after A6 fix) | ✅ |
| A2: network → Testnet, force-stop, `run-as … cat files/settings.json` | ✅ `"network":"T"` |
| A2: relaunch → Advanced → Bitcoin network | ✅ checkmark on Testnet |
| Dice → 50 rolls (all 1s) → `SeedWordsWarningView` (partial-arg kwargs path) | ✅ fingerprint `65fb43fe` |
| Words pages (3×4) → backup-quiz Skip → Finalize | ✅ |
| SeedOptions → Export xpubs → Single Sig → Native Segwit → Static → QR | ✅ QR renders (exercises `QrDisplayView` lazy fix) |
| A1 stored-seed branch: Discard seed | ✅ "Remove seed 65fb43fe?" → Remove → Seeds menu empty, no FATAL in logcat |
| A1 pending-seed branch (calc-12th-word Discard) | code-reviewed; needs an 11-word typed entry to drive on device — left for the webcam session |

### Next (ordered)

1. Commit (code + tests, then docs) — the user pushes.
2. QR scan-in E2E stays blocked until a webcam is attached (`-camera-back webcam0`) or an emulated QR surface is picked.
3. PSBT E2E (embit-built testnet PSBT → scan → review → sign → read back).
4. A1 pending-seed branch on device (Tools → Calc 12th/24th word → 11 words → Discard).

## Known deltas vs upstream (documented, deliberate)

| Item | Note |
| ---- | ---- |
| CompactSeedQR **dot-grid import** | Deferred: custom dot-pattern image processing. Display works; numeric-SeedQR, compact-bytes (entropy round-trip via `mnemonicFromEntropy`) and plain-mnemonic scan works (confirm-scan path) |
| Multisig "sign with multiple keys at once" | One cosigner seed per PSBT pass ported; batched multi-seed UX is the remaining delta |
| PSBT parse runs inline | Upstream: thread + loading screen. Ours: inline (fast) |
| Platform entropy mapping | ANDROID_ID serial + Y-plane frames (REASONING.md) |

## v0.3 → v0.4 pass table (all done)

| Item | State | Commit |
| ---- | ----- | ------ |
| Address scan & single-sig verification + core `Address.singleSigAddress` / `AddressType` (83/83 crosscheck) | done | `6d3f0f5` |
| Address Explorer (seed / scan / descriptor / 12-24 sources; receive/change; per-address QR) | done | `a80807c` |
| Multisig descriptor model (`core/descriptor/Descriptor.kt`, 1:1 embit; `verifyMultisigOutput`; 96/96 byte-identical) | done | `bfa6261` |
| Descriptor app flows (Specter animated accumulation, setup file, PSBT change verification, multisig brute-force) | done | `3b6620d` |
| Electrum seeds (version-100 + PBKDF2 2048, 1:1; `Seed` abstract + `Bip39Seed`/`ElectrumSeed`) | done | `ee4bbcc` |
| Seed backup flow + full SeedQR transcribe chain + `SeedQrEncoder` standard/compact + vendored ZXing byte-mode (`VENDORED.md`) | done | `ee4bbcc` |
| SettingsQR ingest (`SettingsQr.parse` 1:1; `settings::` routing; unit tests) | done | `90b7afb` |
| Docs closeout (README status, roadmap, code structure, REASONING) | done | `48e7301` |
| Docs audit + build_instructions verification suite + CrosscheckPsbtParser bug fix | done | `7fe6eed` |
