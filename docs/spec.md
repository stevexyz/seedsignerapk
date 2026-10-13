# Specification

Single source of truth for what the app must and must not do. Other docs link here instead of restating these rules.

## 1. Security model

### 1.1 Total isolation
- Only inputs: **touch** (virtual on-screen keys) and **camera** (QR scanning).
- Only output: **the screen**.
- No network, no IPC, no shared storage, no telephony, no other apps — in either direction.
- Enforced by the manifest: the app declares **only** the camera permission.
- Audit: `aapt dump permissions app.apk` must show only `android.permission.CAMERA`.

### 1.2 Statelessness
- Seeds and private material exist only in process memory.
- No secret is ever written to disk, cache, log, or backup.
- Killing the process wipes everything (the Android equivalent of the Pi's "wiped on power off").

### 1.3 No external dependencies — the repo is the entire build input
- The build declares **zero third-party dependencies**. `git clone` + Gradle must produce the APK from this repo alone (plus the public JDK/Android SDK toolchain). Nothing is downloaded at build time.
- Allowed: the Android SDK/framework, the Kotlin toolchain, and **vendored source** — code copied into this repo where its license allows.
- Every vendored module is recorded in `VENDORED.md` with: origin project, exact version/commit, download URL, SHA-256 of the original release, and license. The copy must stay diff-able against the official release.
- Vendor the **minimal** subset that is needed; delete everything else.
- Audit: the full dependency tree of every module contains only platform artifacts.

### 1.4 Minimal Google dependency
- Use the classic Android framework, which is in the SDK.
- Google first-party artifacts (AndroidX/Jetpack: Compose, CameraX, DataStore, ...) are **forbidden unless absolutely required** for something the SDK cannot do. Each exception must state its reason in `REASONING.md`.
- Baseline: classic Views for UI, `android.hardware.camera2` for the camera, a plain file in app-private storage for settings.

## 2. Functional scope

A port of upstream SeedSigner (`seedsigner_orig/`): BIP39 seeds (word entry, dice rolls, camera image entropy, passphrase, SeedQR import/export), xpub export (single sig + multisig, all standard script types + custom paths), PSBT review and signing, address verification, message signing, BIP85, mainnet/testnet/regtest. Milestones: [feature_roadmap.md](feature_roadmap.md).

**Drop-in replacement**: the app must be functionally identical to upstream — same screens, same flows, same QR payloads — so a wallet user can swap the Pi device for the APK (and back) without changing anything in the wallet.

## 3. Correctness

- Every crypto/protocol routine must produce **byte-identical results to upstream**.
- Verified by: (a) fixed test vectors in the repo — [dice_verification.md](dice_verification.md) contains known inputs with expected fingerprint, zpub, and addresses; (b) differential tests against the upstream Python code in `seedsigner_orig/`.
- The `core` module is pure Kotlin with no Android imports, so all of it is unit-testable on a plain JVM.

## 4. UI

- Touchscreen only. Every control is on-screen; no doc, code path, or test may assume physical keys.
- Upstream's physical-button navigation is replaced by **emulated on-screen buttons** with the same semantics (next/prev/confirm/cancel).
- Stretch goal (roadmap "Beyond"): draw the SeedSigner case itself on screen — case, buttons, inner display — for a faithful emulation feel.
