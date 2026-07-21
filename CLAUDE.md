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

Any change to framing, the fountain PRNG (`GOLDEN`, `lcg`, `seed`, high-bits draw), or the compression pipeline must be made in **both** languages and must still satisfy the golden parity vectors in `vectors/parity-vectors.txt` (e.g. `neighbors(symbolId=10, k=10) = [1, 5, 6]`).

Both sides are tested against that one file — never against hand-copied constants:
- `python -m unittest discover -s host/tests` — Python side, stdlib only.
- `cd codec-parity && gradle test` — Kotlin side. Compiles `Protocol.kt`/`Fountain.kt` straight out of the plugin module via a `srcDir` filter, so it needs **no IDE SDK** and runs in seconds. (If either file ever grows an IntelliJ import this build breaks — that is the intended alarm; the mirror layer stays plain Kotlin.)
- CI runs both in a separate `parity` job on every push/PR.

The vectors are generated from the Python implementation (`python host/tools/gen_parity_vectors.py`) and CI fails if regenerating them produces a diff. So a genuine protocol change = edit both languages → regenerate → update `PROTOCOL.md`. A vector diff without a matching protocol change means one side drifted.

## Build & run

**Host (Windows, Python):** no build step.
- `pip install -r host/requirements.txt` (mss, opencv-contrib-python for `cv2.aruco`, numpy, zxing-cpp — NOT pyzbar, whose bundled Windows zbar DLL hard-crashes on the bridge's binary QR frames)
- Run from `host/`: `python -m vdi_tunnel` (stdio MCP server; registered via `claude mcp add vdi-tunnel -- python -m vdi_tunnel`)
- Tests cover the codec layer only (`host/tests/`, stdlib `unittest`); the capture/typing layers are untested. No linter config.

**Bridge (Kotlin IntelliJ plugin):**
- `cd bridge-plugin && gradle buildPlugin` → `build/distributions/vdi-tunnel-bridge-*.zip` (JDK 21, Gradle 9.0+ — required by IntelliJ Platform Gradle Plugin 2.18+; CI uses 9.6.1). First local build downloads the ~1 GB IDE SDK — prefer CI.
- CI (`.github/workflows/build.yml`) builds on every push/PR and uploads the zip as an artifact; pushing a tag `vX.Y.Z` publishes a GitHub Release with the zip. The `parity` job runs the codec tests without the SDK.
- `bridge-plugin/gradle.properties` → `platformVersion` must be pinned to the VDI IDE's exact build; `pluginVersion` is injected into plugin.xml.

## Architecture

**Host request/response cycle** (`tunnel.py` orchestrates, one cycle per MCP message):
1. `vision.find_panel` detects 4 ArUco corner markers (DICT_4X4_50, ids 0–3 at TL,TR,BR,BL) bounding the plugin panel → homography. All click points and QR ROIs are fractional offsets inside the rectified panel, defined once in `vision.PANEL_LAYOUT`.
2. Uplink: request is zlib-compressed, chunked, each chunk typed as a base64 line (`winput.py` SendInput) with stop-and-wait ARQ — the bridge acks by exposing a rolling CRC32 (`rx_hash`) in its heartbeat QR; `END` line closes the message.
3. Downlink: bridge LT-encodes the reply into a cycling set of QR frames; host waits for the ROI to visually settle (`vision.stable`), decodes QRs via zxing-cpp, feeds symbols to the peeling `fountain.Decoder` until reconstruction, then verifies SHA tail.

**Host MCP layer** (`proxy.py`): hand-rolled newline-delimited JSON-RPC stdio loop. `initialize` is answered locally; `tools/list` is cached to disk (`tools_cache.json`) keyed by the bridge heartbeat's `schema_hash` so the large schema crosses the QR channel only once; everything else forwards through the tunnel verbatim.

**Bridge** (`TunnelController.kt` state machine: IDLE→RECEIVING→FORWARDING→SENDING/ERROR): reassembles typed REQ lines, forwards the JSON to the IDE's local MCP server (`McpLocalClient`, plain HTTP POST skeleton), LT-encodes the reply and hands QR frames + a heartbeat QR to the Swing panel (`TunnelPanel`), which cycles them at paint ticks. IDE endpoint comes from `-Dvdi.ide.mcp.url=…`.

## VDI-tunnel routing mode

If `.claude/vdi-tunnel.active` exists, VDI-tunnel routing mode is ON: route all
IntelliJ/VDI project operations (build, run, lint, analyze, project structure, file ops)
through the `mcp__vdi-tunnel__*` tools, passing the `projectPath` recorded in that file,
instead of local Bash/Read/Write/Edit (the VDI project is not on this host). The marker is
managed by the `/vdi-tunnel-on` and `/vdi-tunnel-off` skills. Local tools still apply to
this tunnel repo itself (`host/`, `bridge-plugin/`, docs).

## Current state

Framing/codec/fountain, ARQ + QR orchestration, capture/decode, proxy, and the bridge tool
window are implemented and exercised end-to-end against a live VDI: real MCP tool calls
(`read_file`, `search_file`, `git_status`, `apply_patch`, …) round-trip through the
QR/keyboard channel. ArUco markers ship since plugin v0.1.1 (v0.1.7 installed);
`vision.PANEL_LAYOUT` was measured against the live panel (2026-07-20); `McpLocalClient`'s
streamable-HTTP handshake to the IDE works; focus-glyph verification before typing is in
place (`tunnel._focus_textarea` + `vision.is_focused`). Remaining gaps:
- `tools/list` disk cache never staleness-invalidates: the heartbeat's `schema_hash` is always 0, so after an IDE/plugin toolset change `host/tools_cache.json` must be deleted by hand (`proxy.py:_tools`).
- `execute_terminal_command` never returns while the IDE prompts per command — enable "Brave Mode" in the VDI's IDE or terminal-driven work is blocked.
- Slow IDE operations can outlast `downlink_timeout_s` (120s). The timeout now names the bridge state (`bridge=FORWARDING` = IDE still busy, `unseen` = panel occluded). **A failed call may still have applied** — read state back before retrying a mutation.
