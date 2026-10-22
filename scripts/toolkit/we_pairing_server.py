#!/usr/bin/env python3
"""
we_pairing_server.py — Standalone WE PC-side pairing server.

This is a Python re-implementation of the WE pairing protocol that the
official Wallpaper Engine Android app speaks. It mirrors the Kotlin
implementation in `app/src/main/java/com/wepwawet/pairing/WePairingServer.kt`,
so you can run it on any OS (including Windows) for protocol testing
without booting the full APK.

PROTOCOL (reverse-engineered from decompiled WE Android source `TcpClient.java`)
==================================================================================
DISCOVERY (UDP 7884, multicast 239.100.0.1)
    The PC sends "WallpaperEngine/Ping:<pid>:<hostname>:<flag>:<guid>"
    every ~5 seconds. The phone's `DiscoverService` listens for these
    packets and lists the PC.

    This script only handles TCP. For discovery, run
    `we_discovery_broadcaster.py` alongside this server.

PAIRING (TCP 7889, all length prefixes are LITTLE-ENDIAN per the decompiled
`TcpClient$Companion.readInt/writeInt`)
    1. Server sends RSA modulus:    <LE 4-byte length> <modulus bytes>
    2. Server sends RSA exponent:    <LE 4-byte length> <exponent bytes>
    3. Phone sends encrypted AES key: <LE 4-byte length> <RSA-encrypted AES key>
       (RSA/ECB/PKCS1Padding)
    4. Phone sends AES IV:           <LE 4-byte length> <16 raw bytes>
    5. Phone sends encrypted EncAuthRequest:
                                     <LE 4-byte length> <AES/CBC/PKCS5Padding ciphertext>
       Plaintext JSON:
         {"pin":"1234","name":"Pixel","deviceUserName":"My Phone",
          "displayWidth":1080,"displayHeight":2400,"version":4,
          "mpkgVersion":"PKGM0020","appVersion":"2.21.0"}
    6. Server decrypts, verifies PIN, sends encrypted EncAuthResponse:
                                     <LE 4-byte length> <AES/CBC/PKCS5Padding ciphertext>
       Plaintext JSON: {"status":"ok"}
    7. After pairing, the phone sends "heartbeat\\n" (10 ASCII bytes, no
       length prefix) every ~3 seconds. The server must detect and skip
       these frames (otherwise readLeInt would misread 'h','e','a','r' as
       a 4-byte length and bail out).

USAGE
=====
    python we_pairing_server.py --pin 1234 --port 7889 [--keypair key.json]
    python we_pairing_server.py --help

If --keypair is omitted, a fresh RSA-2048 keypair is generated each run and
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


# Constants from decompiled TcpClient.java
MAX_SAFE_COMMAND_LENGTH = 4096
MAX_SAFE_DATA_LENGTH = 1048592  # ~1 MiB
AES_IV_SIZE = 16
AES_KEY_SIZE = 32  # 256-bit AES


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


def read_message_or_heartbeat(sockfile):
    """Read a length-prefixed message OR skip a 'heartbeat\\n' frame.

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
    """Load a keypair JSON or generate a fresh RSA-2048 pair."""
    if path and path.exists():
        data = json.loads(path.read_text())
        key = RSA.import_key(data["private_key_pem"])
        print(f"Loaded RSA keypair from {path}  ({key.size_in_bits()} bits)")
        return key
    print("Generating fresh RSA-2048 keypair ...")
    key = RSA.generate(2048)
    if path:
        path.write_text(json.dumps({
            "modulus": str(key.n),
            "exponent": str(key.e),
            "private_key_pem": key.export_key().decode("ascii"),
            "public_key_pem": key.publickey().export_key().decode("ascii"),
        }, indent=2))
        print(f"Saved keypair to {path}")
    return key


def recv_exactly(sockfile, n: int) -> bytes:
    out = b""
    while len(out) < n:
        chunk = sockfile.read(n - len(out))
        if not chunk:
            raise EOFError(f"short read: expected {n}, got {len(out)}")
        out += chunk
    return out


def handle_connection(client: socket.socket, addr, key, pin: str,
                     on_pairing_success=None, on_pairing_failure=None,
                     on_command=None) -> None:
    client.settimeout(30)
    sockfile = client.makefile("rwb", buffering=0)
    try:
        # Step 1: send RSA modulus + exponent (LE-length prefixed)
        mod_bytes = long_to_bytes(key.n)
        exp_bytes = long_to_bytes(key.e)
        print(f"[server] Sending RSA public key: modulus={len(mod_bytes)}B, "
              f"exponent={len(exp_bytes)}B")
        write_le_int(sockfile, len(mod_bytes))
        sockfile.write(mod_bytes); sockfile.flush()
        write_le_int(sockfile, len(exp_bytes))
        sockfile.write(exp_bytes); sockfile.flush()

        # Step 2: receive encrypted AES key (RSA/ECB/PKCS1Padding)
        enc_aes_key_len = read_le_int(sockfile)
        print(f"[server] Received encrypted AES key length: {enc_aes_key_len}")
        if not (0 < enc_aes_key_len <= 4096):
            print(f"[server] ERROR: bad AES key length {enc_aes_key_len}")
            return
        enc_aes_key = recv_exactly(sockfile, enc_aes_key_len)

        # Step 3: receive AES IV (16 raw bytes, LE-length prefixed)
        iv_len = read_le_int(sockfile)
        if iv_len != AES_IV_SIZE:
            print(f"[server] ERROR: bad IV length {iv_len} (expected {AES_IV_SIZE})")
            return
        iv = recv_exactly(sockfile, iv_len)

        # Decrypt AES key with RSA private key (RSA/ECB/PKCS1Padding)
        rsa_cipher = PKCS1_v1_5.new(key)
        aes_key = rsa_cipher.decrypt(enc_aes_key, sentinel=b"")
        if not aes_key or len(aes_key) not in (16, 24, 32):
            print(f"[server] ERROR: RSA decryption produced bad AES key "
                  f"({len(aes_key) if aes_key else 0} bytes)")
            return
        print(f"[server] AES key exchange complete: {len(aes_key) * 8}-bit AES "
              f"with {len(iv)}-byte IV")

        # Step 4: receive encrypted EncAuthRequest
        enc_auth_req_len = read_le_int(sockfile)
        if not (0 < enc_auth_req_len <= MAX_SAFE_DATA_LENGTH):
            print(f"[server] ERROR: bad EncAuthRequest length {enc_auth_req_len}")
            return
        enc_auth_req = recv_exactly(sockfile, enc_auth_req_len)

        # Decrypt with AES/CBC/PKCS5Padding (PKCS7 in Python; identical for 16-byte blocks)
        dec_cipher = AES.new(aes_key, AES.MODE_CBC, iv)
        auth_req_json = dec_cipher.decrypt(enc_auth_req)
        # Strip PKCS7 padding
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

        # Step 5: verify PIN
        received_pin = auth_req.get("pin", "")
        pin_valid = received_pin == pin
        print(f"[server] PIN verification: received={received_pin!r} "
              f"expected={pin!r} valid={pin_valid}")

        # Step 6: send encrypted EncAuthResponse
        status = "ok" if pin_valid else "invalid_pin"
        resp_json = json.dumps({"status": status}).encode("utf-8")
        enc_cipher = AES.new(aes_key, AES.MODE_CBC, iv)
        # Apply PKCS7 padding to multiple of 16
        pad_len = 16 - (len(resp_json) % 16)
        resp_padded = resp_json + bytes([pad_len] * pad_len)
        ciphertext = enc_cipher.encrypt(resp_padded)
        write_le_int(sockfile, len(ciphertext))
        sockfile.write(ciphertext); sockfile.flush()
        print(f"[server] Sent EncAuthResponse: status={status} ciphertext={len(ciphertext)}B")

        if not pin_valid:
            print(f"[server] Pairing FAILED — invalid PIN")
            if on_pairing_failure:
                on_pairing_failure("Invalid PIN")
            return

        device_name = auth_req.get("deviceUserName") or auth_req.get("name") or "Unknown"
        print(f"[server] Pairing SUCCESS with {device_name} "
              f"({auth_req.get('displayWidth')}x{auth_req.get('displayHeight')}, "
              f"mpkgVersion={auth_req.get('mpkgVersion')}, "
              f"appVersion={auth_req.get('appVersion')})")
        if on_pairing_success:
            on_pairing_success(device_name)

        # Step 7: command loop (skip heartbeats)
        client.settimeout(0)  # blocking reads
        # Reset the AES cipher for command loop (use the same key/iv as a fresh stream)
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
            # Strip PKCS7 padding
            p = plaintext[-1]
            if 1 <= p <= 16:
                plaintext = plaintext[:-p]
            cmd = plaintext.decode("utf-8", errors="replace")
            print(f"[server] Command #{messages} ({len(payload)}B): {cmd[:300]}")
            if on_command:
                resp = on_command(cmd)
            else:
                resp = '{"status":"ok"}'
            resp_bytes = resp.encode("utf-8")
            pad_len = 16 - (len(resp_bytes) % 16)
            resp_padded = resp_bytes + bytes([pad_len] * pad_len)
            ct = enc_cmd_cipher.encrypt(resp_padded)
            write_le_int(sockfile, len(ct))
            sockfile.write(ct); sockfile.flush()
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
    print(f"=== WE Pairing Server ===")
    print(f"Listening on {bind_addr}:{port} (TCP)")
    print(f"PIN: {pin}")
    print(f"RSA key size: {key.size_in_bits()} bits")
    print(f"Pairing protocol: PKGM0020, AES-256-CBC + RSA/ECB/PKCS1Padding")
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
        description="Standalone WE PC-side pairing server (for protocol testing).",
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
