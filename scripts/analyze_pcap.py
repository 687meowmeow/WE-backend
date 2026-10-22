#!/usr/bin/env python3
"""Analyze the wireshark.pcapng capture for WE pairing protocol."""
import dpkt
from collections import defaultdict

pcap_path = "/home/z/my-project/upload/wireshark.pcapng"

with open(pcap_path, "rb") as f:
    try:
        reader = dpkt.pcapng.Reader(f)
    except Exception as e:
        print(f"pcapng failed: {e}")
        f.seek(0)
        reader = dpkt.pcap.Reader(f)
    packets = list(reader)

print(f"Total packets: {len(packets)}")

udp_7884 = []
tcp_7889 = []
for ts, buf in packets:
    try:
        eth = dpkt.ethernet.Ethernet(buf)
        if not isinstance(eth.data, dpkt.ip.IP):
            continue
        ip = eth.data
        src_bytes = bytes(ip.src) if not isinstance(ip.src, bytes) else ip.src
        dst_bytes = bytes(ip.dst) if not isinstance(ip.dst, bytes) else ip.dst
        src = ".".join(str(b) for b in src_bytes)
        dst = ".".join(str(b) for b in dst_bytes)
        if isinstance(ip.data, dpkt.udp.UDP):
            if ip.data.sport == 7884 or ip.data.dport == 7884:
                udp_7884.append((ts, src, dst, ip.data))
        elif isinstance(ip.data, dpkt.tcp.TCP):
            if ip.data.sport == 7889 or ip.data.dport == 7889:
                tcp_7889.append((ts, src, dst, ip.data))
    except Exception:
        continue

print(f"UDP 7884 packets: {len(udp_7884)}")
print(f"TCP 7889 packets: {len(tcp_7889)}")

print()
print("=== UDP 7884 sample (first 5) ===")
for ts, src, dst, udp in udp_7884[:5]:
    payload = udp.data
    try:
        text = payload.rstrip(b'\x00').decode('utf-8', errors='replace')
    except Exception:
        text = payload.hex()
    print(f"[{ts:.3f}] {src}:{udp.sport} -> {dst}:{udp.dport} ({len(payload)}B)")
    print(f"  payload: {text!r}")

print()
print("=== TCP 7889 timeline (first 50 packets) ===")
for ts, src, dst, tcp in tcp_7889[:50]:
    flags = ""
    if tcp.flags & 0x01: flags += "F"
    if tcp.flags & 0x02: flags += "S"
    if tcp.flags & 0x04: flags += "R"
    if tcp.flags & 0x08: flags += "P"
    if tcp.flags & 0x10: flags += "A"
    payload_len = len(tcp.data)
    print(f"[{ts:.3f}] {src}:{tcp.sport} -> {dst}:{tcp.dport}  flags={flags:5s}  payload={payload_len}B")

print()
print("=== TCP 7889 payload dump (full data per direction) ===")
flows = defaultdict(list)
for ts, src, dst, tcp in tcp_7889:
    key = tuple(sorted([(src, tcp.sport), (dst, tcp.dport)]))
    flows[key].append((ts, src, dst, tcp))

for flow_key, packets in flows.items():
    print()
    (a, b) = flow_key
    print(f"Flow: {a[0]}:{a[1]} <-> {b[0]}:{b[1]}  ({len(packets)} packets)")
    packets.sort(key=lambda x: x[0])
    direction_a = b""
    direction_b = b""
    for ts, src, dst, tcp in packets:
        if (src, tcp.sport) == a:
            direction_a += tcp.data
        else:
            direction_b += tcp.data
    print(f"  Direction {a[0]}:{a[1]} -> {b[0]}:{b[1]}: {len(direction_a)} bytes")
    if direction_a:
        print(f"    hex: {direction_a[:200].hex()}")
        try:
            text = direction_a[:200].decode('utf-8', errors='replace')
            print(f"    ascii: {text!r}")
        except Exception:
            pass
    print(f"  Direction {b[0]}:{b[1]} -> {a[0]}:{a[1]}: {len(direction_b)} bytes")
    if direction_b:
        print(f"    hex: {direction_b[:200].hex()}")
        try:
            text = direction_b[:200].decode('utf-8', errors='replace')
            print(f"    ascii: {text!r}")
        except Exception:
            pass
