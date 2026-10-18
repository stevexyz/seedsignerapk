#!/usr/bin/env python
"""Multisig wallet descriptor crosscheck generator (dev-only).

Exercises the embit 0.8.0 descriptor package (the trusted source) and emits
cases the Kotlin port (core/descriptor/Descriptor.kt) must reproduce:

  addr      derive(index, branch).script_pubkey().address() for wsh /
            sh(wsh) / sh(multi) across networks and receive/change indexes
            (upstream embit_utils.get_multisig_address)
  policy    (threshold, n) via embit_utils.get_multisig_policy
  owns      Descriptor.owns() against real PSBT-like output scopes
  reject    strings that embit Descriptor.from_string must reject

TSV columns: kind \t arg \t expected
"""
import os
import sys
import types

sys.path.insert(0, os.path.expanduser("~/00MASTER/seedsignerapk/seedsigner_orig/src"))
sys.path.insert(0, os.path.expanduser("/tmp/urtypes-7fb280eab3b3563dfc57d2733b0bf5cbc0a96a6a/src"))

_stub = types.ModuleType("pyzbar")
_substub = types.ModuleType("pyzbar.pyzbar")
_substub.ZBarSymbol = type("ZBarSymbol", (), {"QRCODE": 4})
_stub.pyzbar = types.SimpleNamespace(decode=lambda *a, **k: [])
sys.modules["pyzbar"] = _stub
sys.modules["pyzbar.pyzbar"] = _substub

from embit import bip32
from embit.networks import NETWORKS
from embit.descriptor import Descriptor
from embit import script as embit_script

# Deferred-importable: --help must work without the upstream checkout.
try:
    from seedsigner.helpers import embit_utils
except ImportError as e:
    embit_utils = None
    _IMPORT_ERROR = e

OUT = os.path.join(os.path.dirname(__file__), "out", "descriptor_cases.tsv")

# Account xpubs at m/48h/0h/2h from deterministic seeds (not from memory).
# The descriptor origin fingerprint is the PARENT node's fingerprint (BIP-32
# origin convention: the first 4 bytes of the parent's child fingerprint
# field) - the xpub's own my_fingerprint is its CHILD's, a different value.
def make_xpub(seed_bytes):
    root = bip32.HDKey.from_seed(seed_bytes, version=NETWORKS["main"]["xprv"])
    account = root.derive("m/48h/0h/2h")
    # Parent node's fingerprint = the .fingerprint field of any of the
    # parent's children (each node stores its parent's fp).
    parent_fp = bytes(root.derive("m/48h/0h").child(0).fingerprint).hex()
    return account.to_public(), parent_fp

XPUBS = [make_xpub(bytes(range(i + 1, i + 33))) for i in range(3)]

def key_str(node, fp):
    xpub = node.to_base58(version=NETWORKS["main"]["xpub"])
    return f"[{fp}/48h/0h/2h]{xpub}/{{0,1}}/*"

XPUB_STRS = [key_str(node, fp) for node, fp in XPUBS]

DESCRIPTORS = {
    "wsh": "wsh(sortedmulti(2,%s))" % ",".join(XPUB_STRS),
    "shwsh": "sh(wsh(sortedmulti(2,%s)))" % ",".join(XPUB_STRS),
    "shmulti": "sh(multi(2,%s))" % ",".join(XPUB_STRS),
    "wsh21": "wsh(sortedmulti(1,%s))" % ",".join(XPUB_STRS[:2]),
}

REJECTS = [
    "wsh(multi(2))",
    "wsh(multi(0,%s))" % ",".join(XPUB_STRS),
    "wsh(multi(99,%s))" % ",".join(XPUB_STRS),
    "nonsense",
]


def main():
    import argparse
    ap = argparse.ArgumentParser(
        description="Multisig wallet descriptor crosscheck generator (dev-only). "
                    "Exercises the embit descriptor package and emits the cases "
                    "the Kotlin port (core/descriptor/Descriptor.kt) must reproduce.")
    ap.parse_args()
    if embit_utils is None:
        sys.exit(f"dev dependency missing (upstream checkout): {_IMPORT_ERROR}")
    get_multisig_policy = embit_utils.get_multisig_policy
    rows = []
    for name, desc_str in DESCRIPTORS.items():
        desc = Descriptor.from_string(desc_str)
        rows.append(("parse", desc_str, desc.to_string()))
        rows.append(("policy", name, "%s,%s" % get_multisig_policy(desc)))
        for net in ["main", "test", "regtest"]:
            for index in [0, 1, 42]:
                for is_change in [0, 1]:
                    addr = embit_utils.get_multisig_address(
                        descriptor=desc, index=index, is_change=bool(is_change), embit_network=net
                    )
                    rows.append(("addr", f"{name}|{net}|{index}|{is_change}", addr))

    # owns(): build PSBT-like output scopes (script_pubkey + bip32_derivations)
    # the way embit PSBT outputs carry them (see embit/psbt.py DerivationPath).
    from embit.psbt import DerivationPath as EmbitDerivationPath
    desc = Descriptor.from_string(DESCRIPTORS["wsh"])

    class Scope:
        def __init__(self, spk, derivs):
            self.script_pubkey = spk
            self.bip32_derivations = dict(derivs)
        @property
        def taproot_bip32_derivations(self):
            return {}

    for index in [0, 5]:
        for is_change in [0, 1]:
            branch = is_change
            derived = desc.derive(index, branch_index=branch)
            spk = derived.script_pubkey()
            # Honest claim: the middle key's pubkey + the full origin path,
            # exactly as a real PSBT records it (origin fp + full derivation).
            k_top = desc.keys[1]
            k = derived.keys[1]
            der_path = EmbitDerivationPath(k_top.fingerprint, k_top.derivation + [branch, index])
            honest = {k.sec(): der_path}
            rows.append(("owns", f"wsh|{index}|{is_change}|honest",
                         str(desc.owns(Scope(spk, honest))).lower()))
            # Forged claim: honest path but a different pubkey (wrong cosigner).
            other_pub = derived.keys[0].sec()
            forged = {other_pub: EmbitDerivationPath(k.fingerprint, k.derivation)}
            # The pubkey->path map key is only used for iteration in owns();
            # the check runs on every (pub, der) pair. A path that matches a
            # key must reproduce the script — it will, so this also returns
            # True; the real negative case is a non-matching path.
            wrong_path = {k.sec(): EmbitDerivationPath(
                derived.keys[2].fingerprint, derived.keys[2].derivation)}
            rows.append(("owns", f"wsh|{index}|{is_change}|mismatch",
                         str(desc.owns(Scope(spk, wrong_path))).lower()))
            # Wrong script type: same derivations, but a p2sh-wrapped script.
            sh_spk = embit_script.Script(b"\xa9\x14" + __import__("embit.hashes", fromlist=["hash160"]).hash160(spk.data) + b"\x87").data
            rows.append(("owns", f"wsh|{index}|{is_change}|wrongscript",
                         str(desc.owns(Scope(embit_script.Script(sh_spk), honest))).lower()))

    for bad in REJECTS:
        try:
            Descriptor.from_string(bad)
            rows.append(("reject", bad, "accepted"))
        except Exception:
            rows.append(("reject", bad, "rejected"))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        for row in rows:
            f.write("\t".join(row) + "\n")
    print(f"{len(rows)} rows written to {OUT}")


if __name__ == "__main__":
    main()
