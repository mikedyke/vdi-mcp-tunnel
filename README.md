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
6. Open the **VDI Tunnel** tool window. The IDE endpoint defaults to
   `http://127.0.0.1:64342/stream` (streamable HTTP); override via `-Dvdi.ide.mcp.url=…`
   in Help > Edit Custom VM Options if yours differs. Enable the MCP Server in
   Settings > Tools with Brave Mode ON.

### CI (GitHub Actions)
`.github/workflows/build.yml` builds the plugin on every push/PR and uploads the zip as a
workflow **artifact**. Push a tag `vX.Y.Z` to also publish a **Release** with the zip attached
(the CI runner downloads the ~1 GB IDE SDK, not you). The VDI install is then just: download the
release zip through your file-ingress → Install Plugin from Disk. Keep the repo **private** (or on
internal GitHub Enterprise) — this is an in-house DLP-boundary tool.

**Host (Windows):**
1. `setup-venv.bat` — creates `.venv\` and installs `host/requirements.txt`, then verifies the
   imports that matter (`cv2.aruco` needs opencv-**contrib**; QR decode must be zxing-cpp).
   `--force` rebuilds from scratch. Or install the requirements yourself with pip.
2. `claude mcp add vdi-tunnel -- "<repo>\start-vdi-tunnel.bat"` — the launcher sets
   `PYTHONPATH`, forces UTF-8 stdio and unbuffered output, and prefers `.venv\` if present.

## What's implemented vs TODO
Implemented (real logic): framing + zlib codec + CRC (both langs), LT fountain encode/decode
(both langs, tested on the Python side incl. a dropped-frame case), host ARQ uplink + QR
downlink orchestration, SendInput driver, mss+ArUco+zxing-cpp capture/decode, MCP stdio proxy
with tools/list disk cache, bridge tool window + state machine + ZXing QR render.

Working end-to-end against a live VDI: `PANEL_LAYOUT` is tuned to the real panel, real ArUco
markers ship in the plugin, the bridge's streamable-HTTP handshake is exercised against the
IDE, focus-glyph verification runs before typing, and real MCP tool calls round-trip.

## Tests
The codec layer is mirrored in Python and Kotlin and must stay byte-compatible, so both
sides are checked against one generated vector file, `vectors/parity-vectors.txt`:

```
python -m unittest discover -s host/tests   # host side (stdlib only)
cd codec-parity && gradle test              # bridge side (no IDE SDK, seconds)
```

`codec-parity/` is a standalone build that compiles `Protocol.kt` and `Fountain.kt` out of
the plugin module and nothing else. Regenerate the vectors with
`python host/tools/gen_parity_vectors.py` — only ever as part of a deliberate protocol
change, and update `PROTOCOL.md` with it. CI runs all of this in the `parity` job.

Nothing above the codec is tested: capture, typing and the panel geometry are only ever
exercised against a live VDI.

TODO:
- **`tools/list` cache never invalidates** — the heartbeat's `schema_hash` is always 0, so
  after an IDE/plugin toolset change you must delete `host/tools_cache.json` by hand.
- **Terminal tools need "Brave Mode"** enabled in the VDI's IDE; without it
  `execute_terminal_command` blocks on a confirmation dialog nobody is there to click.

## Layout
- `host/` — Python proxy + transport (see `host/README.md`)
- `bridge-plugin/` — IntelliJ Kotlin plugin (tool window bridge)
- `codec-parity/` — SDK-free Gradle build running the bridge half of the parity test
- `vectors/` — generated cross-language parity vectors (both test suites read this)
- `PROTOCOL.md` — shared wire format + parity vectors
