# Vendored source

Policy: **no third-party binaries, no Maven/Gradle dependencies**. The only
vendored code is ZXing, as **source files** copied verbatim from the official
repository, pinned to a specific release.

## ZXing 3.5.4 (QR code reader/writer)

- **Upstream**: <https://github.com/zxing/zxing>, tag `zxing-3.5.4`
- **License**: Apache License 2.0 (each file carries its license header)
- **Copied**: Java source only, unmodified. No JAR, no prebuilt artifact.
- **Location**:
  - `core/src/main/java/com/google/zxing/` — 55 files: the QR **decoder**
    (detector, bit-matrix parser, RS decoder, data unmasking, binarizers,
    luminance sources incl. `PlanarYUVLuminanceSource` for camera2 YUV input)
  - `core/src/test/java/com/google/zxing/` — 11 files: the QR **encoder**
    (test-only; the app renders QRs with its own display code, and upstream
    SeedSigner's *encoder* is the Python `qrcode` library)

### Known upstream limitations (verified against pristine 3.5.4)

1. **Default EC level is L**, and the encoder/decoder pair has a checksum
   edge case at L for some contents (M/Q/H unaffected). Tests pin EC M,
   matching the Python `qrcode` library's default (what upstream renders).
2. **Content-dependent finder detection weakness**: for some QR contents a
   data run near a corner mimics the 1:1:3:1:1 finder ratio; the detector
   locks onto it, mis-estimates the version (e.g. samples 33 modules on a
   37-module QR), and decoding fails with `ChecksumException`. OpenCV
   decodes the same images fine. Measured 7/284 on our UR2 crosscheck set
   (pinned in `tools/crosscheck_qr.py`). The app mitigates with
   rotation/offset retries across camera frames.

### Verification

- `:core:crosscheck-qr` — Python `qrcode` renders all UR2 parts at upstream
  density sizes (10/30/120 cbor bytes); the vendored decoder reads them back.
- `ZxingDecodeTest` — JVM round-trips through the vendored encoder/decoder
  incl. noise + rescale.
- Vendored decoder behavior was diffed against pristine 3.5.4 compiled from
  the official tag: identical results on every crosscheck case.
