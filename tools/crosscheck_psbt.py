#!/usr/bin/env python3
"""
Differential cross-check: Kotlin `core` PSBT module vs upstream embit 0.8.0.

Builds PSBTs with embit (the library upstream SeedSigner uses), signs them,
and compares byte-for-byte with the Kotlin port via the `:core:crosscheck-psbt`
Gradle task.

Usage (from repo root):
    python3 tools/crosscheck_psbt.py

Exit code 0 = byte-identical on every case (parse, re-serialize, sign).
"""
import glob
import os
import random
import subprocess
import sys
import tempfile

# Force embit's pure-Python secp256k1 (auditable source) instead of its
# prebuilt .so — no closed binaries in the verification path (REASONING.md #23).
sys.modules["embit.util.ctypes_secp256k1"] = None

from embit import bip32, psbt, script, transaction, ec
from embit.transaction import Transaction, TransactionInput, TransactionOutput, SIGHASH

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

RNG = random.Random(20240601)


def rand_seed():
    return RNG.randbytes(32)


def fake_prev_tx(spk: script.Script, value: int = 50_000_000) -> Transaction:
    """A stand-in previous transaction paying [value] sat to [spk]."""
    in_txid = RNG.randbytes(32)
    prev = Transaction(
        version=2,
        vin=[TransactionInput(in_txid, 0, sequence=0xFFFFFFFF)],
        vout=[TransactionOutput(value, spk)],
        locktime=0,
    )
    return prev


def der(root: bip32.HDKey, path: list) -> psbt.DerivationPath:
    return psbt.DerivationPath(root.my_fingerprint, path)


def case_p2wpkh(name: str):
    root = bip32.HDKey.from_seed(rand_seed())
    key = root.derive("m/84h/0h/0h/0/0")
    pk = key.get_public_key()
    spk = script.p2wpkh(pk)
    prev = fake_prev_tx(spk)
    tx = Transaction(vin=[TransactionInput(prev.txid(), 0)],
                     vout=[TransactionOutput(49_900_000, script.p2wpkh(bip32.HDKey.from_seed(rand_seed()).derive("m/84h/0h/0h/1/0").get_public_key()))])
    p = psbt.PSBT(tx)
    p.inputs[0].witness_utxo = prev.vout[0]
    p.inputs[0].bip32_derivations[pk] = der(root, [0x80000054, 0x80000000, 0x80000000, 0, 0])
    unsigned = p.serialize().hex()
    p.sign_with(root)
    return name, unsigned, [root.to_base58()], p.serialize().hex()


def case_p2pkh(name: str):
    root = bip32.HDKey.from_seed(rand_seed())
    key = root.derive("m/44h/0h/0h/0/0")
    pk = key.get_public_key()
    spk = script.p2pkh(pk)
    prev = fake_prev_tx(spk)
    tx = Transaction(vin=[TransactionInput(prev.txid(), 0)],
                     vout=[TransactionOutput(49_900_000, script.p2pkh(bip32.HDKey.from_seed(rand_seed()).derive("m/44h/0h/0h/1/0").get_public_key()))])
    p = psbt.PSBT(tx)
    p.inputs[0].non_witness_utxo = prev
    p.inputs[0].bip32_derivations[pk] = der(root, [0x8000002C, 0x80000000, 0x80000000, 0, 0])
    unsigned = p.serialize().hex()
    p.sign_with(root)
    return name, unsigned, [root.to_base58()], p.serialize().hex()


def case_p2sh_p2wpkh(name: str):
    root = bip32.HDKey.from_seed(rand_seed())
    key = root.derive("m/49h/0h/0h/0/0")
    pk = key.get_public_key()
    redeem = script.p2wpkh(pk)
    spk = script.p2sh(redeem)
    prev = fake_prev_tx(spk)
    tx = Transaction(vin=[TransactionInput(prev.txid(), 0)],
                     vout=[TransactionOutput(49_900_000, script.p2sh(script.p2wpkh(bip32.HDKey.from_seed(rand_seed()).derive("m/49h/0h/0h/1/0").get_public_key())))])
    p = psbt.PSBT(tx)
    p.inputs[0].witness_utxo = prev.vout[0]
    p.inputs[0].redeem_script = redeem
    p.inputs[0].bip32_derivations[pk] = der(root, [0x80000031, 0x80000000, 0x80000000, 0, 0])
    unsigned = p.serialize().hex()
    p.sign_with(root)
    return name, unsigned, [root.to_base58()], p.serialize().hex()


def case_p2wsh_multisig(name: str):
    roots = [bip32.HDKey.from_seed(rand_seed()) for _ in range(3)]
    keys = [r.derive("m/48h/0h/0h/0/0") for r in roots]
    pubs = [k.get_public_key() for k in keys]
    wscript = script.multisig(2, pubs)
    spk = script.p2wsh(wscript)
    prev = fake_prev_tx(spk, value=80_000_000)
    tx = Transaction(vin=[TransactionInput(prev.txid(), 0)],
                     vout=[TransactionOutput(79_800_000, script.p2wsh(script.multisig(2, pubs[:2])))] )
    p = psbt.PSBT(tx)
    p.inputs[0].witness_utxo = prev.vout[0]
    p.inputs[0].witness_script = wscript
    for pub in pubs:
        p.inputs[0].bip32_derivations[pub] = der(roots[pubs.index(pub)], [0x80000030, 0x80000000, 0x80000000, 0, 0])
    unsigned = p.serialize().hex()
    p.sign_with(roots[0])
    p.sign_with(roots[1])
    return name, unsigned, [r.to_base58() for r in roots[:2]], p.serialize().hex()


def case_p2tr(name: str):
    root = bip32.HDKey.from_seed(rand_seed())
    key = root.derive("m/86h/0h/0h/0/0")
    pk = key.get_public_key()
    spk = script.p2tr(pk)
    prev = fake_prev_tx(spk)
    tx = Transaction(vin=[TransactionInput(prev.txid(), 0)],
                     vout=[TransactionOutput(49_900_000, script.p2tr(bip32.HDKey.from_seed(rand_seed()).derive("m/86h/0h/0h/1/0").get_public_key()))])
    p = psbt.PSBT(tx)
    p.inputs[0].witness_utxo = prev.vout[0]
    p.inputs[0].taproot_internal_key = pk
    p.inputs[0].taproot_bip32_derivations[pk] = ([], der(root, [0x80000056, 0x80000000, 0x80000000, 0, 0]))
    unsigned = p.serialize().hex()
    p.sign_with(root)
    return name, unsigned, [root.to_base58()], p.serialize().hex()


def case_multiio(name: str):
    root = bip32.HDKey.from_seed(rand_seed())
    in_keys = [root.derive(f"m/84h/0h/0h/0/{i}") for i in range(2)]
    prevs = []
    for k in in_keys:
        spk = script.p2wpkh(k.get_public_key())
        prev = fake_prev_tx(spk)
        prevs.append(prev)
        p_in = TransactionInput(prev.txid(), 0)
    tx = Transaction(vin=[TransactionInput(prevs[0].txid(), 0), TransactionInput(prevs[1].txid(), 0)],
                     vout=[TransactionOutput(30_000_000, script.p2wpkh(in_keys[0].get_public_key())),
                           TransactionOutput(19_800_000, script.p2pkh(bip32.HDKey.from_seed(rand_seed()).get_public_key())),
                           TransactionOutput(9_900_000, script.p2sh(script.p2wpkh(in_keys[1].get_public_key())))])
    p = psbt.PSBT(tx)
    for i, (prev, k) in enumerate(zip(prevs, in_keys)):
        p.inputs[i].witness_utxo = prev.vout[0]
        p.inputs[i].bip32_derivations[k.get_public_key()] = der(root, [0x80000054, 0x80000000, 0x80000000, 0, i])
    unsigned = p.serialize().hex()
    p.sign_with(root)
    return name, unsigned, [root.to_base58()], p.serialize().hex()


def case_sighash_explicit(name: str):
    root = bip32.HDKey.from_seed(rand_seed())
    key = root.derive("m/84h/0h/0h/0/1")
    pk = key.get_public_key()
    spk = script.p2wpkh(pk)
    prev = fake_prev_tx(spk)
    tx = Transaction(vin=[TransactionInput(prev.txid(), 0)],
                     vout=[TransactionOutput(49_900_000, script.p2wpkh(bip32.HDKey.from_seed(rand_seed()).derive("m/84h/0h/0h/0/2").get_public_key()))])
    p = psbt.PSBT(tx)
    p.inputs[0].witness_utxo = prev.vout[0]
    p.inputs[0].sighash_type = SIGHASH.ALL
    p.inputs[0].bip32_derivations[pk] = der(root, [0x80000054, 0x80000000, 0x80000000, 0, 1])
    unsigned = p.serialize().hex()
    p.sign_with(root)
    return name, unsigned, [root.to_base58()], p.serialize().hex()


def case_v2(name: str):
    root = bip32.HDKey.from_seed(rand_seed())
    key = root.derive("m/84h/0h/0h/0/0")
    pk = key.get_public_key()
    spk = script.p2wpkh(pk)
    prev = fake_prev_tx(spk)
    p = psbt.PSBT(version=2)
    p.tx_version = 2
    p.locktime = 0
    p.inputs = [psbt.InputScope(vin=TransactionInput(prev.txid(), 0))]
    p.outputs = [psbt.OutputScope(vout=TransactionOutput(49_900_000, script.p2wpkh(bip32.HDKey.from_seed(rand_seed()).derive("m/84h/0h/0h/0/1").get_public_key())))]
    p.inputs[0].witness_utxo = prev.vout[0]
    p.inputs[0].bip32_derivations[pk] = der(root, [0x80000054, 0x80000000, 0x80000000, 0, 0])
    unsigned = p.serialize().hex()
    p.sign_with(root)
    return name, unsigned, [root.to_base58()], p.serialize().hex()


def main():
    import argparse
    ap = argparse.ArgumentParser(
        description="Differential cross-check: Kotlin core PSBT module vs upstream "
                    "embit 0.8.0 (parse + re-serialize + sign, byte-identical).")
    ap.parse_args()
    cases = [
        case_p2wpkh("p2wpkh"),
        case_p2pkh("p2pkh"),
        case_p2sh_p2wpkh("p2sh_p2wpkh"),
        case_p2wsh_multisig("p2wsh_multisig"),
        case_p2tr("p2tr"),
        case_multiio("multiio"),
        case_sighash_explicit("sighash_all"),
        case_v2("psbtv2"),
    ]

    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        for name, unsigned_hex, keys, signed_hex in cases:
            f.write(f"{name}\t{unsigned_hex}\t{'|'.join(keys)}\t{signed_hex}\n")
        in_path = f.name
    out_path = in_path + ".out"

    env = os.environ.copy()
    if not env.get("JAVA_HOME"):
        for jdk in sorted(glob.glob(os.path.expanduser("~/toolchain/jdk-17*"))):
            env["JAVA_HOME"] = jdk
            env["PATH"] = jdk + "/bin:" + env.get("PATH", "")
            break

    subprocess.run(
        ["./gradlew", ":core:crosscheck-psbt", "--no-daemon", "-q",
         "--args", f"{in_path} {out_path}"],
        cwd=REPO_ROOT, check=True, env=env,
    )

    kotlin_results = open(out_path).read().splitlines()
    os.unlink(in_path)
    os.unlink(out_path)

    if len(kotlin_results) != len(cases):
        print(f"FAIL: {len(kotlin_results)} results for {len(cases)} cases")
        sys.exit(1)

    failures = 0
    for (name, unsigned_hex, keys, signed_hex), got in zip(cases, kotlin_results):
        got_reser, got_signed = got.split("\t")
        if got_reser != unsigned_hex:
            failures += 1
            print(f"FAIL [{name}] re-serialize mismatch")
        if got_signed != signed_hex:
            failures += 1
            print(f"FAIL [{name}] signed mismatch")
    if failures:
        print(f"FAIL: {failures} mismatches")
        sys.exit(1)
    print(f"OK: {len(cases)} PSBT cases byte-identical (parse + re-serialize + sign)")


if __name__ == "__main__":
    main()
