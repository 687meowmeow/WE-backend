#!/usr/bin/env python3
"""
generate_keypair.py — Generate an RSA-2048 keypair (PEM) for use with the
WE pairing protocol. The public key components (modulus + exponent as
BigInteger) are also dumped in hex form, which is what the WE Android
app sends over the wire during pairing.

USAGE
=====
    python generate_keypair.py [--out keypair.json] [--bits 2048]
    python generate_keypair.py --help

OUTPUT
======
A JSON file containing:
    {
      "modulus":  "<decimal>",          # RSA modulus N
      "exponent": "<decimal>",          # RSA public exponent e
      "modulus_hex":     "...",         # N in hex
      "exponent_hex":    "...",         # e in hex
      "private_key_pem": "-----BEGIN RSA PRIVATE KEY-----...",
      "public_key_pem":  "-----BEGIN PUBLIC KEY-----...",
      "bits":    2048
    }

DEPENDENCIES
============
    pip install pycryptodome

EXIT CODES
==========
    0  success
    1  key generation failed
    2  bad arguments
    3  missing pycryptodome
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

try:
    from Crypto.PublicKey import RSA
    from Crypto.Util.number import bytes_to_long, long_to_bytes
except ImportError:
    print("error: pycryptodome is not installed.", file=sys.stderr)
    print("       Install with:  pip install pycryptodome", file=sys.stderr)
    sys.exit(3)


def generate(out: Path, bits: int) -> int:
    print(f"Generating RSA-{bits} keypair ...")
    key = RSA.generate(bits)
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
    }
    out.write_text(json.dumps(payload, indent=2))
    print(f"Saved to {out}")
    print()
    print(f"Modulus (decimal): {str(n)[:80]}...")
    print(f"Modulus (hex):     {format(n, 'x')[:80]}...")
    print(f"Modulus bytes:     {len(long_to_bytes(n))}")
    print(f"Exponent (hex):    {format(e, 'x')}")
    print(f"Exponent bytes:    {len(long_to_bytes(e))}")
    print()
    print("Use this keypair with we_pairing_server.py:")
    print(f"  python we_pairing_server.py --keypair {out}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="generate_keypair",
        description="Generate an RSA-2048 keypair for the WE pairing protocol.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--out", type=Path, default=Path("we_pairing_keypair.json"),
                       help="Output JSON path (default: we_pairing_keypair.json)")
    parser.add_argument("--bits", type=int, default=2048,
                       help="RSA key size (default: 2048)")
    args = parser.parse_args(argv)
    return generate(args.out, args.bits)


if __name__ == "__main__":
    sys.exit(main())
