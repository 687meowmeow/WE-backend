#!/usr/bin/env python3
"""
we_discovery_broadcaster.py — Broadcast WallpaperEngine/Ping packets on UDP 7884
so the official WE Android app can discover this machine as a "PC".

PROTOCOL (from decompiled DiscoverService.java)
================================================
The phone's DiscoverService creates a `MulticastSocket(7884)`, joins the
multicast group `239.100.0.1`, and listens for 128-byte DatagramPackets.
It parses the payload as UTF-8 and splits on ':'. Expected layout:

    WallpaperEngine/Ping:<port-or-pid>:<hostname>:<protocolVersion>:<guid>

Constraints (verified in DiscoverService.beginDiscovery$lambda$0):
  - Payload must start with the literal string "WallpaperEngine/Ping:"
  - Splitting by ':' must produce exactly 5 parts
  - parts[1] must be exactly 5 characters long
  - parts[3] is parsed as an integer (protocolVersion)
  - parts[4] is treated as a deviceGuid

The phone builds a DeviceItem:
    name = parts[2]
    ip   = source IP of the UDP packet (auto)
    port = int(parts[1])
    guid = parts[4]
    protocolVersion = int(parts[3])

So parts[1] becomes the TCP port the phone will connect to (must be 5 chars).
For the standard WE pairing port 7889, we send "07889" (zero-padded to 5 chars).

USAGE
=====
    python we_discovery_broadcaster.py [--port 7889] [--name MyPC] [--guid abcd1234]
                                        [--interval 5] [--iface <ip>]
    python we_discovery_broadcaster.py --help

Run this on the same machine as `we_pairing_server.py` (or the APK itself).
The phone will see this machine appear in its "Discover devices" list.

EXIT CODES
==========
    0  interrupted by user (Ctrl-C)
    1  socket failure (port in use, no multicast, etc.)
    2  bad arguments
"""

from __future__ import annotations

import argparse
import os
import random
import socket
import struct
import sys
import threading
import time
from pathlib import Path

MULTICAST_GROUP = "239.100.0.1"
UDP_PORT = 7884
DEFAULT_TCP_PORT = 7889
DEFAULT_INTERVAL = 5  # seconds between broadcasts


def build_ping(port: int, hostname: str, protocol_version: int, guid: str) -> bytes:
    """Build a WallpaperEngine/Ping payload, padded to 128 bytes."""
    # parts[1] must be 5 chars long — zero-pad the port.
    port_str = f"{port:05d}"
    if len(port_str) != 5:
        raise ValueError(f"port must fit in 5 chars: {port}")
    payload = f"WallpaperEngine/Ping:{port_str}:{hostname}:{protocol_version}:{guid}"
    payload_bytes = payload.encode("utf-8")
    if len(payload_bytes) > 128:
        raise ValueError(f"payload too long ({len(payload_bytes)} bytes): {payload!r}")
    # Pad to 128 bytes (DiscoverService uses 128-byte DatagramPacket)
    return payload_bytes.ljust(128, b"\x00")


def broadcast(port: int, hostname: str, guid: str,
              interval: float = DEFAULT_INTERVAL,
              iface_ip: str = "0.0.0.0",
              tcp_port: int = DEFAULT_TCP_PORT) -> int:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    # Set the multicast TTL to 1 (local subnet)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
    if iface_ip and iface_ip != "0.0.0.0":
        try:
            sock.setsockopt(
                socket.IPPROTO_IP, socket.IP_MULTICAST_IF,
                socket.inet_aton(iface_ip),
            )
        except OSError as e:
            print(f"[broadcast] WARNING: could not set multicast interface to "
                  f"{iface_ip}: {e}", file=sys.stderr)

    payload = build_ping(tcp_port, hostname, protocol_version=1, guid=guid)
    print(f"=== WE Discovery Broadcaster ===")
    print(f"Multicast group: {MULTICAST_GROUP}:{UDP_PORT}")
    print(f"Hostname:        {hostname}")
    print(f"GUID:            {guid}")
    print(f"TCP port (sent): {tcp_port:05d}")
    print(f"Interval:        {interval}s")
    print(f"Payload:         {payload[:80].rstrip(chr(0))!r}")
    print(f"Press Ctrl-C to stop.")
    print()

    try:
        while True:
            # Send to multicast group
            try:
                sock.sendto(payload, (MULTICAST_GROUP, UDP_PORT))
            except OSError as e:
                print(f"[broadcast] multicast send failed: {e}", file=sys.stderr)
            # Also send a broadcast to subnet (some networks block multicast)
            try:
                bcast_addr = "255.255.255.255"
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                sock.sendto(payload, (bcast_addr, UDP_PORT))
            except OSError:
                pass
            # And loopback for testing on the same machine
            try:
                sock.sendto(payload, ("127.0.0.1", UDP_PORT))
            except OSError:
                pass
            time.sleep(interval)
    except KeyboardInterrupt:
        print("\n[broadcast] Stopping.")
        return 0
    finally:
        sock.close()


def detect_hostname() -> str:
    """Best-effort hostname detection."""
    try:
        return socket.gethostname() or "WepWawet"
    except Exception:
        return "WepWawet"


def detect_local_ip() -> str:
    """Detect the local LAN IP by connecting a dummy UDP socket."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 53))
            return s.getsockname()[0]
        finally:
            s.close()
    except Exception:
        return "0.0.0.0"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="we_discovery_broadcaster",
        description="Broadcast WallpaperEngine/Ping on UDP 7884 so the "
                    "official WE Android app can discover this machine.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--port", type=int, default=DEFAULT_TCP_PORT,
                       help=f"TCP pairing port to advertise (default: {DEFAULT_TCP_PORT}). "
                            f"Must fit in 5 digits.")
    parser.add_argument("--name", default=detect_hostname(),
                       help="Hostname to advertise (default: this machine's hostname)")
    parser.add_argument("--guid", default=f"wepwawet-{random.randint(0, 0xFFFFFFFF):08x}",
                       help="Device GUID to advertise (default: random)")
    parser.add_argument("--interval", type=float, default=DEFAULT_INTERVAL,
                       help=f"Seconds between broadcasts (default: {DEFAULT_INTERVAL})")
    parser.add_argument("--iface", default=detect_local_ip(),
                       help="Local IP to send multicast from (default: auto-detect)")
    args = parser.parse_args(argv)

    if not (0 <= args.port <= 99999):
        print(f"error: --port must be a 5-digit port (0..99999); got {args.port}",
              file=sys.stderr)
        return 2

    return broadcast(args.port, args.name, args.guid, args.interval, args.iface,
                     args.port)


if __name__ == "__main__":
    sys.exit(main())
