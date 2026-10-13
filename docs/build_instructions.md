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
