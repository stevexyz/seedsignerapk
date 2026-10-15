#!/usr/bin/env python3
"""
Differential crosscheck for MessageSigner.kt (Bitcoin signed messages).

Python side: the upstream seedsigner helpers/embit_utils.py sign_message
(the trusted reference). Kotlin side: :core:crosscheck-message-sign.

TSV columns: name \t seed_b64 \t derivation \t msg_b64 \t network \t signature
"""

import base64
import os
import random
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "seedsigner_orig", "src"))

from embit import bip39
from seedsigner.helpers import embit_utils

REPO_ROOT = os.path.join(os.path.dirname(__file__), "..")
OUT = os.path.join(REPO_ROOT, "tools", "out", "message_sign_cases.tsv")


def main():
    rng = random.Random(6979)
    rows = []

    seeds = [
        bip39.mnemonic_to_seed("model ensure search plunge galaxy firm exclude brain satoshi meadow cable roast"),
        bytes(rng.randrange(256) for _ in range(32)),
    ]
    derivations = [
        "m/84h/0h/0h/0/0",
        "m/84h/0h/0h/1/5",
        "m/44h/0h/0h/0/3",
        "m/49h/0h/0h/1/9",
        "m/86h/1h/0h/0/2",
        "m/0h",
    ]
    messages = [
        b"",
        b"hello world",
        "SeedSigner message signing".encode(),
        bytes(rng.randrange(256) for _ in range(300)),  # > 253 bytes: compact-size 0xfd branch
        bytes(rng.randrange(256) for _ in range(70000)),  # > 65535: 0xfe branch
    ]
    networks = ["main", "test"]

    i = 0
    for si, seed in enumerate(seeds):
        for der in derivations:
            for mi, msg in enumerate(messages):
                if i >= 40:  # keep the battery bounded (the 70000-byte msg is slow in embit pure-python)
                    break
                net = networks[i % len(networks)]
                sig = embit_utils.sign_message(seed, der, msg, compressed=True, embit_network=net)
                rows.append((
                    f"r{si}_{mi}_{i}",
                    base64.b64encode(seed).decode(),
                    der,
                    base64.b64encode(msg).decode(),
                    net,
                    sig,
                ))
                i += 1
            # one message per derivation is enough except the first seed (variety)
            if si == 1 and i >= 30:
                break

    # Known-vector pin: fixed seed/derivation/message (stable across runs)
    pin_seed = bytes(range(32))
    rows.append((
        "pin",
        base64.b64encode(pin_seed).decode(),
        "m/84h/0h/0h/1/5",
        base64.b64encode(b"hello world").decode(),
        "main",
        embit_utils.sign_message(pin_seed, "m/84h/0h/0h/1/5", b"hello world"),
    ))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        f.write("name\tseed_b64\tderivation\tmsg_b64\tnetwork\tsignature\n")
        for r in rows:
            f.write("\t".join(r) + "\n")
    print(f"wrote {len(rows)} cases to {os.path.normpath(OUT)}")

    subprocess.run(
        ["./gradlew", ":core:crosscheck-message-sign", "--no-daemon", "-q",
         "--args", f"{os.path.normpath(OUT)} {os.path.normpath(OUT)}.results"],
        cwd=REPO_ROOT, check=True,
    )

    with open(OUT + ".results") as f:
        lines = f.read().splitlines()
    failures = [l for l in lines if l.split("\t", 1)[1] != "ok"]
    for l in failures:
        print("FAIL:", l)
    ok = len(lines) - len(failures)
    print(f"{'OK' if not failures else 'FAIL'}: {ok}/{len(lines)} cases match upstream")
    if failures:
        sys.exit(1)


if __name__ == "__main__":
    main()
