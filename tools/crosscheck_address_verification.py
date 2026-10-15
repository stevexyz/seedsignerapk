#!/usr/bin/env python
"""Address verification crosscheck generator (dev-only).

Builds the cases for the single-sig address brute-force primitives:
  * standard derivation paths (via the seed's get_xpub wallet paths)
  * receive/change addresses per index (embit_utils.get_single_sig_address)
  * QR address recognition/classification (decode_qr BitcoinAddressQrDecoder)

The Kotlin side runs the same inputs through Address.singleSigAddress +
AddressType.detect and every field must match byte-for-byte.
"""
import os
import sys
import types

sys.path.insert(0, os.path.expanduser("~/00MASTER/seedsignerapk/seedsigner_orig/src"))
# urtypes at the commit pinned by docs/REASONING (UR types source).
sys.path.insert(0, os.path.expanduser("/tmp/urtypes-7fb280eab3b3563dfc57d2733b0bf5cbc0a96a6a/src"))

# decode_qr.py imports pyzbar (native libzbar) at module level; the class we
# test never calls it. Stub it so the import succeeds without native deps.
_stub = types.ModuleType("pyzbar")
_substub = types.ModuleType("pyzbar.pyzbar")
_substub.ZBarSymbol = type("ZBarSymbol", (), {"QRCODE": 4})
_stub.pyzbar = types.SimpleNamespace(decode=lambda *a, **k: [])
sys.modules["pyzbar"] = _stub
sys.modules["pyzbar.pyzbar"] = _substub

from embit import bip32, bip39
from embit.networks import NETWORKS

from seedsigner.helpers import embit_utils
from seedsigner.models.decode_qr import BitcoinAddressQrDecoder

OUT = os.path.join(os.path.dirname(__file__), "out", "address_verification_cases.tsv")

SEED_PHRASES = [
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
    "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
    # 24-word mnemonic: generated in-script from deterministic entropy
    # bytes(range(32)) via embit bip39 (not transcribed from memory).
    bip39.mnemonic_from_bytes(bytes(range(32))),
]


def main():
    rows = []

    # --- address derivation per script type / network / index -------------
    net_map = {"main": "mainnet", "test": "testnet", "regtest": "regtest"}
    sig_map = {"leg": "leg", "nes": "nes", "nat": "nat", "tr": "tr"}
    settings_map = {"main": "M", "test": "T", "regtest": "R"}
    for i, phrase in enumerate(SEED_PHRASES):
        seed = bip39.mnemonic_to_seed(phrase)
        for net, script in [("main", "leg"), ("test", "nes"), ("regtest", "nat"), ("main", "tr")]:
            derivation = embit_utils.get_standard_derivation_path(
                network=settings_map[net], wallet_type="ss", script_type={"leg": "leg", "nes": "nes", "nat": "nat", "tr": "tr"}[script]
            )
            xpub = bip32.HDKey.from_seed(seed, version=NETWORKS[net]["xprv"]).derive(derivation).to_public()
            for idx in [0, 1, 42]:
                for is_change in [0, 1]:
                    addr = embit_utils.get_single_sig_address(
                        xpub=xpub, script_type=sig_map[script], index=idx, is_change=bool(is_change),
                        embit_network=net,
                    )
                    rows.append(("addr", f"{i}|{net}|{script}|{idx}|{is_change}", addr))

    # --- address recognition / classification ------------------------------
    samples = [
        "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2",            # p2pkh main
        "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",    # p2wpkh main
        "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx",    # p2wpkh test
        "bcrt1qsa39wf39q7qrw37anyf3q6rgsy7w3w05z0warg",  # p2wpkh regtest
        "bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297",  # p2tr main
        "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy",            # p2sh main
        "2N4Q3Kdf7HAVJbK9BjJEJKqVZbYH2k4h5f",             # p2sh test (prefix 2)
        "bitcoin:bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",  # prefixed
        "BC1QAR0SRRR7XFKVY5L643LYDNW9RE59GTZZWF5MDQ",    # uppercase bech32
        "not an address",
        "https://example.com",
    ]
    for s in samples:
        decoder = BitcoinAddressQrDecoder()
        decoder.add(s)
        if decoder.get_address() is not None:
            (script_type, network) = decoder.get_address_type()
            rows.append(("det", s, f"{decoder.get_address()};{script_type};{network}"))
        else:
            rows.append(("det", s, "none"))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        for row in rows:
            assert all(isinstance(c, str) for c in row), f"non-str field in {row}"
            f.write("\t".join(row) + "\n")
    print(f"{len(rows)} rows written to {OUT}")


if __name__ == "__main__":
    main()
