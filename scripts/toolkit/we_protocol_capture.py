#!/usr/bin/env python3
"""
we_protocol_capture.py — Print cross-platform Wireshark/tshark capture
commands for the WE pairing protocol.

The WE pairing protocol uses:
  - UDP port 7884 (multicast group 239.100.0.1) — discovery
  - TCP port 7889 — pairing + AES-encrypted file transfer

This script does NOT require Wireshark to be installed; it just prints the
commands you can copy-paste into a terminal. The commands themselves are
cross-platform — `tshark` works on Windows, macOS, and Linux.

USAGE
=====
    python we_protocol_capture.py [--duration 60] [--iface wlan0]
    python we_protocol_capture.py --help

OUTPUT
======
A list of capture commands you can run on Windows, macOS, or Linux. The
script also generates a small Python "live monitor" you can run to view
discovery packets in real time without Wireshark.

EXIT CODES
==========
    0  success
    2  bad arguments
"""

from __future__ import annotations

import argparse
import platform
import sys
from pathlib import Path


def detect_default_iface() -> str:
    """Best-effort default interface name by OS."""
    if platform.system() == "Windows":
        return "Ethernet"  # or "Wi-Fi"
    if platform.system() == "Darwin":
        return "en0"
    return "wlan0"  # Linux default


def print_capture_commands(duration: int, iface: str) -> int:
    print("=" * 72)
    print("WE Pairing Protocol — Capture Commands")
    print("=" * 72)
    print()
    print("The pairing protocol uses:")
    print("  UDP 7884   Discovery (multicast 239.100.0.1)")
    print("    Phone listens for: 'WallpaperEngine/Ping:<port>:<name>:1:<guid>'")
    print("  TCP 7889   Pairing + AES-encrypted file transfer")
    print("    RSA pubkey exchange → AES key/IV exchange → encrypted JSON")
    print()
    print(f"Capture filter (BPF): 'udp port 7884 or tcp port 7889'")
    print(f"Duration:              {duration} seconds")
    print(f"Interface:             {iface}")
    print()
    print("-" * 72)
    print("Option 1: tshark (cross-platform, command-line)")
    print("-" * 72)
    print(f"""
  # Save to a pcapng file you can later open in Wireshark:
  tshark -i {iface} -a duration:{duration} \\
        -f "udp port 7884 or tcp port 7889" \\
        -w we_pairing_$(date +%Y%m%d_%H%M%S).pcapng

  # Live-decode the discovery packets (UDP 7884):
  tshark -i {iface} -f "udp port 7884" -V

  # Live-decode the pairing/transfer (TCP 7889):
  tshark -i {iface} -f "tcp port 7889" -V
""")
    print("-" * 72)
    print("Option 2: Wireshark GUI (cross-platform)")
    print("-" * 72)
    print(f"""
  1. Open Wireshark.
  2. Select interface: {iface}
  3. In the capture filter box, enter:
        udp port 7884 or tcp port 7889
  4. Start capture.
  5. Pair your phone with the PC.
  6. Stop capture and analyze.

  Useful display filters:
    udp.port == 7884                  discovery packets
    tcp.port == 7889                  pairing + transfer
    tcp.port == 7889 && tcp.len > 0   only payload-bearing segments

  Look for:
    UDP 7884: 'WallpaperEngine/Ping:<port>:<hostname>:1:<guid>'
    TCP 7889: RSA modulus (256 bytes for 2048-bit key)
    TCP 7889: AES key + IV (RSA-encrypted, ~256 bytes)
    TCP 7889: AES/CBC/PKCS5Padding ciphertext (length-prefixed LE)
    TCP 7889: 'heartbeat\\n' (10 ASCII bytes, no length prefix)
""")
    print("-" * 72)
    print("Option 3: Live discovery-only monitor (no Wireshark needed)")
    print("-" * 72)
    print("""
  # Listen on UDP 7884 multicast and print every Ping packet:
  python we_protocol_capture.py --monitor
""")
    print("-" * 72)
    print("Notes:")
    print("-" * 72)
    print("""
  - On Windows, replace 'wlan0' with 'Wi-Fi' or 'Ethernet'.
  - On macOS, replace 'wlan0' with 'en0' (Wi-Fi) or 'en1' (Ethernet).
  - On Linux, use 'ip link' to list interfaces.
  - Multicast (239.100.0.1) may be blocked on managed switches;
    use a direct Wi-Fi hotspot or unicast broadcast for testing.
  - Pairing uses LITTLE-ENDIAN length prefixes; Wireshark's default
    TCP desegregation won't decode the framing, but you can see the
    raw bytes in the 'Follow TCP Stream' view.
""")
    return 0


def monitor_discovery(port: int = 7884, group: str = "239.100.0.1") -> int:
    """Listen on UDP 7884 multicast and print every Ping packet."""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", port))
        # Join multicast group
        try:
            mreq = struct.pack("4sl", socket.inet_aton(group), socket.INADDR_ANY)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        except OSError as e:
            print(f"[monitor] WARNING: multicast join failed: {e}",
                  file=sys.stderr)
        print(f"=== WE Discovery Monitor ===")
        print(f"Listening on UDP {port} (multicast {group})")
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


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="we_protocol_capture",
        description="Print capture commands for the WE pairing protocol.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--duration", type=int, default=60,
                       help="Capture duration in seconds (default: 60)")
    parser.add_argument("--iface", default=detect_default_iface(),
                       help="Network interface name (default by OS)")
    parser.add_argument("--monitor", action="store_true",
                       help="Run a live discovery-only monitor on UDP 7884")
    args = parser.parse_args(argv)
    if args.monitor:
        return monitor_discovery()
    return print_capture_commands(args.duration, args.iface)


if __name__ == "__main__":
    import socket
    import struct
    import time
    sys.exit(main())
