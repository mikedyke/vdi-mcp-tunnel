# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An in-house DLP-boundary tool (keep the repo private) that tunnels MCP into an isolated VDI where no network path exists. Claude Code on the Windows host talks stdio to a Python proxy; the proxy types requests into the VDI as keystrokes and reads responses back by screen-capturing animated QR codes rendered by an IntelliJ plugin inside the VDI:

```
Claude Code ──stdio──► host/ (Python proxy) ──keystrokes/QR──► bridge-plugin/ (Kotlin) ──localhost──► IntelliJ MCP server
```

`PROTOCOL.md` is the normative wire format. The full design lives in the handover spec `intellij-tunnel-spec.md` (referenced by README but not checked into this repo).

## Critical constraint: mirrored implementations

The codec layer is implemented **twice, identically**, and must stay byte-for-byte compatible:

| Python (host) | Kotlin (bridge) | Contents |
|---|---|---|
| `host/vdi_tunnel/protocol.py` | `bridge-plugin/.../Protocol.kt` | frame pack/unpack, zlib codec, CRC, rolling ACK |
| `host/vdi_tunnel/fountain.py` | `bridge-plugin/.../Fountain.kt` | LT fountain (systematic + seeded repair, deterministic LCG) |

Any change to framing, the fountain PRNG (`GOLDEN`, `lcg`, `seed`, high-bits draw), or the compression pipeline must be made in **both** languages and must still satisfy the golden parity vectors at the bottom of `PROTOCOL.md` (e.g. `neighbors(symbolId=10, k=10) = [1, 5, 6]`). A bridge-side unit test for those vectors is a standing TODO — do not trust the channel without it.

## Build & run

**Host (Windows, Python):** no build step.
- `pip install -r host/requirements.txt` (mss, opencv-contrib-python for `cv2.aruco`, numpy, pyzbar)
- Run from `host/`: `python -m vdi_tunnel` (stdio MCP server; registered via `claude mcp add vdi-tunnel -- python -m vdi_tunnel`)
- No test suite exists yet; there is no linter config.

**Bridge (Kotlin IntelliJ plugin):**
- `cd bridge-plugin && gradle buildPlugin` → `build/distributions/vdi-tunnel-bridge-*.zip` (JDK 21, Gradle 8.5+; CI uses 8.13). First local build downloads the ~1 GB IDE SDK — prefer CI.
- CI (`.github/workflows/build.yml`) builds on every push/PR and uploads the zip as an artifact; pushing a tag `vX.Y.Z` publishes a GitHub Release with the zip.
- `bridge-plugin/gradle.properties` → `platformVersion` must be pinned to the VDI IDE's exact build; `pluginVersion` is injected into plugin.xml.

## Architecture

**Host request/response cycle** (`tunnel.py` orchestrates, one cycle per MCP message):
1. `vision.find_panel` detects 4 ArUco corner markers (DICT_4X4_50, ids 0–3 at TL,TR,BR,BL) bounding the plugin panel → homography. All click points and QR ROIs are fractional offsets inside the rectified panel, defined once in `vision.PANEL_LAYOUT`.
2. Uplink: request is zlib-compressed, chunked, each chunk typed as a base64 line (`winput.py` SendInput) with stop-and-wait ARQ — the bridge acks by exposing a rolling CRC32 (`rx_hash`) in its heartbeat QR; `END` line closes the message.
3. Downlink: bridge LT-encodes the reply into a cycling set of QR frames; host waits for the ROI to visually settle (`vision.stable`), decodes QRs via pyzbar, feeds symbols to the peeling `fountain.Decoder` until reconstruction, then verifies SHA tail.

**Host MCP layer** (`proxy.py`): hand-rolled newline-delimited JSON-RPC stdio loop. `initialize` is answered locally; `tools/list` is cached to disk (`tools_cache.json`) keyed by the bridge heartbeat's `schema_hash` so the large schema crosses the QR channel only once; everything else forwards through the tunnel verbatim.

**Bridge** (`TunnelController.kt` state machine: IDLE→RECEIVING→FORWARDING→SENDING/ERROR): reassembles typed REQ lines, forwards the JSON to the IDE's local MCP server (`McpLocalClient`, plain HTTP POST skeleton), LT-encodes the reply and hands QR frames + a heartbeat QR to the Swing panel (`TunnelPanel`), which cycles them at paint ticks. IDE endpoint comes from `-Dvdi.ide.mcp.url=…`.

## Current state (scaffold — not yet end-to-end)

Framing/codec/fountain, ARQ + QR orchestration, capture/decode, proxy, and the bridge tool window are implemented. Known gaps before it runs for real (see README for the full list):
- `vision.PANEL_LAYOUT` fractional offsets are untuned placeholders.
- Real ArUco marker PNGs must be generated into `bridge-plugin/src/main/resources/markers/` (README has the snippet).
- IDE MCP transport unconfirmed (SSE vs HTTP-stream + port) — `McpLocalClient.call` is a skeleton.
- Focus-glyph verification before typing (`tunnel._send_request` TODO).
- Bridge parity unit test against `PROTOCOL.md` vectors.
- Spec Phase 0: validate over a temporary TCP path before trusting the QR/keyboard channel.
