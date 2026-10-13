# Code Structure

Two modules, one rule: **`core` never imports Android.**

## `core` — pure Kotlin, JVM-testable

| Package | Contents |
| ------- | -------- |
| `crypto` | secp256k1, BIP39, BIP32, SLIP-0132 |
| `psbt` | parse, validate, sign |
| `qr` | encoder (UR2, SeedQR, animated chunking); decoder (vendored — see `VENDORED.md`) |
| `settings` | settings model |

Everything in `core` is verified byte-identical to upstream per [spec.md §3](spec.md#3-correctness).

## `app` — thin Android shell, classic framework Views

Mirrors upstream's Model-View-Controller 1:1, so the port can be audited side by side with the Python original:

- **One Activity** hosts one screen at a time.
- **Screen (View)**: one class per screen, same role as an upstream `View` — renders, handles touch input, decides the next screen.
- **Controller**: a small router that swaps screens (upstream's `Controller` minus the hardware layer).
- **`ui/components`**: reusable widgets — button lists, animated QR display, text.
- **Camera in**: platform `android.hardware.camera2`; frames go to the `core` decoder.
- **QR out**: an animated `View` drawing frames from the `core` encoder.
- **Settings**: plain file in app-private storage.

Why this shape: upstream is already screen-based MVC (see `seedsigner_orig/docs/code_structure.md`), and classic Views keep the Google-artifact count at zero per [spec.md §1.4](spec.md#14-minimal-google-dependency).

## Platform mapping

| Concern | Upstream (Pi) | Android port |
| ------- | ------------- | ------------ |
| Screen loop | `while True` per View | View lifecycle + event handlers |
| Routing | `Controller` singleton | Same `Controller`, no singleton needed |
| Settings | `settings.ini` on SD card | Plain file, app-private storage |
| Seed storage | RAM, wiped on power off | RAM, wiped on process death |
| Input | 4 buttons + joystick (GPIO) | Touchscreen |
| QR in/out | Camera / LCD | Camera2 / animated View (same protocols) |
