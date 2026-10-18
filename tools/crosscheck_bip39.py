#!/usr/bin/env python3
"""
Differential cross-check: Kotlin `core` crypto vs upstream Python (embit).

Mirrors seedsigner_orig/src/seedsigner/helpers/mnemonic_generation.py exactly
(same entropy source, same embit calls) and compares line-by-line with the
Kotlin implementation via the `:core:crosscheck` Gradle task.

Usage (from repo root):
    python3 tools/crosscheck_bip39.py [num-random-cases]

Exit code 0 = byte-identical on every case.
"""
import hashlib
import glob
import os
import random
import subprocess
import sys
import tempfile

# Force embit's pure-Python secp256k1 (auditable source) — no prebuilt .so (REASONING.md #23).
sys.modules["embit.util.ctypes_secp256k1"] = None

from embit import bip39
from embit.bip39 import WORDLIST  # English, embit's default

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Fixed vectors from docs/dice_verification.md
FIXED = [
    ("dice", "65515223131652132161133154444123616466443112153441"),
    ("dice", "655152231316521321611331544441236164664431121534415633526456254462245546236542364246312613322234612"),
    ("coins", "1" * 128),
    ("coins", "0101010101010101" * 16),
    ("ent", "00" * 16),
    ("ent", "00" * 32),
    ("ent", "ff" * 24),
    ("partial", "0101010"),
    ("partial", "010"),
]


def py_expected(op, arg):
    """Upstream-equivalent computation (embit, per mnemonic_generation.py)."""
    if op == "dice":
        entropy = hashlib.sha256(arg.encode()).digest()
        if len(arg) == 50:
            entropy = entropy[:16]
        return bip39.mnemonic_from_bytes(entropy, wordlist=WORDLIST)
    if op == "coins":
        entropy = hashlib.sha256(arg.encode()).digest()
        if len(arg) == 128:
            entropy = entropy[:16]
        return bip39.mnemonic_from_bytes(entropy, wordlist=WORDLIST)
    if op == "checksum":
        words = arg.split()
        if len(words) in (11, 23):
            words = words + [WORDLIST[0]]
        b = bip39.mnemonic_to_bytes(" ".join(words), ignore_checksum=True, wordlist=WORDLIST)
        return bip39.mnemonic_from_bytes(b, wordlist=WORDLIST)
    if op == "partial":
        return WORDLIST[int(arg + "0" * (11 - len(arg)), 2)]
    if op == "ent":
        return bip39.mnemonic_from_bytes(bytes.fromhex(arg), wordlist=WORDLIST)
    if op == "mn2ent":
        return bip39.mnemonic_to_bytes(arg, wordlist=WORDLIST).hex()
    if op == "seed":
        mnemonic, passphrase = arg.split("\t")
        return bip39.mnemonic_to_seed(mnemonic, passphrase, wordlist=WORDLIST).hex()
    raise ValueError(op)


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
        description="Differential cross-check: Kotlin core BIP39/mnemonic crypto "
                    "vs upstream Python (embit). Emits random + fixed cases, "
                    "runs the Kotlin side via Gradle, compares byte-for-byte.")
    ap.add_argument("n_random", nargs="?", type=int, default=200,
                    help="number of random cases in addition to the fixed vectors (default: 200)")
    args = ap.parse_args()
    n_random = args.n_random
    rng = random.Random(1337)  # deterministic case generation
    cases = list(FIXED)

    for _ in range(n_random):
        choice = rng.randrange(7)
        if choice == 0:  # dice
            n = rng.choice([50, 99])
            cases.append(("dice", "".join(rng.choice("123456") for _ in range(n))))
        elif choice == 1:  # coins
            n = rng.choice([128, 256])
            cases.append(("coins", "".join(rng.choice("01") for _ in range(n))))
        elif choice == 2:  # checksum
            n = rng.choice([11, 23])
            cases.append(("checksum", " ".join(rng.choice(WORDLIST) for _ in range(n))))
        elif choice == 3:  # partial
            n = rng.randrange(1, 12)
            cases.append(("partial", "".join(rng.choice("01") for _ in range(n))))
        elif choice in (4, 5):  # entropy
            nbytes = rng.choice([16, 20, 24, 28, 32])
            cases.append(("ent", rng.randbytes(nbytes).hex()))
        else:  # seed
            m = bip39.mnemonic_from_bytes(rng.randbytes(16), wordlist=WORDLIST)
            cases.append(("seed", m + "\t" + "".join(rng.choice("abz ") for _ in range(6))))

    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.write("\n".join(f"{op} {arg}" for op, arg in cases) + "\n")
        in_path = f.name
    out_path = in_path + ".out"

    subprocess.run(
        ["./gradlew", ":core:crosscheck", "--no-daemon", "-q",
         "--args", f"{in_path} {out_path}"],
        cwd=REPO_ROOT, check=True, env=gradle_env(),
    )

    kotlin_results = open(out_path).read().splitlines()
    os.unlink(in_path)
    os.unlink(out_path)

    if len(kotlin_results) != len(cases):
        print(f"FAIL: {len(kotlin_results)} results for {len(cases)} cases")
        sys.exit(1)

    failures = 0
    for (op, arg), got in zip(cases, kotlin_results):
        want = py_expected(op, arg)
        if got != want:
            failures += 1
            print(f"FAIL [{op}] {arg[:60]}...")
            print(f"  python: {want}")
            print(f"  kotlin: {got}")
            if failures >= 5:
                break

    total = len(cases)
    if failures:
        print(f"{failures}/{total} cases differ")
        sys.exit(1)
    print(f"OK: {total}/{total} cases byte-identical to upstream Python (embit)")


if __name__ == "__main__":
    main()
