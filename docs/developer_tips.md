# Developer Tips

### Quickly generate a new seed to test with

Generate a new 12- or 24-word seed offline, e.g. with the repo's own generator (the same code the app's dice flow uses):

```python
~/toolchain/venv/bin/python -c "import embit.bip39 as b; print(b.mnemonic_from_bytes(__import__('os').urandom(16)))"
```

(Any offline BIP39 generator works. Do not use online generators for seeds you may later treat seriously.)

Convert the seed phrase to the numeric SeedQR encoding: each BIP39 word becomes a four-digit, zero-padded index into the BIP39 English wordlist, concatenated. The venv's embit ships the same wordlist:

```python
~/toolchain/venv/bin/python -c "from embit.wordlists.bip39 import WORDLIST; \nphrase='smoke chimney announce candy glory tongue refuse fatigue cricket once consider beef'; print(''.join('%04d' % WORDLIST.index(w) for w in phrase.split()))"
```

For the example phrase above you should see:

```
1638032000740266079618271443067004111236
```

Encode that digit string as a QR code **offline** (any local QR tool — do
not paste the seed material into a web service) and scan it: start the app
and scan it with the phone camera. (Use the debug build on a testnet —
never mainnet — while testing.)

### Quick install/relaunch loop

```bash
./gradlew installDebug
adb shell am start -n com.seedsigner/com.seedsigner.MainActivity
```

### Viewing app logs

```bash
# The app logs under the `Controller` tag; `AndroidRuntime` carries crashes.
adb logcat | grep -E "Controller|AndroidRuntime"
```

See [debug_crash.md](debug_crash.md) for crash triage.

## Advanced notes

* Keep test seeds obviously fake (generate them fresh, testnet only).
* The app is meant to be stateless: seeds must not survive app restart. Verify this as a test: load a seed, force-stop the app (`adb shell am force-stop com.seedsigner`), relaunch — the seed must be gone.
