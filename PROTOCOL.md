# Wire protocol (shared reference for host + bridge)

All integers big-endian. Every frame ends with CRC32 over all preceding bytes.
Payload pipeline (both directions): `MCP JSON (utf-8) -> zlib -> frame(s)`. If zlib
is larger than raw, send raw. `codec`: `0x00` raw, `0x01` zlib (default), `0x02` zstd (future).

## Downlink RESP frame (carried inside one QR image)
| off | field       | type | notes |
|-----|-------------|------|-------|
| 0   | magic       | u16  | 0x5644 |
| 2   | version     | u8   | 1 |
| 3   | type        | u8   | 0x01 RESP |
| 4   | gen_id      | u16  | per-response generation |
| 6   | symbol_id   | u24  | LT symbol index (0..K-1 = systematic, >=K = repair) |
| 9   | k           | u16  | source-symbol count |
| 11  | payload_len | u32  | total compressed payload length |
| 15  | codec       | u8   | see above |
| 16  | sha_tail    | u32  | low 4 bytes of SHA-256(payload) |
| 20  | symbol      | var  | SYMBOL_SIZE bytes (last may be short via payload_len) |
| ..  | crc32       | u32  | |

## Downlink HEARTBEAT frame (type 0x02, always animating in a corner QR)
| off | field       | type | notes |
|-----|-------------|------|-------|
| 0   | magic       | u16  | 0x5644 |
| 2   | version     | u8   | 1 |
| 3   | type        | u8   | 0x02 |
| 4   | gen_id      | u16  | current/last response gen |
| 6   | state       | u8   | 0 idle,1 receiving,2 forwarding,3 sending,4 error |
| 7   | rx_hash     | u32  | rolling CRC32 of uplink chunks received (ACK) |
| 11  | bridge_ver  | u8   | |
| 12  | schema_hash | u32  | hash of tools/list; host re-pulls on change |
| 16  | crc32       | u32  | |

## Uplink REQ frame (typed as one base64 line into the textarea; `END` sentinel closes a message)
| off | field    | type | notes |
|-----|----------|------|-------|
| 0   | magic    | u16  | 0x4344 |
| 2   | version  | u8   | 1 |
| 3   | gen_id   | u16  | per-request generation |
| 5   | seq      | u16  | chunk index |
| 7   | total    | u16  | total chunks |
| 9   | codec    | u8   | |
| 10  | chunk    | var  | <= FRAME_PAYLOAD compressed bytes |
| ..  | crc32    | u32  | |

## Stop-and-wait ARQ (uplink reliability)
Host types REQ seq -> reads heartbeat.rx_hash -> expected rolling CRC32 of chunks sent ->
match => next; mismatch/timeout => resend (backoff, cap ARQ_MAX_RETRIES). After `total`
chunks, type `END`. rx_hash update: `rx_hash = crc32(chunk, rx_hash_prev)`.

## LT fountain (identical in both languages — see fountain.py / Fountain.kt)
- payload split into K = ceil(payload_len / SYMBOL_SIZE) blocks (last zero-padded).
- symbol s < K  : systematic, neighbors = {s}, data = block[s].
- symbol s >= K : repair, neighbors + degree derived from a shared 64-bit LCG seeded by s.
- decoder: peeling (belief propagation). Complete when all K blocks solved; trim to
  payload_len; verify SHA-256.
- Deterministic PRNG (must match exactly):
    GOLDEN = 0x9E3779B97F4A7C15
    lcg(x) = (x*6364136223846793005 + 1442695040888963407) mod 2^64
    seed(s) = lcg(s XOR GOLDEN)
    draw high bits via (state >> 33)

## Cross-language parity vectors (MUST match; add as a bridge unit test)
# Fountain parity vectors (dmax=8) — Kotlin Fountain.neighbors must match:
#   neighbors(symbolId=10, k=10) = [1, 5, 6]
#   neighbors(symbolId=11, k=10) = [1, 5, 6, 7]
#   neighbors(symbolId=25, k=10) = [0, 1, 3, 6, 8, 9]
#   neighbors(symbolId=100, k=10) = [2, 6, 8, 9]
#   neighbors(symbolId=7, k=5) = [0, 4]
#   neighbors(symbolId=9, k=5) = [0, 1, 2, 3, 4]
#   neighbors(symbolId=50, k=7) = [0, 1, 3, 4, 5, 6]
#   neighbors(symbolId=4, k=4) = [1, 2, 3]
#   neighbors(symbolId=8, k=4) = [0, 2, 3]
