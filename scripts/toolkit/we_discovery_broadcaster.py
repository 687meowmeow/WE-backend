#!/usr/bin/env python3
"""
we_discovery_broadcaster.py — Broadcast WallpaperEngine/Ping packets on UDP 7884
so the official WE Android app can discover this machine as a "PC".

VERIFIED PROTOCOL (from real Wireshark capture 2026-10-22):
=========================================================
The PC broadcasts on THREE destinations every ~1.3 seconds:
  1. Multicast group 239.100.0.1:7884
  2. Subnet broadcast (e.g., 192.168.1.255:7884)
  3. Loopback 127.0.0.1:7884

Packet payload (128-byte DatagramPacket, UTF-8, null-padded):
    WallpaperEngine/Ping:<port 5 chars>:<hostname>:<protocolVersion>:<guid>

Verified fields from the capture:
    WallpaperEngine/Ping:07889:LAPTOP-2H7TH5UT:4:5FB2F0F0-5B62-4DA0-81F1-E15AE1DE94BE

  - parts[1] = "07889" (zero-padded 5-char TCP port; phone's DiscoverService
              requires length==5)
  - parts[2] = hostname
  - parts[3] = "4" (protocolVersion — matches CONNECTION_PROTOCOL_VERSION = 4)
  - parts[4] = deviceGuid (Windows interface GUID, UUID format with hyphens)

The phone's DiscoverService.beginDiscovery$lambda$0 splits the payload by ':'
and expects exactly 5 parts where parts[1] is 5 chars long.

USAGE
=====
    python we_discovery_broadcaster.py [--port 7889] [--name MyPC] [--guid abcd1234]
                                        [--interval 1.3] [--iface <ip>]
    python we_discovery_broadcaster.py --help

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
import uuid
from pathlib import Path

MULTICAST_GROUP = "239.100.0.1"
UDP_PORT = 7884
DEFAULT_TCP_PORT = 7889
DEFAULT_INTERVAL = 1.3  # seconds — verified from real capture (1.3s between pings)


def build_ping(port: int, hostname: str, protocol_version: int, guid: str) -> bytes:
    """Build a WallpaperEngine/Ping payload, padded to 128 bytes."""
    port_str = f"{port:05d}"
    if len(port_str) != 5:
        raise ValueError(f"port must fit in 5 chars: {port}")
    payload = f"WallpaperEngine/Ping:{port_str}:{hostname}:{protocol_version}:{guid}"
    payload_bytes = payload.encode("utf-8")
    if len(payload_bytes) > 128:
        raise ValueError(f"payload too long ({len(payload_bytes)} bytes): {payload!r}")
    return payload_bytes.ljust(128, b"\x00")


def broadcast(port: int, hostname: str, guid: str,
              interval: float = DEFAULT_INTERVAL,
              iface_ip: str = "0.0.0.0",
              tcp_port: int = DEFAULT_TCP_PORT) -> int:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
    if iface_ip and iface_ip != "0.0.0.0":
        try:
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF,
                            socket.inet_aton(iface_ip))
        except OSError as e:
            print(f"[broadcast] WARNING: could not set multicast interface to "
                  f"{iface_ip}: {e}", file=sys.stderr)

    payload = build_ping(tcp_port, hostname, protocol_version=4, guid=guid)
    print(f"=== WE Discovery Broadcaster (VERIFIED format) ===")
    print(f"Multicast group: {MULTICAST_GROUP}:{UDP_PORT}")
    print(f"Subnet broadcast: 255.255.255.255:{UDP_PORT}")
    print(f"Loopback:        127.0.0.1:{UDP_PORT}")
    print(f"Hostname:         {hostname}")
    print(f"GUID:             {guid}")
    print(f"TCP port (sent):  {tcp_port:05d}")
    print(f"ProtocolVersion:  4 (matches TransferService.CONNECTION_PROTOCOL_VERSION)")
    print(f"Interval:         {interval}s")
    print(f"Payload (truncated): {payload[:80].rstrip(chr(0))!r}")
    print(f"Press Ctrl-C to stop.")
    print()

    try:
        while True:
            # 1. Send to multicast group
            try:
                sock.sendto(payload, (MULTICAST_GROUP, UDP_PORT))
            except OSError as e:
                print(f"[broadcast] multicast send failed: {e}", file=sys.stderr)
            # 2. Send to subnet broadcast
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                sock.sendto(payload, ("255.255.255.255", UDP_PORT))
            except OSError:
                pass
            # 3. Send to loopback (for same-machine pairing)
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
    try:
        return socket.gethostname() or "WepWawet"
    except Exception:
        return "WepWawet"


def detect_local_ip() -> str:
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
        description="Broadcast WallpaperEngine/Ping on UDP 7884 (VERIFIED format).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--port", type=int, default=DEFAULT_TCP_PORT,
                        help=f"TCP pairing port to advertise (default: {DEFAULT_TCP_PORT}). "
                             f"Must fit in 5 digits.")
    parser.add_argument("--name", default=detect_hostname(),
                        help="Hostname to advertise (default: this machine's hostname)")
    parser.add_argument("--guid", default=str(uuid.uuid4()).upper(),
                        help="Device GUID to advertise (default: random UUID)")
    parser.add_argument("--interval", type=float, default=DEFAULT_INTERVAL,
                        help=f"Seconds between broadcasts (default: {DEFAULT_INTERVAL})")
    parser.add_argument("--iface", default=detect_local_ip(),
                        help="Local IP to send multicast from (default: auto-detect)")
    args = parser.parse_args(argv)

    if not (0 <= args.port <= 99999):
        print(f"error: --port must be a 5-digit port (0..99999); got {args.port}",
              file=sys.stderr)
        return 2

    return broadcast(args.port, args.name, args.guid, args.interval, args.iface, args.port)


if __name__ == "__main__":
    sys.exit(main())
