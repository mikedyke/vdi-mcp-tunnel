# VDI MCP Tunnel — bridge (Kotlin plugin) + host (Python)

Tunnels MCP over a keyboard/QR channel so Claude Code on a Windows host can drive
IntelliJ's built-in MCP server inside an isolated VDI. See `PROTOCOL.md` for the wire
format and `intellij-tunnel-spec.md` (handover spec) for the full design.

```
Claude Code ──stdio──► host/ (Python proxy) ──keystrokes/QR──► bridge-plugin/ (Kotlin) ──localhost──► IntelliJ MCP server
```

## Fountain codec note
Cross-language RaptorQ interop (JVM encoder ↔ Python decoder) is fragile, so this scaffold
ships a **self-contained LT fountain** implemented identically in `host/vdi_tunnel/fountain.py`
and `bridge-plugin/.../Fountain.kt` (systematic symbols + seeded repair, deterministic LCG).
`PROTOCOL.md` carries golden parity vectors — wire them as a bridge unit test before trusting
the channel. Swap in a shared RaptorQ pair later if you confirm one; the frame layout allows it.

## Build & run
**Bridge (in the VDI):**
1. Ensure IntelliJ 2025.2+ MCP Server plugin is enabled and **Brave Mode is ON**.
2. Pin `bridge-plugin/gradle.properties` → `platformVersion` to the VDI's exact build (Help > About).
3. Generate the 4 ArUco corner markers into plugin resources (host expects DICT_4X4_50, ids 0–3):
   ```python
   import cv2
   d = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
   for i in range(4):
       cv2.imwrite(f"bridge-plugin/src/main/resources/markers/aruco_{i}.png",
                   cv2.aruco.generateImageMarker(d, i, 200))
   ```
4. Get the plugin zip: **CI builds it for you** (see below) — download from the latest GitHub
   Release, or from the workflow run's artifacts. To build locally instead:
   `cd bridge-plugin && gradle buildPlugin` → `build/distributions/vdi-tunnel-bridge-*.zip`.
5. In the VDI IDE: Settings > Plugins > gear ⚙ > *Install Plugin from Disk…* > pick the zip > restart.
6. Open the **VDI Tunnel** tool window; set the IDE endpoint via `-Dvdi.ide.mcp.url=…` (open question #1).

### CI (GitHub Actions)
`.github/workflows/build.yml` builds the plugin on every push/PR and uploads the zip as a
workflow **artifact**. Push a tag `vX.Y.Z` to also publish a **Release** with the zip attached
(the CI runner downloads the ~1 GB IDE SDK, not you). The VDI install is then just: download the
release zip through your file-ingress → Install Plugin from Disk. Keep the repo **private** (or on
internal GitHub Enterprise) — this is an in-house DLP-boundary tool.

**Host (Windows):**
1. `pip install -r host/requirements.txt` (pyzbar bundles zbar on Windows).
2. `claude mcp add vdi-tunnel -- python -m vdi_tunnel` (run from `host/`).

## What's implemented vs TODO
Implemented (real logic): framing + zlib codec + CRC (both langs), LT fountain encode/decode
(both langs, tested on the Python side incl. a dropped-frame case), host ARQ uplink + QR
downlink orchestration, SendInput driver, mss+ArUco+pyzbar capture/decode, MCP stdio proxy
with tools/list disk cache, bridge tool window + state machine + ZXing QR render.

TODO before end-to-end:
- **Tune `host/vdi_tunnel/vision.PANEL_LAYOUT`** fractional offsets to the real plugin panel.
- **Add real ArUco markers** (step 2 above) — placeholders won't be detected.
- **Confirm the IDE MCP endpoint** (SSE vs HTTP-stream + port) and finish `McpLocalClient.call`.
- **Focus-glyph verification** in `tunnel._send_request` before typing (probe `PANEL_LAYOUT.focus_probe`).
- **Bridge parity unit test** against `PROTOCOL.md` vectors.
- Validate over a temporary TCP path first (spec Phase 0), then swap in the QR/keyboard channel.

## Layout
- `host/` — Python proxy + transport (see `host/README.md`)
- `bridge-plugin/` — IntelliJ Kotlin plugin (tool window bridge)
- `PROTOCOL.md` — shared wire format + parity vectors
