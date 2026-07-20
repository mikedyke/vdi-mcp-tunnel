# host — VDI MCP tunnel (Windows)

Python MCP stdio proxy + transport. Register with Claude Code:

    claude mcp add vdi-tunnel -- python -m vdi_tunnel

(run from this `host/` dir, or install the package). See top-level README for the big picture.

Layout:
- `config.py`   defaults (§14 of the spec)
- `protocol.py` frame pack/unpack + zlib codec + CRC   (mirror of Bridge Protocol.kt)
- `fountain.py` LT decoder                              (mirror of Bridge Fountain.kt)
- `vision.py`   mss capture, ArUco calibration, QR decode, settle detection
- `winput.py`   SendInput keyboard/mouse
- `tunnel.py`   ARQ uplink + QR downlink -> request()/response
- `proxy.py`    MCP stdio server, tools/list disk cache
- `__main__.py` entrypoint

TODO before it runs for real: tune `vision.PANEL_LAYOUT` offsets to the actual plugin
panel, add focus-glyph verification in `tunnel._send_request`, and confirm ArUco ids 0..3
match the bridge's corner markers.
