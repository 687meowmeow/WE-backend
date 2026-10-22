#!/usr/bin/env python3
"""generate_keypair.py — Generate an RSA-1024 keypair (PEM + decimal + hex).

VERIFIED: The real WE pairing protocol uses RSA-1024 (128-byte modulus).
The exponent is sent FIRST as a 3-byte BE BigInteger `01 00 01` (= 65537).
"""
from __future__ import annotations
import argparse
import json
import sys
from pathlib import Path

try:
    from Crypto.PublicKey import RSA
    from Crypto.Util.number import long_to_bytes
except ImportError:
    print("error: pycryptodome is not installed.", file=sys.stderr)
    print("       Install with:  pip install pycryptodome", file=sys.stderr)
    sys.exit(3)


def generate(out: Path, bits: int = 1024) -> int:
    print(f"Generating RSA-{bits} keypair (matches real WE protocol) ...")
    key = RSA.generate(bits, e=65537)
    n = key.n
    e = key.e
    private_pem = key.export_key().decode("ascii")
    public_pem = key.publickey().export_key().decode("ascii")
    payload = {
        "modulus": str(n),
        "exponent": str(e),
        "modulus_hex": format(n, "x"),
        "exponent_hex": format(e, "x"),
        "modulus_bytes": len(long_to_bytes(n)),
        "exponent_bytes": len(long_to_bytes(e)),
        "private_key_pem": private_pem,
        "public_key_pem": public_pem,
        "bits": bits,
        "wire_format_exponent": long_to_bytes(e).hex(),
        "wire_format_modulus": long_to_bytes(n).hex(),
    }
    out.write_text(json.dumps(payload, indent=2))
    print(f"Saved to {out}")
    print()
    print(f"Modulus bytes:    {len(long_to_bytes(n))} (= {bits // 8} for RSA-{bits})")
    print(f"Exponent (hex):    {format(e, 'x')}")
    print(f"Exponent bytes:    {len(long_to_bytes(e))}")
    print()
    print("Wire format (sent over TCP 7889 to phone):")
    print(f"  Frame 0: length=3 + 3 bytes (exponent) = {long_to_bytes(e).hex()}")
    print(f"  Frame 1: length={bits // 8} + {bits // 8} bytes (modulus)")
    print()
    print("Use this keypair with we_pairing_server.py:")
    print(f"  python we_pairing_server.py --keypair {out}")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="generate_keypair",
        description="Generate an RSA-1024 keypair for the WE pairing protocol.",
    )
    parser.add_argument("--out", type=Path, default=Path("we_pairing_keypair.json"))
    parser.add_argument("--bits", type=int, default=1024,
                        help="RSA key size (default: 1024 — matches real WE protocol)")
    args = parser.parse_args(argv)
    return generate(args.out, args.bits)


if __name__ == "__main__":
    sys.exit(main())
