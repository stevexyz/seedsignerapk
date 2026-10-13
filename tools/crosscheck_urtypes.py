#!/usr/bin/env python3
"""Crosscheck: UR crypto payload CBOR (crypto-account, crypto-psbt).

Python side builds the payloads exactly like upstream
(seedsigner_orig/src/seedsigner/models/encode_qr.py UrXpubQrEncoder /
UrPsbtQrEncoder) using embit + the commit-pinned `urtypes` source
(7fb280ea, from upstream requirements.txt). The Kotlin side builds the
same CBOR with core Cbor/UrTypes. Byte-identity is the bar.

Flow:
  python tools/crosscheck_urtypes.py --build
  ./gradlew :core:crosscheck-urtypes --args="/tmp/urtypes_in.tsv /tmp/urtypes_out.tsv" -q
  python tools/crosscheck_urtypes.py --check

TSV row: case_id <TAB> kind <TAB> params... <TAB> expected_cbor_hex
  kind=xpub: root_fp key chain_code parent_fp derivation network script_tags(404|400,404|...)
  kind=psbt: <psbt_hex>
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "seedsigner_orig", "src"))

from embit import bip32
from embit.networks import NETWORKS

URTYPES_COMMIT = "7fb280eab3b3563dfc57d2733b0bf5cbc0a96a6a"
URTYPES_URL = f"https://github.com/selfcustody/urtypes/archive/{URTYPES_COMMIT}.tar.gz"
URTYPES_DIR = f"/tmp/urtypes-{URTYPES_COMMIT}"


def ensure_urtypes():
    if not os.path.isdir(URTYPES_DIR):
        print(f"Fetching pinned urtypes {URTYPES_COMMIT} (trusted source, upstream requirements.txt)...")
        with tempfile.NamedTemporaryFile(suffix=".tar.gz") as f:
            urllib.request.urlretrieve(URTYPES_URL, f.name)
            with tarfile.open(f.name) as t:
                t.extractall("/tmp")
        os.rename(f"/tmp/urtypes-{URTYPES_COMMIT}", URTYPES_DIR)
    sys.path.insert(0, os.path.join(URTYPES_DIR, "src"))


def map_network_to_embit(network):
    # 1:1 from SettingsConstants.map_network_to_embit (settings_definition.py)
    return {"mainnet": "main", "testnet": "test", "regtest": "regtest"}[network]


def prep_xpub(seed_bytes, derivation, network, version):
    # 1:1 from BaseXpubQrEncoder.prep_xpub (encode_qr.py)
    root = bip32.HDKey.from_seed(seed_bytes, version=NETWORKS[map_network_to_embit(network)]["xprv"])
    fingerprint = root.child(0).fingerprint
    xprv = root.derive(derivation)
    xpub = xprv.to_public()
    return root, fingerprint, xpub


def script_expressions_for(derivation):
    """1:1 tag-selection logic from UrXpubQrEncoder.__post_init__ (encode_qr.py).
    Returns the list of ScriptExpression LISTS (one per Output), empty = all types."""
    from urtypes.crypto import SCRIPT_EXPRESSION_TAG_MAP as M
    arr = derivation.split("/")
    if arr[0] == "m":
        arr = arr[1:]
    if len(arr) == 0:
        return []
    idx = [int(e.rstrip("h'")) for e in arr]
    outputs = []
    if idx[0] == 84:
        outputs.append([M[404]])
    elif idx[0] == 49:
        outputs.append([M[400], M[404]])
    elif idx[0] == 48:
        if len(idx) >= 4:
            if idx[3] == 2:
                outputs.append([M[401]])
            elif idx[3] == 1:
                outputs.append([M[400], M[401]])
    elif idx[0] == 86:
        outputs.append([M[409]])
    elif idx[0] == 44:
        outputs.append([M[403]])
    elif idx[0] == 45:
        outputs.append([M[400]])
    return outputs


def build_xpub_cbor(root, fingerprint, xpub, derivation, network, outputs):
    """1:1 from UrXpubQrEncoder.__post_init__ (encode_qr.py)."""
    from urtypes.crypto import HDKey, Output, Keypath, PathComponent
    from urtypes.crypto import CoinInfo

    def derivation_to_keypath(path):
        arr = path.split("/")
        if arr[0] == "m":
            arr = arr[1:]
        if len(arr) == 0:
            return Keypath([], root.child(0).fingerprint, None)
        if arr[-1] == "":
            arr = arr[:-1]
        for i, e in enumerate(arr):
            if e[-1] == "h" or e[-1] == "'":
                arr[i] = PathComponent(int(e[:-1]), True)
            else:
                arr[i] = PathComponent(int(e), False)
        return Keypath(arr, root.child(0).fingerprint, len(arr))

    origin = derivation_to_keypath(derivation)
    use_info = None if network == "mainnet" else CoinInfo(type=None, network=1)
    ur_hdkey = HDKey({'key': xpub.key.serialize(),
                      'chain_code': xpub.chain_code,
                      'origin': origin,
                      'parent_fingerprint': xpub.fingerprint,
                      'use_info': use_info})
    from urtypes.crypto import Account
    ur_account = Account(root.child(0).fingerprint, [Output(tags, ur_hdkey) for tags in outputs])
    return ur_account.to_cbor()


def build(out_tsv):
    ensure_urtypes()
    from urtypes.crypto import PSBT as UR_PSBT

    # fixed seed (not a real user seed; crosscheck material only)
    seed_bytes = hashlib.sha512(b"seedsignerapk urtypes crosscheck").digest()[:32]

    cases = []
    xpub_cases = [
        ("m/84'/0'/0'", "mainnet"),
        ("m/84'/1'/0'", "testnet"),
        ("m/49'/0'/0'", "mainnet"),
        ("m/48'/0'/0'/2'", "mainnet"),
        ("m/48'/0'/0'/1'", "mainnet"),
        ("m/86'/0'/0'", "mainnet"),
        ("m/44'/0'/0'", "mainnet"),
        ("m/45'/0'", "mainnet"),
        ("m/48'/0'/0'", "testnet"),   # 48 with <4 components -> empty outputs -> all types
    ]
    for i, (derivation, network) in enumerate(xpub_cases):
        version = NETWORKS[map_network_to_embit(network)]["xpub"]
        root, fingerprint, xpub = prep_xpub(seed_bytes, derivation, network, version)
        outputs = script_expressions_for(derivation)
        cbor = build_xpub_cbor(root, fingerprint, xpub, derivation, network, outputs)
        tags_str = "|".join(",".join(str(t.tag) for t in o) for o in outputs)
        cases.append((
            f"xpub{i}", "xpub",
            [fingerprint.hex(), xpub.key.serialize().hex(), xpub.chain_code.hex(),
             xpub.fingerprint.hex(), derivation, network, tags_str],
            cbor,
        ))

    # crypto-psbt: a real embit-built PSBT
    from urtypes.crypto import PSBT as UR_PSBT
    psbt_bytes = make_simple_psbt()
    cases.append(("psbt0", "psbt", [psbt_bytes.hex()], UR_PSBT(psbt_bytes).to_cbor()))

    with open(out_tsv, "w") as f:
        for case_id, kind, params, cbor in cases:
            f.write("\t".join([case_id, kind] + params + [cbor.hex()]) + "\n")
    print(f"Wrote {len(cases)} cases to {out_tsv}")


def make_simple_psbt():
    from embit.psbt import PSBT
    from embit.transaction import Transaction, TransactionInput, TransactionOutput

    # unsigned p2pkh tx: one fake prev, one output (same pattern as crosscheck_psbt.py)
    from embit.script import Script
    spk = Script(b"\x76\xa9\x14" + bytes(20) + b"\x88\xac")
    prev_tx = Transaction(version=1,
                          vin=[TransactionInput(bytes(32), 0)],
                          vout=[TransactionOutput(100000, spk)])
    tx = Transaction(version=2,
                     vin=[TransactionInput(prev_tx.txid(), 0)],
                     vout=[TransactionOutput(90000, spk)])

    p = PSBT(tx)
    p.inputs[0].witness_utxo = prev_tx.vout[0]
    return p.serialize()


def check(in_tsv, out_tsv):
    with open(in_tsv) as f:
        expected = [l.rstrip("\n").split("\t") for l in f]
    with open(out_tsv) as f:
        actual = [l.rstrip("\n").split("\t") for l in f]
    assert len(expected) == len(actual), f"line count {len(expected)} vs {len(actual)}"
    bad = 0
    for exp, act in zip(expected, actual):
        if exp[0] != act[0] or exp[-1] != act[-1]:
            bad += 1
            print(f"MISMATCH {exp[0]}\n  expected: {exp[-1][:80]}...\n  actual:   {act[-1][:80]}...")
    if bad:
        print(f"{bad} MISMATCHES")
        sys.exit(1)
    print(f"ALL {len(expected)} CBOR PAYLOADS BYTE-IDENTICAL")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--build", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("in_tsv", nargs="?", default="/tmp/urtypes_in.tsv")
    ap.add_argument("out_tsv", nargs="?", default="/tmp/urtypes_out.tsv")
    a = ap.parse_args()
    if a.build:
        build(a.in_tsv)
    if a.check:
        check(a.in_tsv, a.out_tsv)
