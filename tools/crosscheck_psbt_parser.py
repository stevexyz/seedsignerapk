#!/usr/bin/env python3
"""
Crosscheck: upstream PSBTParser (Python/embit) vs Kotlin PsbtParser.

Builds a battery of psbts from the upstream test fixtures, runs each through
the upstream PSBTParser, and dumps the parsed results (or the exception class
raised) to a TSV. The Kotlin test core/src/test/.../psbt/CrosscheckPsbtParser.kt
reads the TSV, re-parses the same psbt bytes with the Kotlin PsbtParser, and
asserts every field matches.

Usage:
    ~/toolchain/venv/bin/python tools/crosscheck_psbt_parser.py
Output:
    tools/out/psbt_parser_cases.tsv
"""
import base64
import os
import random
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "seedsigner_orig", "src"))
sys.path.insert(0, os.path.join(ROOT, "seedsigner_orig", "tests"))

from binascii import a2b_base64

from embit.psbt import PSBT
# Deferred-importable: --help must work without the upstream checkout.
try:
    from psbt_testing_util import (
        PSBTTestData,
        claim_seed_owns_key,
        create_output,
        foreign_public_key,
        p2tr_with_script_tree,
        tapleaf_hash,
    )
    from seedsigner.models.psbt_parser import PSBTParser
    from seedsigner.models.settings_definition import SettingsConstants
except ImportError as e:
    PSBTTestData = None
    _IMPORT_ERROR = e

OUT = os.path.join(ROOT, "tools", "out", "psbt_parser_cases.tsv")

def parse_case(psbt, seed_mnemonic):
    """Run the upstream parser and flatten the result into a dict of strings."""
    from seedsigner.models.seed import Seed
    seed = Seed(seed_mnemonic.split())
    row = {
        "status": "ok",
        "policy_type": "", "m": "", "n": "", "cosigners": "",
        "spend": "", "change": "", "fee": "", "input": "",
        "num_inputs": "", "is_high_fee": "", "op_return": "",
        "destinations": "", "change_data": "",
    }
    try:
        p = PSBTParser(p=psbt, seed=seed, network=SettingsConstants.REGTEST)
    except Exception as e:
        row["status"] = type(e).__name__
        return row

    policy = p.policy or {}
    row["policy_type"] = str(policy.get("type"))
    row["m"] = str(policy.get("m")) if policy.get("m") is not None else ""
    row["n"] = str(policy.get("n")) if policy.get("n") is not None else ""
    row["cosigners"] = ";".join(policy.get("cosigners") or [])
    row["spend"] = str(p.spend_amount)
    row["change"] = str(p.change_amount)
    row["fee"] = str(p.fee_amount)
    row["input"] = str(p.input_amount)
    row["num_inputs"] = str(p.num_inputs)
    row["is_high_fee"] = str(p.is_high_fee).lower()
    row["op_return"] = (p.op_return_data.hex() if p.op_return_data is not None else "")
    row["destinations"] = ";".join(
        f"{a}|{v}" for a, v in zip(p.destination_addresses, p.destination_amounts)
    )
    row["change_data"] = ";".join(
        f"{c['address']}|{c['amount']}|{','.join(hex(x) for x in c['verified_derivation_path'])}"
        for c in p.change_data
    )
    return row


def fresh(base_b64):
    return PSBT.parse(a2b_base64(base_b64))


def main():
    import argparse
    ap = argparse.ArgumentParser(
        description="Crosscheck: upstream PSBTParser (Python/embit) vs Kotlin "
                    "PsbtParser. Builds a battery of psbts from the upstream test "
                    "fixtures, runs each through both sides, and compares the "
                    "parsed output (or exception class) field by field.")
    ap.parse_args()
    if PSBTTestData is None:
        sys.exit(f"dev dependency missing (upstream checkout): {_IMPORT_ERROR}")
    SEEDS = {
        "seed": PSBTTestData.seed.mnemonic_str,
        "two_input_seed": PSBTTestData.two_input_seed.mnemonic_str,
        "recipient_seed": PSBTTestData.recipient_seed.mnemonic_str,
    }
    random.seed(42)
    cases = []  # (name, psbt, seed_name)

    # --- Basic matrix: every base input x {change+receive, self-transfer, full spend}
    basic = [
        ("singlesig_native_segwit", PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_1_INPUT,
         PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_CHANGE, PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_SELF_TRANSFER,
         PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_RECEIVE),
        ("singlesig_nested_segwit", PSBTTestData.SINGLE_SIG_NESTED_SEGWIT_1_INPUT,
         PSBTTestData.SINGLE_SIG_NESTED_SEGWIT_CHANGE, PSBTTestData.SINGLE_SIG_NESTED_SEGWIT_SELF_TRANSFER,
         PSBTTestData.SINGLE_SIG_NESTED_SEGWIT_RECEIVE),
        ("singlesig_taproot", PSBTTestData.SINGLE_SIG_TAPROOT_1_INPUT,
         PSBTTestData.SINGLE_SIG_TAPROOT_CHANGE, PSBTTestData.SINGLE_SIG_TAPROOT_SELF_TRANSFER,
         PSBTTestData.SINGLE_SIG_TAPROOT_RECEIVE),
        ("singlesig_legacy_p2pkh", PSBTTestData.SINGLE_SIG_LEGACY_P2PKH_1_INPUT,
         PSBTTestData.SINGLE_SIG_LEGACY_P2PKH_CHANGE, PSBTTestData.SINGLE_SIG_LEGACY_P2PKH_SELF_TRANSFER,
         PSBTTestData.SINGLE_SIG_LEGACY_P2PKH_RECEIVE),
        ("multisig_native_segwit", PSBTTestData.MULTISIG_NATIVE_SEGWIT_1_INPUT,
         PSBTTestData.MULTISIG_NATIVE_SEGWIT_CHANGE, PSBTTestData.MULTISIG_NATIVE_SEGWIT_SELF_TRANSFER,
         PSBTTestData.MULTISIG_NATIVE_SEGWIT_RECEIVE),
        ("multisig_nested_segwit", PSBTTestData.MULTISIG_NESTED_SEGWIT_1_INPUT,
         PSBTTestData.MULTISIG_NESTED_SEGWIT_CHANGE, PSBTTestData.MULTISIG_NESTED_SEGWIT_SELF_TRANSFER,
         PSBTTestData.MULTISIG_NESTED_SEGWIT_RECEIVE),
        ("multisig_legacy_p2sh", PSBTTestData.MULTISIG_LEGACY_P2SH_1_INPUT,
         PSBTTestData.MULTISIG_LEGACY_P2SH_CHANGE, PSBTTestData.MULTISIG_LEGACY_P2SH_SELF_TRANSFER,
         PSBTTestData.MULTISIG_LEGACY_P2SH_RECEIVE),
    ]
    for name, base, change, self_transfer, receive in basic:
        psbt = fresh(base)
        input_amount = sum(inp.utxo.value for inp in psbt.inputs)
        fee = 5000

        # change + matching receive
        psbt1 = fresh(base)
        psbt1.outputs.append(create_output(receive, 12345678))
        psbt1.outputs.append(create_output(change, input_amount - 12345678 - fee))
        cases.append((f"{name}_change_receive", psbt1, "seed"))

        # self-transfer only
        psbt2 = fresh(base)
        psbt2.outputs.append(create_output(self_transfer, input_amount - fee))
        cases.append((f"{name}_self_transfer", psbt2, "seed"))

        # full spend, no change
        psbt3 = fresh(base)
        psbt3.outputs.append(create_output(receive, input_amount - fee))
        cases.append((f"{name}_full_spend", psbt3, "seed"))

    # --- Complete multi-input psbt (2 inputs, 3 change outputs, 1 destination)
    cases.append(("singlesig_native_segwit_2_inputs",
                  fresh(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_2_INPUTS), "two_input_seed"))

    # --- Complete taproot psbt with change
    cases.append(("singlesig_taproot_with_change",
                  fresh(PSBTTestData.SINGLE_SIG_TAPROOT_WITH_CHANGE), "two_input_seed"))

    # --- OP_RETURN (mirrors test_parse_op_return_content)
    psbt = fresh(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_1_INPUT)
    input_amount = sum(inp.utxo.value for inp in psbt.inputs)
    fee = 5000
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_RECEIVE, 12345678))
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_CHANGE,
                                      input_amount - 12345678 - fee))
    # append an OP_RETURN output
    from embit import script
    from embit.psbt import OutputScope
    op_return_payload = b"seedsigner crosscheck payload"
    op_return_script = script.Script(b"\x6a" + bytes([len(op_return_payload)]) + op_return_payload)
    op_out = OutputScope()
    op_out.value = 0
    op_out.script_pubkey = op_return_script
    psbt.outputs.append(op_out)
    cases.append(("singlesig_op_return", psbt, "seed"))

    # --- Forged claim on an output (mirrors test__parse__rejects_a_forged_claim_on_an_output)
    psbt = fresh(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_1_INPUT)
    input_amount = sum(inp.utxo.value for inp in psbt.inputs)
    fee = 5000
    receive = create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_RECEIVE, 12345678)
    # forge: claim our fingerprint at a path where the key is someone else's
    claim_seed_owns_key(receive, "m/84h/1h/0h/0/5",
                        foreign_public_key("m/84h/1h/0h/0/5"), seed=PSBTTestData.seed)
    psbt.outputs.append(receive)
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_CHANGE,
                                      input_amount - 12345678 - fee))
    cases.append(("forged_output_claim", psbt, "seed"))

    # --- Forged claim on an input
    from embit import bip32
    from embit.psbt import DerivationPath
    psbt = fresh(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_1_INPUT)
    foreign = foreign_public_key("m/84h/1h/0h/0/9")
    psbt.inputs[0].bip32_derivations[foreign] = DerivationPath(
        bip32.HDKey.from_seed(PSBTTestData.seed.seed_bytes).my_fingerprint,
        [0x80000054, 0x80000001, 0x80000000, 0, 9])
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_RECEIVE, 12345678))
    input_amount = sum(inp.utxo.value for inp in psbt.inputs)
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_CHANGE,
                                      input_amount - 12345678 - 5000))
    cases.append(("forged_input_claim", psbt, "seed"))

    # --- Forged taproot claim
    psbt = fresh(PSBTTestData.SINGLE_SIG_TAPROOT_1_INPUT)
    input_amount = sum(inp.utxo.value for inp in psbt.inputs)
    fee = 5000
    receive = create_output(PSBTTestData.SINGLE_SIG_TAPROOT_RECEIVE, 12345678)
    foreign_tap = foreign_public_key("m/86h/1h/0h/0/7")
    claim_seed_owns_key(receive, "m/86h/1h/0h/0/7", foreign_tap,
                        seed=PSBTTestData.seed, is_taproot=True)
    psbt.outputs.append(receive)
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_TAPROOT_CHANGE,
                                      input_amount - 12345678 - fee))
    cases.append(("forged_taproot_claim", psbt, "seed"))

    # --- Forged claim on a multisig scope
    psbt = fresh(PSBTTestData.MULTISIG_NATIVE_SEGWIT_1_INPUT)
    input_amount = sum(inp.utxo.value for inp in psbt.inputs)
    fee = 5000
    receive = create_output(PSBTTestData.MULTISIG_NATIVE_SEGWIT_RECEIVE_ANNOTATED, 12345678)
    # Replace one cosigner's key with a foreign one under our fingerprint
    pub, der = list(receive.bip32_derivations.items())[0]
    receive.bip32_derivations[foreign_public_key("m/48h/1h/0h/2h/0/3")] = der
    psbt.outputs.append(receive)
    psbt.outputs.append(create_output(PSBTTestData.MULTISIG_NATIVE_SEGWIT_CHANGE,
                                      input_amount - 12345678 - fee))
    cases.append(("forged_multisig_claim", psbt, "seed"))

    # --- Honest external output (someone else's key, their fingerprint)
    psbt = fresh(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_1_INPUT)
    input_amount = sum(inp.utxo.value for inp in psbt.inputs)
    fee = 5000
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_RECEIVE, 12345678))
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_CHANGE,
                                      input_amount - 12345678 - fee))
    cases.append(("honest_external_output", psbt, "seed"))

    # --- Wrong seed (seed cannot sign): recipient's wallet parsed with our seed
    psbt = fresh(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_1_INPUT)
    input_amount = sum(inp.utxo.value for inp in psbt.inputs)
    fee = 5000
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_RECEIVE, 12345678))
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_CHANGE,
                                      input_amount - 12345678 - fee))
    cases.append(("seed_cannot_sign", psbt, "recipient_seed"))

    # --- High fee (fee > 25% of non-change output value)
    psbt = fresh(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_1_INPUT)
    input_amount = sum(inp.utxo.value for inp in psbt.inputs)
    big_fee = input_amount // 2
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_RECEIVE, input_amount - big_fee - 1000000))
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_CHANGE, 1000000))
    cases.append(("high_fee", psbt, "seed"))

    # --- Zero fingerprints (mirrors the missing-fingerprint handling test)
    psbt = fresh(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_1_INPUT)
    for scope in psbt.inputs + psbt.outputs:
        for k, v in list(scope.bip32_derivations.items()):
            scope.bip32_derivations[k] = type(v)(b"\x00\x00\x00\x00", v.derivation)
        for k, v in list(scope.taproot_bip32_derivations.items()):
            lh, d = v
            scope.taproot_bip32_derivations[k] = (lh, type(d)(b"\x00\x00\x00\x00", d.derivation))
    input_amount = sum(inp.utxo.value for inp in psbt.inputs)
    fee = 5000
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_RECEIVE, 12345678))
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_CHANGE,
                                      input_amount - 12345678 - fee))
    # zero the new outputs' fingerprints too
    for scope in list(psbt.outputs)[-2:]:
        for k, v in list(scope.bip32_derivations.items()):
            scope.bip32_derivations[k] = type(v)(b"\x00\x00\x00\x00", v.derivation)
    cases.append(("zero_fingerprints", psbt, "seed"))

    # --- Mixed derivation path types (both maps populated in one scope)
    psbt = fresh(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_1_INPUT)
    input_amount = sum(inp.utxo.value for inp in psbt.inputs)
    fee = 5000
    receive = create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_RECEIVE, 12345678)
    our_pub, our_der = list(psbt.inputs[0].bip32_derivations.items())[0]
    receive.bip32_derivations[our_pub] = our_der
    receive.taproot_bip32_derivations[our_pub] = ([], our_der)
    psbt.outputs.append(receive)
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_CHANGE,
                                      input_amount - 12345678 - fee))
    cases.append(("mixed_derivation_path_types", psbt, "seed"))

    # --- Contradiction: output pays our key but claims a different fingerprint
    psbt = fresh(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_1_INPUT)
    input_amount = sum(inp.utxo.value for inp in psbt.inputs)
    fee = 5000
    change = create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_CHANGE, 12345678)
    # the change fixture already carries our derivation; rewrite the fingerprint to a foreign one
    pub, der = list(change.bip32_derivations.items())[0]
    change.bip32_derivations[pub] = type(der)(b"\x12\x34\x56\x78", der.derivation)
    psbt.outputs.append(change)
    psbt.outputs.append(create_output(PSBTTestData.SINGLE_SIG_NATIVE_SEGWIT_RECEIVE,
                                      input_amount - 12345678 - fee))
    cases.append(("output_ownership_contradiction", psbt, "seed"))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    cols = ["name", "psbt_b64", "seed", "status", "policy_type", "m", "n", "cosigners",
            "spend", "change", "fee", "input", "num_inputs", "is_high_fee", "op_return",
            "destinations", "change_data"]
    with open(OUT, "w") as f:
        f.write("\t".join(cols) + "\n")
        for name, psbt, seed_name in cases:
            row = parse_case(psbt, SEEDS[seed_name])
            f.write("\t".join([
                name, base64.b64encode(psbt.serialize()).decode(),
                SEEDS[seed_name],
                row["status"], row["policy_type"], row["m"], row["n"], row["cosigners"],
                row["spend"], row["change"], row["fee"], row["input"],
                row["num_inputs"], row["is_high_fee"], row["op_return"],
                row["destinations"], row["change_data"],
            ]) + "\n")
    print(f"wrote {len(cases)} cases to {OUT}")


if __name__ == "__main__":
    main()
