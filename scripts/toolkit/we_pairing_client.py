#!/usr/bin/env python3
"""
Reconstructed WE Pairing Protocol — the missing connector$lambda$2 method.

jadx skipped this method ("Method not decompiled: ... connector$lambda$2")
because it had 1518 instructions — too complex for jadx's decompiler.

We reconstruct it from:
1. The decompiled TcpClient$Companion methods (readInt, writeInt,
   sendEncryptedGSONObject, readEncryptedData, makeReadDecryptFunction,
   generateAESKey)
2. The decompiled EncAuthRequest/EncAuthResponse/ConnectorMessage classes
3. The decompiled RSAPublicKeyHelper class
4. The REAL Wireshark capture (77 packets, 2 TCP flows on port 7889)
5. The decompiled heartbeat code (connector$lambda$2$lambda$1)

The result: a standalone Python script that acts as the PHONE side of the
WE pairing protocol — connecting to a PC running Wallpaper Engine and
performing the full pairing handshake.

This can be used to test our WepWawet WePairingServer (Kotlin) by having
this script connect to it as if it were a real WE PC.

DEPENDENCIES: pip install pycryptodome
"""
from __future__ import annotations
import json
import socket
import struct
import sys
import threading
import time
from pathlib import Path

try:
    from Crypto.Cipher import AES, PKCS1_OAEP
    from Crypto.PublicKey import RSA
    from Crypto.Util.number import bytes_to_long, long_to_bytes
except ImportError:
    print("Install: pip install pycryptodome", file=sys.stderr)
    sys.exit(3)


# === Constants from decompiled source ===

# TcpClient.java:
MAX_SAFE_COMMAND_LENGTH = 4096
MAX_SAFE_DATA_LENGTH = 1048592  # ~1 MiB

# TransferService.java:
CONNECTION_PROTOCOL_VERSION = 4
MPKG_SUPPORT_VERSION = "PKGM0020"

# Heartbeat: connector$lambda$2$lambda$1
# sends "heartbeat\n" every 30 × 100ms = 3 seconds
HEARTBEAT_INTERVAL_SEC = 3.0
HEARTBEAT_PAYLOAD = b"heartbeat\n"


# === Wire format helpers (mirror TcpClient$Companion.readInt/writeInt) ===

def write_le_int(sock, value: int) -> None:
    """Write 4-byte LITTLE-ENDIAN int. Mirrors TcpClient$Companion.writeInt:
        output.writeByte(value & 255)
        output.writeByte((value >> 8) & 255)
        output.writeByte((value >> 16) & 255)
        output.writeByte((value >> 24) & 255)
    """
    sock.sendall(struct.pack("<I", value))


def read_le_int(sock) -> int:
    """Read 4-byte LITTLE-ENDIAN int. Mirrors TcpClient$Companion.readInt:
        byte[] bArr = new byte[4];
        input.read(bArr, 0, 4);
        return ByteBuffer.wrap(bArr).order(LITTLE_ENDIAN).getInt();
    """
    b = recv_exactly(sock, 4)
    return struct.unpack("<I", b)[0]


def recv_exactly(sock, n: int) -> bytes:
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise EOFError(f"short read: expected {n}, got {len(data)}")
        data += chunk
    return data


# === AES encrypt/decrypt (mirror sendEncryptedGSONObject / readEncryptedData) ===

def aes_encrypt(key: bytes, iv: bytes, plaintext: bytes) -> bytes:
    """AES/CBC/PKCS5Padding encrypt. Mirrors TcpClient$Companion.sendEncryptedGSONObject:
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(ENCRYPT_MODE, key, iv);
        byte[] doFinal = cipher.doFinal(json.getBytes(UTF_8));
        writeInt(outStream, doFinal.length);
        outStream.write(doFinal);
    """
    cipher = AES.new(key, AES.MODE_CBC, iv)
    # PKCS7 padding (same as PKCS5 for 16-byte blocks)
    pad_len = 16 - (len(plaintext) % 16)
    padded = plaintext + bytes([pad_len] * pad_len)
    return cipher.encrypt(padded)


def aes_decrypt(key: bytes, iv: bytes, ciphertext: bytes) -> bytes:
    """AES/CBC/PKCS5Padding decrypt. Mirrors TcpClient$Companion.readEncryptedData:
        int readInt = readInt(inStream);
        byte[] bArr = new byte[readInt];
        inStream.readFully(bArr, 0, readInt);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(DECRYPT_MODE, key, iv);
        return cipher.doFinal(bArr);
    """
    cipher = AES.new(key, AES.MODE_CBC, iv)
    plaintext = cipher.decrypt(ciphertext)
    # Strip PKCS7 padding
    pad = plaintext[-1]
    if 1 <= pad <= 16:
        plaintext = plaintext[:-pad]
    return plaintext


def send_encrypted_json(sock, key: bytes, iv: bytes, obj: dict) -> None:
    """Send a length-prefixed AES-encrypted JSON object.
    Mirrors sendEncryptedGSONObject."""
    json_bytes = json.dumps(obj).encode("utf-8")
    ciphertext = aes_encrypt(key, iv, json_bytes)
    write_le_int(sock, len(ciphertext))
    sock.sendall(ciphertext)


def read_encrypted_json(sock, key: bytes, iv: bytes) -> dict:
    """Read a length-prefixed AES-encrypted JSON object.
    Mirrors makeReadDecryptFunction$lambda$0."""
    length = read_le_int(sock)
    if length <= 0 or length > 4096:  # Note: 4096, not MAX_SAFE_DATA_LENGTH
        raise ValueError(f"invalid encrypted message length: {length}")
    ciphertext = recv_exactly(sock, length)
    plaintext = aes_decrypt(key, iv, ciphertext)
    return json.loads(plaintext.decode("utf-8"))


# === RSA encrypt (phone side — encrypts with PC's public key) ===

def rsa_encrypt(pubkey: RSA.RsaKey, data: bytes) -> bytes:
    """RSA/ECB/OAEPPadding encrypt. The phone uses this to encrypt
    the AES key, IV, and third block with the PC's RSA public key."""
    cipher = PKCS1_OAEP.new(pubkey)
    return cipher.encrypt(data)


# === Reconstructed connector$lambda$2 ===

def perform_pairing(pc_host: str, pc_port: int, pin: str,
                    device_name: str = "WepWawet",
                    device_uuid: str = "wepwawet-test-uuid",
                    display_width: int = 1080,
                    display_height: int = 2400,
                    app_version: str = "2.21.0") -> bool:
    """
    Reconstructed connector$lambda$2 — the phone-side pairing handshake.

    This is the method jadx couldn't decompile (1518 instructions).
    Reconstructed from:
    - The decompiled Companion methods (readInt, writeInt, sendEncryptedGSONObject, etc.)
    - The Wireshark capture (actual byte-level protocol)
    - The decompiled EncAuthRequest/EncAuthResponse classes
    - The decompiled heartbeat code

    Protocol flow (VERIFIED from Wireshark capture):

    PC → phone:
      Frame 0: LE length=3 + 3 bytes = RSA exponent (BE BigInteger of 65537 = 01 00 01)
      Frame 1: LE length=128 + 128 bytes = RSA-1024 modulus (raw BE bytes)

    Phone → PC:
      Frame 0: LE length=128 + 128B = RSA-encrypted AES key
      Frame 1: LE length=128 + 128B = RSA-encrypted AES IV
      Frame 2: LE length=128 + 128B = RSA-encrypted ??? (possibly device UUID or PIN)
      Frame 3: LE length=N + N bytes = AES-encrypted EncAuthRequest JSON

    PC → phone:
      Frame 0: LE length=32 + 32B = AES-encrypted response 1 (EncAuthResponse)
      Frame 1: LE length=32 + 32B = AES-encrypted response 2 (???)
      Frame 2: LE length=32 + 32B = AES-encrypted response 3 (= response 2, periodic ack)

    After pairing:
      Phone sends "heartbeat\\n" (10 ASCII bytes, NO length prefix) every ~3 seconds.
    """
    print(f"=== WE Pairing Client (reconstructed connector$lambda$2) ===")
    print(f"Connecting to {pc_host}:{pc_port} ...")

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(30)
    sock.connect((pc_host, pc_port))
    print(f"Connected to {pc_host}:{pc_port}")

    try:
        # === Step 1: Read RSA exponent from PC ===
        # Verified from Wireshark: PC sends length=3 + bytes 01 00 01
        exp_len = read_le_int(sock)
        print(f"Reading RSA exponent: length={exp_len}")
        exp_bytes = recv_exactly(sock, exp_len)
        exponent = bytes_to_long(exp_bytes)  # BE BigInteger
        print(f"  exponent = {exponent} (0x{exponent:x})")

        # === Step 2: Read RSA modulus from PC ===
        # Verified from Wireshark: PC sends length=128 + 128 bytes
        mod_len = read_le_int(sock)
        print(f"Reading RSA modulus: length={mod_len}")
        mod_bytes = recv_exactly(sock, mod_len)
        modulus = bytes_to_long(mod_bytes)  # BE BigInteger
        print(f"  modulus = {modulus.bit_length()} bits")

        # === Step 3: Construct RSA public key ===
        # Mirrors RSAPublicKeyHelper.setModulus + setExponent
        rsa_pubkey = RSA.construct((modulus, exponent))
        print(f"RSA public key constructed: {rsa_pubkey.size_in_bits()} bits")

        # === Step 4: Generate AES key + IV ===
        # Mirrors Companion.generateAESKey(): KeyGenerator.getInstance("AES").init(256)
        aes_key = RSA.generate(32 * 8).n.to_bytes(32, "big")[:32]  # 256-bit AES key
        import os
        aes_key = os.urandom(32)  # 256-bit random AES key
        iv = os.urandom(16)       # 128-bit IV
        print(f"AES key: {len(aes_key)*8} bits = {aes_key.hex()}")
        print(f"IV: {len(iv)} bytes = {iv.hex()}")

        # === Step 5: RSA-encrypt AES key and send ===
        # Verified from Wireshark: phone sends length=128 + 128B
        enc_aes_key = rsa_encrypt(rsa_pubkey, aes_key)
        print(f"Sending RSA-encrypted AES key: {len(enc_aes_key)}B")
        write_le_int(sock, len(enc_aes_key))
        sock.sendall(enc_aes_key)

        # === Step 6: RSA-encrypt IV and send ===
        # Verified from Wireshark: phone sends length=128 + 128B
        enc_iv = rsa_encrypt(rsa_pubkey, iv)
        print(f"Sending RSA-encrypted IV: {len(enc_iv)}B")
        write_le_int(sock, len(enc_iv))
        sock.sendall(enc_iv)

        # === Step 7: RSA-encrypt third block and send ===
        # VERIFIED from Wireshark: phone sends length=128 + 128B
        # CONTENT UNKNOWN — could be:
        #   (a) device UUID (TcpClient.deviceUUID field — 36-char string)
        #   (b) PIN itself (4-digit string)
        #   (c) A hash/HMAC of the AES key + IV
        #   (d) A nonce for session token generation
        #
        # The TcpClient constructor takes a deviceUUID parameter:
        #   public TcpClient(Context, DeviceItem, Function1, String deviceUUID)
        # This is the most likely candidate.
        third_data = device_uuid.encode("utf-8")
        enc_third = rsa_encrypt(rsa_pubkey, third_data)
        print(f"Sending RSA-encrypted third block (deviceUUID?): {len(enc_third)}B")
        write_le_int(sock, len(enc_third))
        sock.sendall(enc_third)

        # === Step 8: Build + AES-encrypt EncAuthRequest and send ===
        # Mirrors sendEncryptedGSONObject + EncAuthRequest class
        # Verified from Wireshark: phone sends length=176 + 176B
        auth_request = {
            "pin": pin,
            "name": device_name,
            "deviceUserName": device_name,
            "displayWidth": display_width,
            "displayHeight": display_height,
            "version": CONNECTION_PROTOCOL_VERSION,  # = 4
            "mpkgVersion": MPKG_SUPPORT_VERSION,       # = "PKGM0020"
            "appVersion": app_version,
        }
        print(f"Sending AES-encrypted EncAuthRequest: {json.dumps(auth_request)[:200]}")
        send_encrypted_json(sock, aes_key, iv, auth_request)

        # === Step 9: Read PC's responses ===
        # VERIFIED from Wireshark: PC sends 3 × (length=32 + 32B)
        # Content of each response is AES-encrypted JSON.
        # Response 1: likely EncAuthResponse ({"status":"ok", ...})
        # Response 2: likely capability/list response
        # Response 3: same as Response 2 (periodic ack)
        sock.settimeout(30)
        for i in range(3):
            try:
                resp = read_encrypted_json(sock, aes_key, iv)
                print(f"Received PC response {i+1}: {json.dumps(resp)[:300]}")
            except Exception as e:
                print(f"Failed to read PC response {i+1}: {e}")
                break

        # === Step 10: Start heartbeat thread ===
        # Mirrors connector$lambda$2$lambda$1:
        #   while (shouldRun) {
        #     for (int i = 1; i < 31; i++) { Thread.sleep(100L); }  // 3 sec
        #     dataOutputStream.writeBytes("heartbeat\n");
        #   }
        print("Pairing handshake complete. Starting heartbeat...")
        heartbeat_stop = threading.Event()

        def heartbeat_loop():
            while not heartbeat_stop.is_set():
                heartbeat_stop.wait(HEARTBEAT_INTERVAL_SEC)
                if heartbeat_stop.is_set():
                    break
                try:
                    sock.sendall(HEARTBEAT_PAYLOAD)
                    print(f"  [heartbeat] sent 'heartbeat\\n'")
                except Exception as e:
                    print(f"  [heartbeat] send failed: {e}")
                    break

        hb_thread = threading.Thread(target=heartbeat_loop, daemon=True)
        hb_thread.start()

        # === Step 11: Enter command loop ===
        # Read any further encrypted commands from the PC
        print("Entering command loop (Ctrl-C to stop)...")
        sock.settimeout(1)
        while True:
            try:
                cmd = read_encrypted_json(sock, aes_key, iv)
                print(f"  [command] {json.dumps(cmd)[:300]}")
                # Send a generic "ok" response
                send_encrypted_json(sock, aes_key, iv, {"status": "AuthOK"})
            except socket.timeout:
                continue
            except KeyboardInterrupt:
                break
            except Exception as e:
                print(f"  [command loop] ended: {e}")
                break

    except Exception as e:
        print(f"Pairing error: {e}")
        return False
    finally:
        sock.close()
        print("Connection closed.")
    return True


def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="Reconstructed WE pairing client (connector$lambda$2)")
    parser.add_argument("--host", default="127.0.0.1", help="PC host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=7889, help="PC TCP port (default: 7889)")
    parser.add_argument("--pin", default="1234", help="4-digit PIN (default: 1234)")
    parser.add_argument("--name", default="WepWawet", help="Device name")
    parser.add_argument("--uuid", default="wepwawet-test-uuid-12345678", help="Device UUID")
    parser.add_argument("--width", type=int, default=1080, help="Display width")
    parser.add_argument("--height", type=int, default=2400, help="Display height")
    args = parser.parse_args()

    perform_pairing(
        pc_host=args.host,
        pc_port=args.port,
        pin=args.pin,
        device_name=args.name,
        device_uuid=args.uuid,
        display_width=args.width,
        display_height=args.height,
    )


if __name__ == "__main__":
    main()
