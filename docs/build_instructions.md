# Build Instructions

## Prerequisites

* JDK 17, Android SDK (platform 34, build-tools 34.0.0), `adb`
* A device or emulator with a camera

### Installing the toolchain without root (into `~/toolchain`)

```bash
export TC=$HOME/toolchain && mkdir -p $TC

# JDK 17 (Temurin)
curl -sL -o $TC/jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
tar xzf $TC/jdk17.tar.gz -C $TC && rm $TC/jdk17.tar.gz

# Android SDK (command line tools + platform 34 + build-tools)
curl -sL -o $TC/cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
mkdir -p $TC/android-sdk/cmdline-tools && (cd $TC/android-sdk/cmdline-tools && unzip -q $TC/cmdtools.zip && mv cmdline-tools latest)
export JAVA_HOME=$TC/jdk-17* PATH=$JAVA_HOME/bin:$PATH
yes | $TC/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$TC/android-sdk --licenses
yes | $TC/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$TC/android-sdk \
    "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

Pitfall: use `$TC/...` in the `--sdk_root=` argument. Bash does not expand `~` after `=`, and the packages would land in a literal directory named `~`.

Point the build at it via `local.properties` (not committed):

```
sdk.dir=$HOME/toolchain/android-sdk
```

## Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Running the verification suite

Unit tests:

```bash
./gradlew :core:test :app:test
```

Differential crosschecks (each Python tool generates cases with the upstream
reference, the matching Gradle task replays them in Kotlin, the tool
compares byte-for-byte). All run from the repo root:

```bash
PY=~/toolchain/venv/bin/python   # dev-only venv with embit 0.8.0 (never a build input)
$PY tools/crosscheck_bip39.py                              # 209/209
$PY tools/crosscheck_psbt.py                               # 8/8
$PY tools/crosscheck_psbt_parser.py && ./gradlew :core:crosscheck-psbt-parser --no-daemon --args="$PWD/tools/out/psbt_parser_cases.tsv $PWD/tools/out/psbt_parser_out.tsv" -q   # 34/34 (per-field FAILs printed, exits non-zero)
$PY tools/crosscheck_mnemonic_gen.py                       # 48/48
$PY tools/crosscheck_bip85.py                              # 39/39
$PY tools/crosscheck_message_sign.py                       # 36/36
$PY tools/crosscheck_descriptor.py && ./gradlew :core:crosscheck-descriptor --no-daemon --args="$PWD/tools/out/descriptor_cases.tsv" -q   # 96/96 (Kotlin side compares + exits non-zero on mismatch)
$PY tools/crosscheck_address_verification.py && ./gradlew :core:crosscheck-address-verification --no-daemon --args="$PWD/tools/out/address_verification_cases.tsv" -q   # 83/83 (same)
$PY tools/crosscheck_ur2.py --build && ./gradlew :core:crosscheck-ur2 --no-daemon --args="/tmp/ur2_in.tsv /tmp/ur2_out.tsv" -q && $PY tools/crosscheck_ur2.py --check   # 5/5
$PY tools/crosscheck_urtypes.py --build && ./gradlew :core:crosscheck-urtypes --no-daemon --args="/tmp/urtypes_in.tsv /tmp/urtypes_out.tsv" -q && $PY tools/crosscheck_urtypes.py --check   # 10/10
$PY tools/crosscheck_qr.py --build && ./gradlew :core:crosscheck-qr --no-daemon --args="/tmp/qr_in.tsv /tmp/qr_out.tsv" -q && $PY tools/crosscheck_qr.py --check   # 277/284, 7 pinned upstream ZXing limit
```

The Python venv is a **development** tool for generating reference data; it
is never a build input (spec §1.3) and never runs inside the app.

## Auditing a build (per spec.md)

1. **Permissions**: `aapt dump permissions app.apk` must show only `android.permission.CAMERA`.
2. **Dependencies**: the dependency tree of every module must contain only platform artifacts — no third-party, no AndroidX unless an exception is recorded in `REASONING.md`.
3. **Vendored code**: every vendored module is listed in `VENDORED.md` with origin, exact version, SHA-256 of the official release, and license — and the in-repo copy diffs clean against that release.

## Verifying a release download

Releases publish the APK, a `seedsigner-android.<version>.sha256.txt` manifest, and its GPG signature:

```bash
gpg --fetch-keys <project-key-url>
gpg --verify seedsigner-android.<version>.sha256.txt.sig   # must be "Good signature"; check the key fingerprint out-of-band
shasum -a 256 --check seedsigner-android.<version>.sha256.txt
```

If any step fails, stop and do not install.
