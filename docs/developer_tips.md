# Developer Tips

### Quickly generate a new seed to test with

Generate a new 12- or 24-word seed via [https://iancoleman.io/bip39/](https://iancoleman.io/bip39/).

Convert the seed phrase to the numeric SeedQR encoding: each BIP39 word becomes a four-digit, zero-padded index into the BIP39 English wordlist, concatenated. You can do it quickly in any language, e.g. Python:

```python
wordlist = open("english.txt").read().split()  # standard BIP39 English wordlist
seed_phrase = "smoke chimney announce candy glory tongue refuse fatigue cricket once consider beef"
print("".join("%04d" % wordlist.index(w) for w in seed_phrase.split()))
```

For the example phrase above you should see:

```
1638032000740266079618271443067004111236
```

Paste the output into a [QR code generator](https://www.the-qrcode-generator.com/).

Start the app, choose "Load seed" from a QR code, and scan the QR with the phone camera. (Use the debug build on a testnet — never mainnet — while testing.)

### Quick install/relaunch loop

```bash
./gradlew installDebug
adb shell am start -n <application-id>/.MainActivity
```

### Viewing app logs

```bash
adb logcat | grep -i seedsigner
```

See [debug_crash.md](debug_crash.md) for crash triage.

## Advanced notes

* Keep test seeds obviously fake (generate them fresh, testnet only).
* The app is meant to be stateless: seeds must not survive app restart. Verify this as a test: load a seed, force-stop the app (`adb shell am force-stop <application-id>`), relaunch — the seed must be gone.
