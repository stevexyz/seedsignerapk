#!/usr/bin/env python3
"""
Differential crosscheck generator for MnemonicGeneration.kt.

Runs the upstream seedsigner helpers/mnemonic_generation.py over a fixed
battery of inputs and dumps the results to a TSV. The Kotlin side
(:core:crosscheck-mnemonic-gen) re-runs the same inputs through
MnemonicGeneration.kt and compares field-for-field.

TSV columns: name \t input_b64 \t is_bytes \t result
  input_b64: base64 of the input string (or raw entropy bytes when is_bytes=1)
  is_bytes:  0 = string input (dice/coinflips), 1 = raw entropy bytes
  result:    space-joined words (or the single partial word)
"""

import base64
import glob
import os
import random
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "seedsigner_orig", "src"))

# Deferred-importable: --help must work without the upstream checkout.
try:
    from seedsigner.helpers import mnemonic_generation as mg
except ImportError as e:
    mg = None
    _IMPORT_ERROR = e

REPO_ROOT = os.path.join(os.path.dirname(__file__), "..")
OUT = os.path.join(REPO_ROOT, "tools", "out", "mnemonic_gen_cases.tsv")


def dice_string(n, rng):
    return "".join(str(rng.randint(1, 6)) for _ in range(n))


def flips_string(n, rng):
    return "".join(rng.choice("01") for _ in range(n))


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
        description="Differential crosscheck generator for MnemonicGeneration.kt "
                    "(dice / coin flips / raw bytes -> BIP39 mnemonics) vs the "
                    "upstream seedsigner helpers/mnemonic_generation.py.")
    ap.parse_args()
    if mg is None:
        sys.exit(f"dev dependency missing (upstream checkout): {_IMPORT_ERROR}")
    rng = random.Random(20260210)
    rows = []

    def add_result(name, result_words, payload, is_bytes=False):
        rows.append((
            name,
            base64.b64encode(payload).decode() if is_bytes else base64.b64encode(payload.encode()).decode(),
            "1" if is_bytes else "0",
            " ".join(result_words),
        ))

    # Fixed dice vectors (dice_verification.md) + random battery
    # Pinned: the documented 50-roll example must yield the documented words.
    doc_rolls = "65515223131652132161133154444123616466443112153441"
    doc_words = "hole luggage safe present express tragic orbit shed switch metal identify path".split()
    got = mg.generate_mnemonic_from_dice(doc_rolls)
    assert got == doc_words, f"upstream module disagrees with the documented dice vector: {got}"
    add_result("dice50_documented", got, doc_rolls)
    add_result("dice50_fixed", mg.generate_mnemonic_from_dice("1" * 50), "1" * 50)
    add_result("dice50_all6", mg.generate_mnemonic_from_dice("6" * 50), "6" * 50)
    add_result("dice99_mixed", mg.generate_mnemonic_from_dice("123456" * 16 + "12345"), "123456" * 16 + "12345")
    for i in range(6):
        s = dice_string(50, rng)
        add_result(f"dice50_r{i}", mg.generate_mnemonic_from_dice(s), s)
    for i in range(6):
        s = dice_string(99, rng)
        add_result(f"dice99_r{i}", mg.generate_mnemonic_from_dice(s), s)

    # Coin flips: 128 and 256
    for n in (128, 256):
        for i in range(4):
            s = flips_string(n, rng)
            add_result(f"flips{n}_r{i}", mg.generate_mnemonic_from_coin_flips(s), s)
    add_result("flips128_all0", mg.generate_mnemonic_from_coin_flips("0" * 128), "0" * 128)
    add_result("flips256_all1", mg.generate_mnemonic_from_coin_flips("1" * 256), "1" * 256)

    # Entropy bytes: 16..32
    for length in (16, 20, 24, 28, 32):
        b = bytes(rng.randrange(256) for _ in range(length))
        add_result(f"bytes{length}", mg.generate_mnemonic_from_bytes(b), b, is_bytes=True)
    add_result("bytes16_zero", mg.generate_mnemonic_from_bytes(b"\x00" * 16), b"\x00" * 16, is_bytes=True)

    # Partial final word: 7 flips (12-word) and 3 flips (24-word)
    for n in (7, 3):
        for i in range(4):
            s = flips_string(n, rng)
            rows.append(("partial%d_r%d" % (n, i), base64.b64encode(s.encode()).decode(), "0",
                         mg.get_partial_final_word(s)))
    rows.append(("partial7_0101010", base64.b64encode(b"0101010").decode(), "0",
                 mg.get_partial_final_word("0101010")))
    rows.append(("partial3_010", base64.b64encode(b"010").decode(), "0",
                 mg.get_partial_final_word("010")))

    # Checksums: complete 12/24-word mnemonics, and 11/23-word prefixes
    # (last word replaced by 0000 -> wordlist[0]).
    good12 = mg.generate_mnemonic_from_dice(dice_string(50, rng))
    good24 = mg.generate_mnemonic_from_dice(dice_string(99, rng))
    add_result("checksum_full12", mg.calculate_checksum(good12), " ".join(good12))
    add_result("checksum_full24", mg.calculate_checksum(good24), " ".join(good24))
    add_result("checksum_11words", mg.calculate_checksum(good12[:11]), " ".join(good12[:11]))
    add_result("checksum_23words", mg.calculate_checksum(good24[:23]), " ".join(good24[:23]))
    # wrong final word (checksum must correct it)
    wrong_last = good12[:11] + [mg.get_partial_final_word("0000000") if good12[11] == mg.get_partial_final_word("0000000") else "zoo"]
    add_result("checksum_fix12", mg.calculate_checksum(wrong_last), " ".join(wrong_last))
    # comma-separated string form
    add_result("checksum_commas", mg.calculate_checksum(",".join(good24)), ",".join(good24))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        f.write("name\tinput_b64\tis_bytes\tresult\n")
        for r in rows:
            f.write("\t".join(r) + "\n")

    print(f"wrote {len(rows)} cases to {os.path.normpath(OUT)}")

    subprocess.run(
        ["./gradlew", ":core:crosscheck-mnemonic-gen", "--no-daemon", "-q",
         "--args", f"{os.path.normpath(OUT)} {os.path.normpath(OUT)}.results"],
        cwd=REPO_ROOT, check=True, env=gradle_env(),
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
