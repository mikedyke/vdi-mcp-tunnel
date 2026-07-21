"""Generate vectors/parity-vectors.txt from the host (Python) implementation.

That file is the single artefact both codec test suites check against:

    host/tests/test_parity.py                                   (Python)
    bridge-plugin/src/test/kotlin/com/vditunnel/bridge/ParityTest.kt   (Kotlin)

Each record carries its own inputs and the expected output, so both suites
recompute from the inputs rather than trusting a hand-copied constant.

Regenerate ONLY when the wire protocol itself changes -- a diff in this file is
a deliberate protocol change, and PROTOCOL.md must change with it. Run from the
repo root:  python host/tools/gen_parity_vectors.py
"""
import os
import sys

_HOST = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, _HOST)

from vdi_tunnel import fountain, protocol  # noqa: E402

OUT = os.path.join(os.path.dirname(_HOST), "vectors", "parity-vectors.txt")

# Payloads referenced by the SYMBOL / SHATAIL / ZLIB records.
P_RAMP = bytes(range(37))                       # 37B -> k=5 at symbol_size 8 (last block padded)
P_JSON = b'{"jsonrpc":"2.0","id":1,"result":{"ok":true}}'
P_RUNS = b"A" * 200                             # compresses well
P_RAND = bytes((i * 167 + 13) % 256 for i in range(64))   # does not compress
# Deliberately NOT the ramp: a ramp is XOR-symmetric across equal-sized blocks,
# so full-degree symbols over it collapse to a single block and would hide
# neighbour-ordering / padding bugs.
P_ASYM = bytes((i * 97 + 31) % 256 for i in range(37))

NEIGHBOR_CASES = [
    # the nine vectors published in PROTOCOL.md
    (10, 10, 8), (11, 10, 8), (25, 10, 8), (100, 10, 8),
    (7, 5, 8), (9, 5, 8), (50, 7, 8), (4, 4, 8), (8, 4, 8),
    # edges: systematic ids, k<=1, k=2 (degree clamp), dmax clamp, u24-max symbol id
    (0, 10, 8), (9, 10, 8), (0, 1, 8), (5, 1, 8), (3, 2, 8),
    (10, 10, 2), (10, 10, 4), (10, 3, 8), (16777215, 10, 8), (65536, 1000, 8),
]

SYMBOL_CASES = [
    (P_ASYM, sid, 8, 8) for sid in (0, 4, 5, 6, 12, 99)
] + [
    (P_JSON, sid, 16, 8) for sid in (0, 2, 3, 7, 40)
]

RESP_CASES = [
    # gen_id, symbol_id, k, payload_len, codec, sha, symbol
    (1, 0, 1, 12, 0x01, 0xDEADBEEF, bytes(range(16))),
    (0xFFFF, 0xFFFFFF, 0xFFFF, 0xFFFFFFFF, 0x00, 0xFFFFFFFF, b""),
    (7, 300, 12, 5900, 0x01, protocol.sha_tail(P_JSON), P_JSON[:24]),
]

HEARTBEAT_CASES = [
    # gen_id, state, rx_hash, bridge_ver, schema_hash
    (0, 0, 0, 1, 0),
    (42, 2, 0x89ABCDEF, 7, 0x12345678),
    (0xFFFF, 4, 0xFFFFFFFF, 0xFF, 0xFFFFFFFF),
]

REQ_CASES = [
    # gen_id, seq, total, codec, chunk
    (1, 0, 1, 0x01, b'{"id":1}'),
    (0xFFFF, 0xFFFE, 0xFFFF, 0x00, P_RAMP),
    (3, 2, 9, 0x01, b""),
]

ACK_CASES = [
    (0, b""),
    (0, b'{"id":1}'),
    (0x2144DF1C, P_RAMP),          # seeded with a previous chunk's CRC (the rolling case)
    (0xFFFFFFFF, b"\x00" * 8),
]

SHATAIL_CASES = [b"", P_JSON, P_RAMP, P_RUNS]
ZLIB_CASES = [P_JSON, P_RUNS, P_RAND, b""]


def _h(b: bytes) -> str:
    return b.hex() if b else "-"


def build() -> str:
    L = [
        "# Cross-language codec parity vectors -- GENERATED, do not hand-edit.",
        "# Source of truth: host/vdi_tunnel/{protocol,fountain}.py, via",
        "# host/tools/gen_parity_vectors.py. Asserted by host/tests/test_parity.py and",
        "# bridge-plugin/src/test/kotlin/com/vditunnel/bridge/ParityTest.kt.",
        "#",
        "# One record per line:  NAME <args...> => <expected...>",
        "# Byte strings are lowercase hex; '-' is the empty string.",
        "",
        "# NEIGHBORS symbolId k dmax => sorted source indices, comma-separated",
    ]
    for sid, k, dmax in NEIGHBOR_CASES:
        ns = fountain._neighbors(sid, k, dmax)
        L.append(f"NEIGHBORS {sid} {k} {dmax} => {','.join(map(str, ns))}")

    L += ["", "# SYMBOL payloadHex symbolId symbolSize dmax => encoded symbol"]
    for payload, sid, ssize, dmax in SYMBOL_CASES:
        sym = fountain.encode_symbol(payload, sid, ssize, dmax)
        L.append(f"SYMBOL {_h(payload)} {sid} {ssize} {dmax} => {_h(sym)}")

    L += ["", "# RESP genId symbolId k payloadLen codec shaHex symbolHex => framed bytes"]
    for gen, sid, k, plen, codec, sha, sym in RESP_CASES:
        frame = protocol.pack_resp(gen, sid, k, plen, codec, sha, sym)
        L.append(f"RESP {gen} {sid} {k} {plen} {codec} {sha:08x} {_h(sym)} => {_h(frame)}")

    L += ["", "# HEARTBEAT genId state rxHashHex bridgeVer schemaHashHex => framed bytes"]
    for gen, state, rx, bver, schema in HEARTBEAT_CASES:
        frame = _pack_heartbeat(gen, state, rx, bver, schema)
        L.append(f"HEARTBEAT {gen} {state} {rx:08x} {bver} {schema:08x} => {_h(frame)}")

    L += ["", "# REQ genId seq total codec chunkHex => base64 line as typed into the textarea"]
    for gen, seq, total, codec, chunk in REQ_CASES:
        line = protocol.req_to_line(protocol.pack_req(gen, seq, total, codec, chunk))
        L.append(f"REQ {gen} {seq} {total} {codec} {_h(chunk)} => {line}")

    L += ["", "# ACK prevHex chunkHex => rolling CRC32 (zlib.crc32(chunk, prev))"]
    for prev, chunk in ACK_CASES:
        L.append(f"ACK {prev:08x} {_h(chunk)} => {protocol.rolling_ack(prev, chunk):08x}")

    L += ["", "# SHATAIL payloadHex => low 4 bytes of SHA-256, big-endian"]
    for payload in SHATAIL_CASES:
        L.append(f"SHATAIL {_h(payload)} => {protocol.sha_tail(payload):08x}")

    L += ["", "# ZLIB rawHex => codec compressedHex  (deflate output need not be",
          "# byte-identical across languages -- the codec CHOICE must match, and each",
          "# side must inflate the other's bytes back to raw.)"]
    for raw in ZLIB_CASES:
        codec, data = protocol.compress(raw)
        L.append(f"ZLIB {_h(raw)} => {codec} {_h(data)}")

    return "\n".join(L) + "\n"


def _pack_heartbeat(gen_id, state, rx_hash, bridge_ver, schema_hash) -> bytes:
    """The host only ever *reads* heartbeats, so protocol.py has no packer.

    This mirrors the frame layout in PROTOCOL.md so the bridge's packHeartbeat
    has something to be checked against; unpack_heartbeat verifies the result.
    """
    import struct, zlib
    body = struct.pack(">HBBHBIBI", protocol.MAGIC_DOWN, protocol.VERSION,
                       protocol.TYPE_HEARTBEAT, gen_id, state, rx_hash,
                       bridge_ver, schema_hash)
    return body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)


if __name__ == "__main__":
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(build())
    print(f"wrote {OUT}")
