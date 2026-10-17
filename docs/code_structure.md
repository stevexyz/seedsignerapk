# Code Structure

Two modules, one rule: **`core` never imports Android.**

## `core` — pure Kotlin, JVM-testable

| Package | Contents |
| ------- | -------- |
| `crypto` | secp256k1, BIP39, BIP32, SLIP-0132, hashes, Base58, Bech32/32m, addresses, networks |
| `tx` | transaction + script types (BIP-174 data model, sighash) |
| `psbt` | parse, validate, sign, plus the `PsbtParser` review layer (policy, change detection, forged-claim rejection) |
| `qr` | UR2 encode/decode (fountain), the `urtypes` crypto payload builders, split-QR segment reassembly (Specter `p{N}of{M}` base64 + BBQr `B$…` hex/base32/zlib, `SplitQrDecoders.kt`); the `qr/zxing` decoders wrap the vendored ZXing (separate `com.google.zxing` package under `core/src/main/java/` — see `VENDORED.md`) |
| `descriptor` | basic-multisig wallet descriptors (1:1 of embit's `descriptor` package: `AllowedDerivation`, `Key`, `Multi`/`Sortedmulti`, `Descriptor`, `getMultisigAddress`, `getMultisigPolicy`) |

Everything in `core` is verified byte-identical to upstream per [spec.md §3](spec.md#3-correctness).

## `app` — thin Android shell, classic framework Views

Mirrors upstream's Model-View-Controller 1:1, so the port can be audited side by side with the Python original:

- **One Activity** (`MainActivity`) hosts one screen at a time.
- **Screen (View)**: one class per screen, same role as an upstream `View` — renders, handles touch input, decides the next screen. The flow views live in `app/views/` grouped by upstream file (`SeedViews`, `XpubViews`, `PsbtViews`, `ScanViews`, ...).
- **Controller**: a small singleton router that swaps screens (upstream's `Controller` minus the hardware layer); holds the working flow state (working PSBT, back stack) and references to the models below.
- **`Ui` / `QrDisplay`**: screen-building helpers and QR rendering (bitmaps from the `core` encoder).
- **`models`**: `Settings` (plain file in app-private storage, loaded on start, saved on every change) + `SettingsQr` (SettingsQR parse), `Seed` (abstract; `Bip39Seed` / `ElectrumSeed`, memory-only), `SeedStorage` (the in-memory seed list), `Derivation`.
- View files added in v0.3/v0.4: `AddressViews`, `ExplorerViews`, `DescriptorViews`, `MessageSignViews`, `Bip85Views`, `SeedWordsViews`, `SeedBackupViews` (SeedQR transcribe chain + `SeedQrEncoder`).
- **Camera in**: platform `android.hardware.camera2`; frames go to the `core` decoder.
- **QR out**: an animated view cycling the UR2 parts (2 s per part, upstream cadence).

Why this shape: upstream is already screen-based MVC (see `seedsigner_orig/docs/code_structure.md`), and classic Views keep the Google-artifact count at zero per [spec.md §1.4](spec.md#14-minimal-google-dependency).

## Platform mapping

| Concern | Upstream (Pi) | Android port |
| ------- | ------------- | ------------ |
| Screen loop | `while True` per View | View lifecycle + event handlers |
| Routing | `Controller` singleton | Same singleton `Controller`, event-driven instead of blocking loop |
| Settings | `settings.ini` on SD card | Plain file, app-private storage (load on start, save on change) |
| Seed storage | RAM, wiped on power off | RAM, wiped on process death |
| Input | 4 buttons + joystick (GPIO) | Touchscreen |
| QR in/out | Camera / LCD | Camera2 / animated View (same protocols) |
