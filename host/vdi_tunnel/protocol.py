"""Frame packing/unpacking + payload codec. Mirror of Protocol.kt."""
import struct, zlib, hashlib, base64

MAGIC_DOWN = 0x5644
MAGIC_UP = 0x4344
VERSION = 1
TYPE_RESP = 0x01
TYPE_HEARTBEAT = 0x02
END_SENTINEL = b"END"

# ---- payload codec (MCP json bytes <-> compressed payload) ----
def compress(raw: bytes):
    z = zlib.compress(raw, 9)
    if len(z) < len(raw):
        return 0x01, z
    return 0x00, raw

def decompress(codec: int, data: bytes) -> bytes:
    if codec == 0x00:
        return data
    if codec == 0x01:
        return zlib.decompress(data)
    raise ValueError(f"unsupported codec {codec}")

def sha_tail(payload: bytes) -> int:
    return struct.unpack(">I", hashlib.sha256(payload).digest()[:4])[0]

def _u24(n: int) -> bytes:
    return bytes(((n >> 16) & 0xFF, (n >> 8) & 0xFF, n & 0xFF))

def _r_u24(b: bytes) -> int:
    return (b[0] << 16) | (b[1] << 8) | b[2]

# ---- downlink RESP ----
def pack_resp(gen_id, symbol_id, k, payload_len, codec, sha, symbol: bytes) -> bytes:
    body = struct.pack(">HBBH", MAGIC_DOWN, VERSION, TYPE_RESP, gen_id) \
        + _u24(symbol_id) + struct.pack(">HIBI", k, payload_len, codec, sha) + symbol
    return body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

def unpack_resp(frame: bytes):
    body, crc = frame[:-4], struct.unpack(">I", frame[-4:])[0]
    if zlib.crc32(body) & 0xFFFFFFFF != crc:
        raise ValueError("crc mismatch")
    magic, ver, typ, gen = struct.unpack(">HBBH", body[:6])
    if magic != MAGIC_DOWN:
        raise ValueError("bad magic")
    symbol_id = _r_u24(body[6:9])
    k, payload_len, codec, sha = struct.unpack(">HIBI", body[9:20])
    return dict(type=typ, gen_id=gen, symbol_id=symbol_id, k=k,
                payload_len=payload_len, codec=codec, sha=sha, symbol=body[20:])

def unpack_heartbeat(frame: bytes):
    body, crc = frame[:-4], struct.unpack(">I", frame[-4:])[0]
    if zlib.crc32(body) & 0xFFFFFFFF != crc:
        raise ValueError("crc mismatch")
    magic, ver, typ, gen, state, rx_hash, bver, schema = struct.unpack(">HBBHBIBI", body[:16])
    if magic != MAGIC_DOWN or typ != TYPE_HEARTBEAT:
        raise ValueError("not a heartbeat")
    return dict(gen_id=gen, state=state, rx_hash=rx_hash, bridge_ver=bver, schema_hash=schema)

# ---- uplink REQ ----
def pack_req(gen_id, seq, total, codec, chunk: bytes) -> bytes:
    body = struct.pack(">HBHHHB", MAGIC_UP, VERSION, gen_id, seq, total, codec) + chunk
    return body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

def req_to_line(frame: bytes) -> str:
    return base64.b64encode(frame).decode("ascii")

def rolling_ack(prev: int, chunk: bytes) -> int:
    return zlib.crc32(chunk, prev) & 0xFFFFFFFF
