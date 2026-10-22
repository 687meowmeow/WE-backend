#!/usr/bin/env python3
"""Per-packet analyzer for the wireshark.pcapng WE pairing capture.
Decodes the actual wire format message-by-message."""
import dpkt
import struct
from collections import defaultdict

pcap_path = "/home/z/my-project/upload/wireshark.pcapng"

with open(pcap_path, "rb") as f:
    try:
        reader = dpkt.pcapng.Reader(f)
    except Exception:
        f.seek(0)
        reader = dpkt.pcap.Reader(f)
    packets = list(reader)

print(f"Total packets: {len(packets)}")

# Filter to TCP 7889 traffic and group by TCP flow
flows = defaultdict(list)
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
        if isinstance(ip.data, dpkt.tcp.TCP):
            tcp = ip.data
            if tcp.sport == 7889 or tcp.dport == 7889:
                key = tuple(sorted([(src, tcp.sport), (dst, tcp.dport)]))
                flows[key].append((ts, src, dst, tcp))
    except Exception:
        continue

for flow_key, pkts in flows.items():
    (a, b) = flow_key
    print()
    print("=" * 72)
    print(f"FLOW: {a[0]}:{a[1]} <-> {b[0]}:{b[1]}  ({len(pkts)} packets)")
    print("=" * 72)
    pkts.sort(key=lambda x: x[0])
    for i, (ts, src, dst, tcp) in enumerate(pkts):
        flags = ""
        if tcp.flags & 0x01: flags += "F"
        if tcp.flags & 0x02: flags += "S"
        if tcp.flags & 0x04: flags += "R"
        if tcp.flags & 0x08: flags += "P"
        if tcp.flags & 0x10: flags += "A"
        direction = "->" if (src, tcp.sport) == a else "<-"
        payload = bytes(tcp.data)
        plen = len(payload)
        print(f"  [{i:2d}] [{ts:.3f}] {a[0]}:{tcp.sport if (src,tcp.sport)==a else tcp.dport} {direction} "
              f"{b[0]}:{tcp.dport if (src,tcp.sport)==a else tcp.sport}  flags={flags:5s}  payload={plen}B")
        if plen > 0 and plen <= 600:
            # Print as hex
            print(f"        hex: {payload.hex()}")
            # Try to decode length-prefixed messages
            pos = 0
            msg_idx = 0
            while pos + 4 <= plen:
                length = struct.unpack_from("<I", payload, pos)[0]
                if 1 <= length <= 1024 and pos + 4 + length <= plen:
                    msg = payload[pos + 4:pos + 4 + length]
                    print(f"        msg[{msg_idx}] @{pos}: len={length}  data={msg.hex()}")
                    pos += 4 + length
                    msg_idx += 1
                else:
                    # Maybe it's a literal 4-byte int (version hello?)
                    val = struct.unpack_from("<I", payload, pos)[0]
                    if 0 <= val <= 100:
                        print(f"        @{pos}: u32 LE = {val} (literal? version hello?)")
                        pos += 4
                        msg_idx += 1
                    else:
                        print(f"        @{pos}: u32 LE = {val} (0x{val:08x}) — not a length, stopping parse")
                        break
            if pos < plen:
                print(f"        trailing {plen - pos} bytes: {payload[pos:].hex()}")
