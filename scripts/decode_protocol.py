#!/usr/bin/env python3
"""Deep protocol analyzer — concatenates TCP segments per direction
and parses length-prefixed frames from the real WE pairing capture."""
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

# Group TCP 7889 packets by flow, ordered by time
flows = defaultdict(list)
for ts, buf in packets:
    try:
        eth = dpkt.ethernet.Ethernet(buf)
        if not isinstance(eth.data, dpkt.ip.IP):
            continue
        ip = eth.data
        if not isinstance(ip.data, dpkt.tcp.TCP):
            continue
        tcp = ip.data
        if tcp.sport != 7889 and tcp.dport != 7889:
            continue
        src_bytes = bytes(ip.src) if not isinstance(ip.src, bytes) else ip.src
        dst_bytes = bytes(ip.dst) if not isinstance(ip.dst, bytes) else ip.dst
        src = ".".join(str(b) for b in src_bytes)
        dst = ".".join(str(b) for b in dst_bytes)
        key = tuple(sorted([(src, tcp.sport), (dst, tcp.dport)]))
        flows[key].append((ts, src, tcp.sport, dst, tcp.dport, bytes(tcp.data), tcp.flags))
    except Exception:
        continue

for flow_key, pkts in flows.items():
    (a, b) = flow_key
    print()
    print("=" * 72)
    print(f"FLOW: {a[0]}:{a[1]} <-> {b[0]}:{b[1]}  ({len(pkts)} packets)")
    print("=" * 72)
    pkts.sort(key=lambda x: x[0])

    # Build per-direction stream
    pc_to_phone = b""
    phone_to_pc = b""
    for ts, src, sport, dst, dport, payload, flags in pkts:
        # PC is the side with port 7889 (server)
        if dport == 7889:
            phone_to_pc += payload
        else:
            pc_to_phone += payload

    print(f"\nPC  -> phone: {len(pc_to_phone)} bytes total")
    print(f"phone -> PC:  {len(phone_to_pc)} bytes total")

    # Parse frames from PC->phone direction
    print("\n--- PC -> phone frame parse ---")
    pos = 0
    msg_idx = 0
    while pos < len(pc_to_phone):
        # Read 4-byte LE int
        if pos + 4 > len(pc_to_phone):
            print(f"  [{msg_idx}] @{pos}: only {len(pc_to_phone) - pos} trailing bytes: "
                  f"{pc_to_phone[pos:].hex()}")
            break
        val = struct.unpack_from("<I", pc_to_phone, pos)[0]
        # If the value is small (1-1024), treat as length prefix
        if 1 <= val <= 1024 and pos + 4 + val <= len(pc_to_phone):
            data = pc_to_phone[pos + 4:pos + 4 + val]
            print(f"  [{msg_idx}] @{pos}: length={val}, data ({len(data)}B): {data.hex()}")
            pos += 4 + val
            msg_idx += 1
        else:
            # Treat as a literal 4-byte int (e.g., version hello)
            print(f"  [{msg_idx}] @{pos}: literal u32 LE = {val}  (0x{val:08x})  "
                  f"bytes={pc_to_phone[pos:pos+4].hex()}")
            pos += 4
            msg_idx += 1

    # Parse frames from phone->PC direction
    print("\n--- phone -> PC frame parse ---")
    pos = 0
    msg_idx = 0
    while pos < len(phone_to_pc):
        if pos + 4 > len(phone_to_pc):
            print(f"  [{msg_idx}] @{pos}: only {len(phone_to_pc) - pos} trailing bytes: "
                  f"{phone_to_pc[pos:].hex()}")
            break
        val = struct.unpack_from("<I", phone_to_pc, pos)[0]
        if 1 <= val <= 1024 and pos + 4 + val <= len(phone_to_pc):
            data = phone_to_pc[pos + 4:pos + 4 + val]
            print(f"  [{msg_idx}] @{pos}: length={val}, data ({len(data)}B): {data.hex()}")
            pos += 4 + val
            msg_idx += 1
        else:
            print(f"  [{msg_idx}] @{pos}: literal u32 LE = {val}  (0x{val:08x})  "
                  f"bytes={phone_to_pc[pos:pos+4].hex()}")
            pos += 4
            msg_idx += 1
