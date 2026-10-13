# SeedSigner for Android

> ## ⚠️ EXPERIMENTAL RELEASE — USE AT YOUR OWN RISK
>
> This is an **early experimental project**. There are **no guarantees of any kind**:
> not of correctness, not of security, not of compatibility, not of stability.
> The code may lose or corrupt funds, may contain bugs, and has not been audited.
> **Do not use it with real funds.** Test only with testnet/regtest or dust amounts
> you can afford to lose. The authors are not liable for any loss or damage.

An offline, airgapped Bitcoin signing device that runs on a regular Android phone.

Repository: <https://github.com/stevexyz/seedsignerapk>

A port of [SeedSigner](https://github.com/SeedSigner/seedsigner) (MIT, see `LICENSE.md`). The binding requirements — isolation, statelessness, no external dependencies, minimal Google dependency — are specified in [docs/spec.md](docs/spec.md) and summarized here:

- **Fully isolated**: no communication of any kind. Only inputs: touch and camera. Only output: the screen.
- **Stateless**: seeds live in memory only; killing the app wipes them.
- **Self-contained**: no external library dependencies; vendored code is attributed in `VENDORED.md`. Classic Android SDK only — no AndroidX/Compose/CameraX unless absolutely required (reason recorded in `REASONING.md`).
- **Auditable**: FOSS, MIT, small enough to read end to end; crypto verified byte-identical to upstream.

## Features

- **Seeds**: create via BIP39 words, [dice rolls](docs/dice_verification.md), or camera image entropy; BIP39 passphrase; import via SeedQR or word entry; [Electrum SegWit seeds](seedsigner_orig/docs/electrum.md).
- **Signing**: Taproot, native/nested segwit, legacy; single sig + multisig xpub export with custom derivation paths; in-depth PSBT review before signing; change-output verification; mainnet/testnet/regtest.
- **Utilities**: address verification, Address Explorer, message signing, BIP85 child seeds.
- **Works with**: Sparrow, Nunchuk, Keeper, BlueWallet, Specter Desktop, and any QR-capable wallet.

## Hardware

Any Android phone with a camera. For maximum security use a dedicated phone with mobile data, WiFi, and Bluetooth disabled (and no SIM). Nothing else to buy.

## Install

1. Build the APK ([docs/build_instructions.md](docs/build_instructions.md)) or download a release and verify it.
2. `adb install seedsigner-android.apk` (or open the APK on the phone).
3. Grant the camera permission — it is the app's only permission.

## Documentation

| Doc | What |
| --- | ---- |
| [docs/spec.md](docs/spec.md) | **The specification** — binding requirements |
| [docs/code_structure.md](docs/code_structure.md) | Architecture |
| [docs/feature_roadmap.md](docs/feature_roadmap.md) | Milestones |
| [docs/build_instructions.md](docs/build_instructions.md) | Build, install, verify, audit |
| [docs/developer_tips.md](docs/developer_tips.md) | Test seeds, dev loop |
| [docs/debug_crash.md](docs/debug_crash.md) | Crash triage |
| [docs/dice_verification.md](docs/dice_verification.md) | Seed-generation verification + test vectors |
| [docs/current-task.md](docs/current-task.md) | Current work state |
| [REASONING.md](REASONING.md) | Why the decisions were made |
| [SECURITY.md](SECURITY.md) | Vulnerability reporting |
| [seedsigner_orig/docs/](seedsigner_orig/) | Upstream docs that are unchanged: [QR formats](seedsigner_orig/docs/qr_formats.md), [SeedQR + printable templates](seedsigner_orig/docs/seed_qr/README.md), [xpub derivation paths](seedsigner_orig/docs/recovery.md), [Electrum](seedsigner_orig/docs/electrum.md) |

## Status

**[Pre-development]** — documentation complete, no app code yet. Next: `core` module per the roadmap v0.0.
