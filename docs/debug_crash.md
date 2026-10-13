# Debugging a Crash

These instructions help users provide crash logs to developers, and developers reproduce crashes.

### Testnet vs Mainnet

Whenever possible, recreate a crash in testnet. This avoids accidentally revealing private information about your transactions or putting funds at risk.

### Getting logs with adb

With a USB connection and [USB debugging](https://developer.android.com/studio/debug/dev-options) enabled on the phone:

```bash
# Live log stream
adb logcat

# Full logcat dump to a file (attach this to the GitHub issue)
adb logcat -d > seedsigner_crash.log
```

Crash stack traces appear in logcat under the `AndroidRuntime` tag.

### Debugging with a debugger (advanced)

For a debug build, attach the Android Studio debugger to the running app, set breakpoints, and reproduce the crash. Debug builds log additional state that release builds do not; keep that extra logging out of release builds (a seed word printed to logcat on a user's device is a security bug).

### Reproducing with deterministic input

Use [developer_tips.md](developer_tips.md) to generate a known test seed via QR. This makes crashes reproducible across devices and lets developers compare behavior against upstream SeedSigner.
