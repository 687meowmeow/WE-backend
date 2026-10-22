#!/usr/bin/env python3
"""we_protocol_capture.py — Print cross-platform Wireshark/tshark capture
commands for the WE pairing protocol, with a live discovery monitor.

The WE pairing protocol uses (VERIFIED from real capture 2026-10-22):
  - UDP port 7884 (multicast 239.100.0.1 + subnet broadcast + loopback)
    Format: WallpaperEngine/Ping:<5-char-port>:<hostname>:<protVer>:<guid>
    Interval: ~1.3 seconds
  - TCP port 7889 (pairing + AES-encrypted file transfer)
    Framing: 4-byte LITTLE-ENDIAN length prefix + N bytes of data
    PC sends first: Frame 0 = 3-byte RSA exponent (`01 00 01` = 65537),
                     Frame 1 = 128-byte RSA-1024 modulus
    Phone sends: 3 × 128-byte RSA-encrypted frames + 1 × 176-byte AES-encrypted EncAuthRequest
    After pairing: phone sends "heartbeat\\n" (10 ASCII bytes, no length prefix) every ~3s
"""
from __future__ import annotations
import argparse
import platform
import socket
import struct
import sys
import time
from pathlib import Path


def detect_default_iface() -> str:
    if platform.system() == "Windows":
        return "Wi-Fi"
    if platform.system() == "Darwin":
        return "en0"
    return "wlan0"


def print_capture_commands(duration: int, iface: str) -> int:
    print("=" * 72)
    print("WE Pairing Protocol — Capture Commands (VERIFIED format)")
    print("=" * 72)
    print()
    print("Discovery (UDP 7884):")
    print("  PC broadcasts every ~1.3s to:")
    print("    - multicast 239.100.0.1:7884")
    print("    - subnet broadcast 255.255.255.255:7884 (or 192.168.1.255 etc.)")
    print("    - loopback 127.0.0.1:7884")
    print("  Payload: 'WallpaperEngine/Ping:07889:LAPTOP-NAME:4:<GUID>'  (128B padded)")
    print()
    print("Pairing (TCP 7889, all length prefixes are 4-byte LITTLE-ENDIAN u32):")
    print("  PC → phone Frame 0: length=3 + 3B exponent `01 00 01` (= 65537)")
    print("  PC → phone Frame 1: length=128 + 128B RSA-1024 modulus")
    print("  Phone → PC: 3 × (length=128 + 128B RSA-encrypted) + 1 × (length + AES-encrypted)")
    print("  After pairing: phone sends literal 'heartbeat\\n' (10 ASCII bytes, no length prefix)")
    print()
    print(f"Capture filter (BPF): 'udp port 7884 or tcp port 7889'")
    print(f"Duration:              {duration} seconds")
    print(f"Interface:             {iface}")
    print()
    print("-" * 72)
    print("Option 1: tshark (cross-platform, command-line)")
    print("-" * 72)
    print(f"""
  # Save to pcapng:
  tshark -i {iface} -a duration:{duration} \\
        -f "udp port 7884 or tcp port 7889" \\
        -w we_pairing_$(date +%Y%m%d_%H%M%S).pcapng

  # Live-decode discovery:
  tshark -i {iface} -f "udp port 7884" -V

  # Live-decode pairing/transfer:
  tshark -i {iface} -f "tcp port 7889" -V
""")
    print("-" * 72)
    print("Option 2: Wireshark GUI")
    print("-" * 72)
    print(f"""
  1. Open Wireshark.
  2. Select interface: {iface}
  3. Capture filter: 'udp port 7884 or tcp port 7889'
  4. Start capture.
  5. Pair phone with PC.
  6. Stop capture and analyze.

  Useful display filters:
    udp.port == 7884                  discovery packets
    tcp.port == 7889                  pairing + transfer
    tcp.port == 7889 && tcp.len > 0   only payload-bearing segments

  Look for:
    UDP 7884: 'WallpaperEngine/Ping:07889:<hostname>:4:<GUID>'
    TCP 7889: PC sends 4B (LE int 3) then 3B exponent `01 00 01`
    TCP 7889: PC sends 4B (LE int 128) then 128B RSA modulus
    TCP 7889: Phone sends 4 × length-prefixed frames (3×128B + 1×176B)
    TCP 7889: After pairing, phone sends literal 'heartbeat\\n' (10B, no length prefix)
""")
    print("-" * 72)
    print("Option 3: Live discovery-only monitor (no Wireshark needed)")
    print("-" * 72)
    print("""
  python we_protocol_capture.py --monitor
""")
    return 0


def monitor_discovery(port: int = 7884, group: str = "239.100.0.1") -> int:
    """Listen on UDP 7884 multicast and print every Ping packet."""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", port))
        try:
            mreq = struct.pack("4sl", socket.inet_aton(group), socket.INADDR_ANY)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        except OSError as e:
            print(f"[monitor] WARNING: multicast join failed: {e}", file=sys.stderr)
        print(f"=== WE Discovery Monitor (VERIFIED format) ===")
        print(f"Listening on UDP {port} (multicast {group} + subnet bcast + loopback)")
        print(f"Press Ctrl-C to stop.")
        print()
        while True:
            try:
                data, addr = sock.recvfrom(256)
            except KeyboardInterrupt:
                print("\n[monitor] Stopping.")
                return 0
            payload = data.rstrip(b"\x00").decode("utf-8", errors="replace")
            ts = time.strftime("%H:%M:%S")
            print(f"[{ts}] {addr[0]}:{addr[1]} -> {payload!r}")
    except OSError as e:
        print(f"[monitor] ERROR: {e}", file=sys.stderr)
        return 1


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="we_protocol_capture",
        description="Print capture commands for the WE pairing protocol.",
    )
    parser.add_argument("--duration", type=int, default=60)
    parser.add_argument("--iface", default=detect_default_iface())
    parser.add_argument("--monitor", action="store_true",
                        help="Run a live discovery-only monitor on UDP 7884")
    args = parser.parse_args(argv)
    if args.monitor:
        return monitor_discovery()
    return print_capture_commands(args.duration, args.iface)


if __name__ == "__main__":
    sys.exit(main())
