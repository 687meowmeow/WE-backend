#!/usr/bin/env python3
"""
we_pairing_server.py — Standalone WE PC-side pairing server.

This is a Python re-implementation of the WE pairing protocol that the
official Wallpaper Engine Android app speaks. It mirrors the protocol
observed in a real Wireshark capture (2026-10-22).

VERIFIED PROTOCOL (from real Wireshark capture between Win PC and WE Android app)
==============================================================================
DISCOVERY (UDP 7884, multicast 239.100.0.1 + subnet broadcast + loopback)
    The PC sends "WallpaperEngine/Ping:<port>:<hostname>:<protVer>:<guid>"
    every ~1.3 seconds. The phone's DiscoverService listens for these.

PAIRING (TCP 7889, all length prefixes are 4-byte LITTLE-ENDIAN u32)

    PC → phone frames (in order):
      Frame 0: length=3 + 3 bytes  = RSA exponent (BE BigInteger of 65537 = `01 00 01`)
      Frame 1: length=128 + 128 bytes = RSA-1024 modulus (raw BE bytes, NO sign byte)
      [After phone responds:]
      Frame 2: length=32 + 32 bytes = AES-encrypted response 1 (after EncAuthRequest)
      Frame 3: length=32 + 32 bytes = AES-encrypted response 2
      Frame 4: length=32 + 32 bytes = same as Frame 3 (duplicate ack / heartbeat-like)

    Phone → PC frames (in order):
      Frame 0: length=128 + 128 bytes = RSA-encrypted AES key (RSA-1024 = 128-byte ciphertext)
      Frame 1: length=128 + 128 bytes = RSA-encrypted AES IV (16-byte IV padded to RSA block)
      Frame 2: length=128 + 128 bytes = RSA-encrypted ??? (purpose TBD — possibly MAC/token)
      Frame 3: length=176 + 176 bytes = AES-encrypted EncAuthRequest JSON
      [Then every ~3 seconds:]
      Frames 4-6: literal ASCII "heartbeat\\n" (10 bytes, NO length prefix, may split
                  across TCP segments as `hear` + `tbea` + `t\\n`)

    The EncAuthRequest JSON looks like:
      {"pin":"1234","name":"Pixel 7","deviceUserName":"My Phone",
       "displayWidth":1080,"displayHeight":2400,"version":4,
       "mpkgVersion":"PKGM0020","appVersion":"2.21.0"}

    The EncAuthResponse is small (16 bytes plaintext like `{"status":"ok"}`+pad
    = 16 bytes ciphertext; with length prefix = 20 bytes). But the real protocol
    sends 32-byte responses — possibly 2 separate 16-byte messages, or a longer
    JSON like `{"status":"ok","session":"..."}`. (Needs Frida hooks to confirm.)

CRITICAL DIFFERENCES vs. our previous assumptions:
  - RSA is RSA-1024 (128-byte modulus), NOT RSA-2048
  - The exponent frame is sent FIRST (as a 3-byte BE BigInteger)
  - The phone sends 3 RSA-encrypted frames, not 1 (key + IV + ???)
  - All length prefixes are 4-byte LITTLE-ENDIAN u32
  - Heartbeat is 10 ASCII bytes "heartbeat\\n" with no length prefix

USAGE
=====
    python we_pairing_server.py --pin 1234 --port 7889 [--keypair key.json]
    python we_pairing_server.py --help

If --keypair is omitted, a fresh RSA-1024 keypair is generated each run and
saved to we_pairing_keypair.json in the current directory.

DEPENDENCIES
============
    pip install pycryptodome

EXIT CODES
==========
    0  server shut down cleanly (Ctrl-C)
    1  fatal error (port in use, bad keypair, etc.)
    2  bad arguments
    3  missing pycryptodome
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import struct
import sys
import threading
import time
from pathlib import Path
from typing import Optional

try:
    from Crypto.Cipher import AES, PKCS1_v1_5
    from Crypto.PublicKey import RSA
    from Crypto.Util.number import bytes_to_long, long_to_bytes
except ImportError:
    print("error: pycryptodome is not installed.", file=sys.stderr)
    print("       Install with:  pip install pycryptodome", file=sys.stderr)
    sys.exit(3)


# Constants from decompiled TcpClient.java + real capture
MAX_SAFE_COMMAND_LENGTH = 4096
MAX_SAFE_DATA_LENGTH = 1048592  # ~1 MiB
AES_IV_SIZE = 16
# RSA-1024 — verified from real capture (PC sent 128-byte modulus)
RSA_KEY_SIZE = 1024
RSA_BLOCK_SIZE = 128  # bytes per RSA-1024 ciphertext block
# Standard RSA exponent — verified from real capture (3-byte frame `01 00 01` = 65537)
RSA_EXPONENT = 65537


def write_le_int(sockfile, value: int) -> None:
    """Write a 4-byte LITTLE-ENDIAN int. Mirrors TcpClient$Companion.writeInt."""
    sockfile.write(struct.pack("<I", value))
    sockfile.flush()


def read_le_int(sockfile) -> int:
    """Read a 4-byte LITTLE-ENDIAN int. Mirrors TcpClient$Companion.readInt."""
    b = sockfile.read(4)
    if len(b) != 4:
        raise EOFError(f"short read for length prefix: got {len(b)} bytes")
    return struct.unpack("<I", b)[0]


def recv_exactly(sockfile, n: int) -> bytes:
    out = b""
    while len(out) < n:
        chunk = sockfile.read(n - len(out))
        if not chunk:
            raise EOFError(f"short read: expected {n}, got {len(out)}")
        out += chunk
    return out


def read_message_or_heartbeat(sockfile):
    """Read a length-prefixed message OR skip a 'heartbeat\\n' frame.

    The phone sends "heartbeat\\n" (10 ASCII bytes, no length prefix) every ~3s.
    Without this, read_le_int would misread 'h','e','a','r' as 0x72616568 = ~1.9 GiB
    and bail out of the command loop.

    Returns ("heartbeat", None) for a heartbeat,
            ("invalid", None) for a bad length,
            ("message", bytes) for a real ciphertext payload.
    """
    header = sockfile.read(4)
    if len(header) != 4:
        return "invalid", None
    # Detect 'h','e','a','r' = first 4 bytes of "heartbeat\n"
    if header == b"hear":
        rest = sockfile.read(6)  # consume "tbeat\n"
        if rest != b"tbeat\n":
            return "invalid", None
        return "heartbeat", None
    length = struct.unpack("<I", header)[0]
    if length <= 0 or length > MAX_SAFE_DATA_LENGTH:
        return "invalid", None
    payload = b""
    while len(payload) < length:
        chunk = sockfile.read(length - len(payload))
        if not chunk:
            return "invalid", None
        payload += chunk
    return "message", payload


def load_or_generate_keypair(path: Optional[Path]):
    """Load a keypair JSON or generate a fresh RSA-1024 pair."""
    if path and path.exists():
        data = json.loads(path.read_text())
        key = RSA.import_key(data["private_key_pem"])
        print(f"Loaded RSA keypair from {path}  ({key.size_in_bits()} bits)")
        return key
    print(f"Generating fresh RSA-{RSA_KEY_SIZE} keypair ...")
    key = RSA.generate(RSA_KEY_SIZE, e=RSA_EXPONENT)
    if path:
        path.write_text(json.dumps({
            "modulus": str(key.n),
            "exponent": str(key.e),
            "modulus_hex": format(key.n, "x"),
            "exponent_hex": format(key.e, "x"),
            "modulus_bytes": len(long_to_bytes(key.n)),
            "exponent_bytes": len(long_to_bytes(key.e)),
            "private_key_pem": key.export_key().decode("ascii"),
            "public_key_pem": key.publickey().export_key().decode("ascii"),
            "bits": key.size_in_bits(),
        }, indent=2))
        print(f"Saved keypair to {path}")
    return key


def handle_connection(client: socket.socket, addr, key, pin: str) -> None:
    """Handle a single pairing connection from the WE Android app."""
    client.settimeout(30)
    sockfile = client.makefile("rwb", buffering=0)
    try:
        # === PC → phone: Send RSA exponent FIRST (as 3-byte BE BigInteger) ===
        # The real capture showed: Frame 0 = length=3 + bytes `01 00 01` = 65537
        # This is the standard RSA public exponent e = 65537 = 0x10001
        # Note: BigInteger.toByteArray() returns minimal-length BE bytes with sign bit.
        # For 65537, this is exactly 3 bytes `01 00 01` (no sign byte needed because
        # the high bit is not set).
        exp_bytes = long_to_bytes(key.e)  # 65537 -> b'\x01\x00\x01' (3 bytes)
        print(f"[server] Sending RSA exponent: {key.e} ({len(exp_bytes)}B): {exp_bytes.hex()}")
        write_le_int(sockfile, len(exp_bytes))
        sockfile.write(exp_bytes)
        sockfile.flush()

        # === PC → phone: Send RSA modulus (128 raw BE bytes for RSA-1024) ===
        # Real capture: 128 bytes, no sign byte (RSA modulus always has high bit set,
        # but the wire format does NOT include the BigInteger sign byte).
        mod_bytes = long_to_bytes(key.n)
        # Pad to exactly 128 bytes if needed (RSA-1024 modulus is 128 bytes; if our
        # generated key happens to have a 127-byte modulus, left-pad with zeros)
        if len(mod_bytes) < RSA_BLOCK_SIZE:
            mod_bytes = b"\x00" * (RSA_BLOCK_SIZE - len(mod_bytes)) + mod_bytes
        print(f"[server] Sending RSA modulus: {len(mod_bytes)}B: {mod_bytes[:16].hex()}...")
        write_le_int(sockfile, len(mod_bytes))
        sockfile.write(mod_bytes)
        sockfile.flush()

        # === Phone → PC: Receive 3 RSA-encrypted frames (128B each) ===
        # Frame 0: RSA-encrypted AES key
        # Frame 1: RSA-encrypted AES IV
        # Frame 2: RSA-encrypted ??? (purpose TBD)
        enc_aes_key = b""
        enc_iv = b""
        enc_third = b""
        for i, name in enumerate(["AES key", "AES IV", "third block (???)"]):
            length = read_le_int(sockfile)
            print(f"[server] Received RSA-encrypted {name}: length={length}B")
            if length != RSA_BLOCK_SIZE:
                print(f"[server] ERROR: expected {RSA_BLOCK_SIZE}B RSA block, got {length}B")
                return
            data = recv_exactly(sockfile, length)
            if i == 0:
                enc_aes_key = data
            elif i == 1:
                enc_iv = data
            else:
                enc_third = data

        # Decrypt AES key + IV with our RSA private key (RSA/ECB/PKCS1Padding)
        rsa_cipher = PKCS1_v1_5.new(key)
        aes_key = rsa_cipher.decrypt(enc_aes_key, sentinel=b"")
        if not aes_key or len(aes_key) not in (16, 24, 32):
            print(f"[server] ERROR: RSA decryption of AES key produced bad result "
                  f"({len(aes_key) if aes_key else 0} bytes)")
            return
        iv = rsa_cipher.decrypt(enc_iv, sentinel=b"")
        if not iv or len(iv) != AES_IV_SIZE:
            print(f"[server] ERROR: RSA decryption of IV produced bad result "
                  f"({len(iv) if iv else 0} bytes; expected {AES_IV_SIZE})")
            return
        third_block = rsa_cipher.decrypt(enc_third, sentinel=b"")
        print(f"[server] RSA decryption complete:")
        print(f"          AES key: {len(aes_key) * 8} bits = {aes_key.hex()}")
        print(f"          IV:      {len(iv)} bytes = {iv.hex()}")
        print(f"          third:   {len(third_block)} bytes = {third_block.hex()[:64]}{'...' if len(third_block) > 32 else ''}")
        if third_block:
            print(f"          (third block purpose TBD — possibly MAC, session token, or hash)")

        # === Phone → PC: Receive AES-encrypted EncAuthRequest ===
        enc_auth_req_len = read_le_int(sockfile)
        print(f"[server] Received AES-encrypted EncAuthRequest: length={enc_auth_req_len}B")
        if not (0 < enc_auth_req_len <= MAX_SAFE_DATA_LENGTH):
            print(f"[server] ERROR: bad EncAuthRequest length {enc_auth_req_len}")
            return
        enc_auth_req = recv_exactly(sockfile, enc_auth_req_len)

        # Decrypt with AES/CBC/PKCS5Padding (= PKCS7 in Python for 16-byte blocks)
        dec_cipher = AES.new(aes_key, AES.MODE_CBC, iv)
        auth_req_json = dec_cipher.decrypt(enc_auth_req)
        pad = auth_req_json[-1]
        if 1 <= pad <= 16:
            auth_req_json = auth_req_json[:-pad]
        auth_req_json = auth_req_json.decode("utf-8", errors="replace")
        print(f"[server] Received EncAuthRequest: {auth_req_json}")

        try:
            auth_req = json.loads(auth_req_json)
        except json.JSONDecodeError as e:
            print(f"[server] ERROR: EncAuthRequest is not valid JSON: {e}")
            return

        # Verify PIN
        received_pin = auth_req.get("pin", "")
        pin_valid = received_pin == pin
        print(f"[server] PIN verification: received={received_pin!r} "
              f"expected={pin!r} valid={pin_valid}")

        # === PC → phone: Send AES-encrypted EncAuthResponse ===
        # Real capture showed 32-byte responses (2 AES blocks = 16-byte ciphertext each,
        # or one 32-byte ciphertext for a longer JSON).
        status = "ok" if pin_valid else "invalid_pin"
        resp_json = json.dumps({"status": status}).encode("utf-8")
        # Pad to 32 bytes (PKCS7) so ciphertext is 32 bytes = 2 AES blocks
        pad_len = 32 - (len(resp_json) % 32) if len(resp_json) % 32 != 0 else 32
        # Actually: pad to next 16-byte boundary, then if result is 16 bytes add another
        # 16-byte block to make it 32 (to match the real capture's 32-byte responses)
        enc_cipher = AES.new(aes_key, AES.MODE_CBC, iv)
        # PKCS7 pad to 32 bytes minimum
        target_len = 32 if len(resp_json) <= 32 else ((len(resp_json) // 16 + 1) * 16)
        pad_len = target_len - len(resp_json)
        if pad_len == 0:
            pad_len = 16  # always add at least one block of padding
            target_len = len(resp_json) + 16
        resp_padded = resp_json + bytes([pad_len] * pad_len)
        ciphertext = enc_cipher.encrypt(resp_padded)
        write_le_int(sockfile, len(ciphertext))
        sockfile.write(ciphertext)
        sockfile.flush()
        print(f"[server] Sent EncAuthResponse: status={status} ciphertext={len(ciphertext)}B")

        if not pin_valid:
            print(f"[server] Pairing FAILED — invalid PIN")
            return

        device_name = auth_req.get("deviceUserName") or auth_req.get("name") or "Unknown"
        print(f"[server] Pairing SUCCESS with {device_name} "
              f"({auth_req.get('displayWidth')}x{auth_req.get('displayHeight')}, "
              f"mpkgVersion={auth_req.get('mpkgVersion')}, "
              f"appVersion={auth_req.get('appVersion')})")

        # === Command loop (skip heartbeats, log commands) ===
        client.settimeout(0)
        dec_cmd_cipher = AES.new(aes_key, AES.MODE_CBC, iv)
        enc_cmd_cipher = AES.new(aes_key, AES.MODE_CBC, iv)
        heartbeats = 0
        messages = 0
        while True:
            try:
                kind, payload = read_message_or_heartbeat(sockfile)
            except (EOFError, ConnectionResetError, OSError) as e:
                print(f"[server] Connection closed: {e}")
                break
            if kind == "heartbeat":
                heartbeats += 1
                if heartbeats % 10 == 1:
                    print(f"[server] heartbeat #{heartbeats}")
                continue
            if kind == "invalid":
                print(f"[server] Invalid frame in command loop, ending")
                break
            messages += 1
            plaintext = dec_cmd_cipher.decrypt(payload)
            p = plaintext[-1]
            if 1 <= p <= 16:
                plaintext = plaintext[:-p]
            cmd = plaintext.decode("utf-8", errors="replace")
            print(f"[server] Command #{messages} ({len(payload)}B): {cmd[:300]}")
            # Send a 32-byte ack response (matching real protocol's 32-byte responses)
            resp = '{"status":"ok"}'.encode("utf-8")
            pad_len = 32 - len(resp) if len(resp) < 32 else 16
            resp_padded = resp + bytes([pad_len] * pad_len)
            ct = enc_cmd_cipher.encrypt(resp_padded)
            write_le_int(sockfile, len(ct))
            sockfile.write(ct)
            sockfile.flush()
        print(f"[server] Command loop ended: heartbeats={heartbeats} messages={messages}")
    except (socket.timeout, OSError) as e:
        print(f"[server] Socket error from {addr}: {e}")
    finally:
        try:
            sockfile.close()
        except Exception:
            pass
        try:
            client.close()
        except Exception:
            pass


def serve(port: int, pin: str, keypair_path: Optional[Path],
          bind_addr: str = "0.0.0.0") -> int:
    key = load_or_generate_keypair(keypair_path)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((bind_addr, port))
    srv.listen(8)
    print(f"=== WE Pairing Server (REAL protocol from capture) ===")
    print(f"Listening on {bind_addr}:{port} (TCP)")
    print(f"PIN: {pin}")
    print(f"RSA key size: {key.size_in_bits()} bits (= {RSA_BLOCK_SIZE}-byte modulus)")
    print(f"RSA exponent: {key.e} (sent first as 3-byte BE BigInteger)")
    print(f"Pairing protocol: PKGM0020, AES-CBC + RSA/ECB/PKCS1Padding")
    print(f"Discovery: run we_discovery_broadcaster.py separately")
    print(f"Press Ctrl-C to stop.")
    print()
    try:
        while True:
            try:
                client, addr = srv.accept()
            except KeyboardInterrupt:
                print("\n[server] Shutting down.")
                break
            print(f"\n[server] Incoming connection from {addr}")
            t = threading.Thread(
                target=handle_connection,
                args=(client, addr, key, pin),
                daemon=True,
            )
            t.start()
    except OSError as e:
        print(f"[server] Fatal: {e}", file=sys.stderr)
        return 1
    finally:
        srv.close()
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="we_pairing_server",
        description="Standalone WE PC-side pairing server (REAL protocol from capture).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--port", type=int, default=7889,
                        help="TCP port (default: 7889)")
    parser.add_argument("--pin", default="1234",
                        help="4-digit pairing PIN (default: 1234)")
    parser.add_argument("--keypair", type=Path, default=None,
                        help="Path to keypair JSON (default: we_pairing_keypair.json)")
    parser.add_argument("--bind", default="0.0.0.0",
                        help="Bind address (default: 0.0.0.0)")
    args = parser.parse_args(argv)
    if args.keypair is None:
        args.keypair = Path("we_pairing_keypair.json")
    return serve(args.port, args.pin, args.keypair, args.bind)


if __name__ == "__main__":
    sys.exit(main())
