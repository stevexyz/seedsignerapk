#!/usr/bin/env python3
"""
Differential crosscheck for Bip85.kt (BIP-85 app 39: child mnemonics).

Python side: embit.bip85 (the module upstream SeedSigner calls) + the
official BIP-85 spec test vectors (bip-0085.mediawiki, checked in-session
from github.com/bitcoin/bips).
Kotlin side: :core:crosscheck-bip85 re-runs the same derivations.

TSV columns: name \t root_kind \t root \t num_words \t index \t result
  root_kind: "seed" (b64 seed bytes) or "xprv" (base58 string, utf-8 b64)
"""

import base64
import glob
import os
import random
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "seedsigner_orig", "src"))

from embit import bip32, bip39, bip85

REPO_ROOT = os.path.join(os.path.dirname(__file__), "..")
OUT = os.path.join(REPO_ROOT, "tools", "out", "bip85_cases.tsv")

# Official BIP-85 spec vectors (bip-0085.mediawiki "English mnemonics").
SPEC_XPRV = "xprv9s21ZrQH143K2LBWUUQRFXhucrQqBpKdRRxNVq2zBqsx8HVqFk2uYo8kmbaLLHRdqtQpUm98uKfu3vca1LqdGhUtyoFnCNkfmXRyPXLjbKb"
SPEC_EXPECTED = {
    (12, 0): "girl mad pet galaxy egg matter matrix prison refuse sense ordinary nose",
    (18, 0): "near account window bike charge season chef number sketch tomorrow excuse sniff circle vital hockey outdoor supply token",
    (24, 0): "puppy ocean match cereal symbol another shed magic wrap hammer bulb intact gadget divorce twin tonight reason outdoor destroy simple truth cigar social volcano",
}
SPEC_ENTROPY = {
    12: "6250b68daf746d12a24d58b4787a714b",
    18: "938033ed8b12698449d4bbca3c853c66b293ea1b1ce9d9dc",
    24: "ae131e2312cdc61331542efe0d1077bac5ea803adf24b313a4f0e48e9c51f37f",
}


def gradle_env():
    env = os.environ.copy()
    if not env.get("JAVA_HOME"):
        for jdk in sorted(glob.glob(os.path.expanduser("~/toolchain/jdk-17*"))):
            env["JAVA_HOME"] = jdk
            env["PATH"] = jdk + "/bin:" + env.get("PATH", "")
            break
    return env


def main():
    import argparse
    ap = argparse.ArgumentParser(
        description="Differential crosscheck for Bip85.kt (BIP-85 app 39 child "
                    "mnemonics) vs embit.bip85.")
    ap.parse_args()
    rng = random.Random(85)
    rows = []

    # Spec vectors (root = the spec xprv)
    spec_root = bip32.HDKey.from_base58(SPEC_XPRV)
    for (num_words, _idx), expected in SPEC_EXPECTED.items():
        got = bip85.derive_mnemonic(spec_root, num_words, 0).split()
        assert " ".join(got) == expected, f"embit disagrees with the BIP-85 spec: {got}"
        ent = bip85.derive_entropy(spec_root, 39, [0, num_words, 0])[: num_words * 4 // 3].hex()
        assert ent == SPEC_ENTROPY[num_words], f"embit entropy disagrees with the spec: {ent}"
        rows.append((f"spec_{num_words}", "xprv", base64.b64encode(SPEC_XPRV.encode()).decode(),
                     str(num_words), "0", expected))

    # Differential battery (roots from seeds)
    seeds = [
        "model ensure search plunge galaxy firm exclude brain satoshi meadow cable roast",
        "hole luggage safe present express tragic orbit shed switch metal identify path",
        bytes(rng.randrange(256) for _ in range(32)),
    ]
    for si, s in enumerate(seeds):
        if isinstance(s, str):
            seed_bytes = bip39.mnemonic_to_seed(s)
            root_payload = base64.b64encode(seed_bytes).decode()
        else:
            seed_bytes = s
            root_payload = base64.b64encode(s).decode()
        root = bip32.HDKey.from_seed(seed_bytes)
        for num_words in (12, 18, 24):
            for index in (0, 1, 7, 99):
                words = bip85.derive_mnemonic(root, num_words, index).split()
                rows.append((f"r{si}_w{num_words}_i{index}", "seed", root_payload,
                             str(num_words), str(index), " ".join(words)))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        f.write("name\troot_kind\troot\tnum_words\tindex\tresult\n")
        for r in rows:
            f.write("\t".join(r) + "\n")
    print(f"wrote {len(rows)} cases to {os.path.normpath(OUT)}")

    subprocess.run(
        ["./gradlew", ":core:crosscheck-bip85", "--no-daemon", "-q",
         "--args", f"{os.path.normpath(OUT)} {os.path.normpath(OUT)}.results"],
        cwd=REPO_ROOT, check=True, env=gradle_env(),
    )

    with open(OUT + ".results") as f:
        lines = f.read().splitlines()
    failures = [l for l in lines if l.split("\t", 1)[1] != "ok"]
    for l in failures:
        print("FAIL:", l)
    ok = len(lines) - len(failures)
    print(f"{'OK' if not failures else 'FAIL'}: {ok}/{len(lines)} cases match")
    if failures:
        sys.exit(1)


if __name__ == "__main__":
    main()
