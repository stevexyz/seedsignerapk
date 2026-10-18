#!/usr/bin/env python3
"""
Differential crosscheck for the Kotlin UR2 port (com.seedsigner.core.qr.ur2).

Phase 1 (this script, --build):
    Build UR test cases with the reference Python implementation
    (seedsigner_orig's helpers/ur2) and write /tmp/ur2_in.tsv:
        maxfrag <TAB> payload_hex <TAB> part1 <TAB> part2 <TAB> ...

Phase 2 (Kotlin test CrosscheckUr2):
    Reads /tmp/ur2_in.tsv, decodes each case with URDecoder (parts fed in
    REVERSED order to exercise the fountain decoder), verifies the cbor
    matches payload_hex, re-encodes with UREncoder, writes /tmp/ur2_out.tsv.

Phase 3 (this script, --check):
    Compares /tmp/ur2_in.tsv and /tmp/ur2_out.tsv line by line.
    Any difference means the Kotlin port is not byte-identical.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "seedsigner_orig" / "src"))

IN = Path("/tmp/ur2_in.tsv")
OUT = Path("/tmp/ur2_out.tsv")


def build():
    import hashlib
    # Deferred: --help must work without the upstream checkout importable.
    from seedsigner.helpers.ur2.ur import UR
    from seedsigner.helpers.ur2.ur_encoder import UREncoder
    # Deterministic payloads of increasing size: single-part, ~2, ~5, ~11, ~21
    # fragments at max_fragment_len=1500.
    def make_payload(size):
        return bytes(int.from_bytes(hashlib.sha256(i.to_bytes(4, "big")).digest()[:1], "big") & 0xFF for i in range(size))

    rows = []
    # Protocol-level sizes (large fragments, few parts)
    for size in (100, 2000, 6000, 15000, 40000):
        payload = make_payload(size)
        encoder = UREncoder(UR("seedsigner", bytearray(payload)), max_fragment_len=1500)
        n = encoder.fountain_encoder.seq_len()  # minimum parts to transmit the message
        parts = [encoder.next_part() for _ in range(n)]
        rows.append((1500, payload, parts))
    # Upstream display densities (encode_qr.py qr_max_fragment_size: 10/30/120)
    for maxfrag in (10, 30, 120):
        payload = make_payload(2000)
        encoder = UREncoder(UR("seedsigner", bytearray(payload)), max_fragment_len=maxfrag)
        n = encoder.fountain_encoder.seq_len()
        parts = [encoder.next_part() for _ in range(n)]
        rows.append((maxfrag, payload, parts))

    with IN.open("w") as f:
        for maxfrag, payload, parts in rows:
            f.write("\t".join([str(maxfrag), payload.hex(), *parts]) + "\n")
    print(f"Wrote {len(rows)} cases to {IN}")


def check():
    in_lines = IN.read_text().splitlines()
    out_lines = OUT.read_text().splitlines()
    assert len(in_lines) == len(out_lines), f"line count {len(in_lines)} != {len(out_lines)}"
    for i, (a, b) in enumerate(zip(in_lines, out_lines)):
        assert a == b, f"case {i} differs:\n  py : {a[:120]}...\n  kt : {b[:120]}..."
    print(f"ALL {len(in_lines)} CASES BYTE-IDENTICAL (decode + re-encode)")


def main():
    import argparse
    ap = argparse.ArgumentParser(
        description="Differential crosscheck for the Kotlin UR2 port "
                    "(com.seedsigner.core.qr.ur2). --build writes the case file "
                    "from the upstream Python encoder; --check compares it "
                    "against the Kotlin output byte-for-byte.")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--build", action="store_true", help="write the case TSV (needs the upstream checkout)")
    g.add_argument("--check", action="store_true", help="compare the Kotlin side's output against the case TSV")
    args = ap.parse_args()
    if args.build:
        build()
    elif args.check:
        check()
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
