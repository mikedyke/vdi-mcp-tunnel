"""Minimal MCP stdio server that Claude Code connects to, tunnelling to the IDE via the bridge.

MCP stdio framing = newline-delimited JSON-RPC. We forward requests over the Tunnel;
`tools/list` is cached to disk (keyed by the bridge's schema_hash) so the large tool
schema only crosses the QR channel once. Swap this hand-rolled loop for the official
`mcp` SDK later if preferred."""
import sys, json, os, traceback
from .tunnel import Tunnel, TunnelError

class Proxy:
    def __init__(self, cfg):
        self.cfg = cfg
        self.tunnel = Tunnel(cfg)
        self._tools_cache = None
        self._load_cache()

    # ---- disk cache for tools/list ----
    def _cache_path(self):
        # absolute + stable regardless of the cwd Claude Code launches us from
        p = self.cfg.tools_cache_path
        if not os.path.isabs(p):
            p = os.path.join(os.path.dirname(os.path.dirname(__file__)), p)
        return p

    def _load_cache(self):
        try:
            with open(self._cache_path()) as f:
                self._tools_cache = json.load(f)
        except Exception:
            self._tools_cache = None

    def _save_cache(self, tools, schema_hash):
        with open(self._cache_path(), "w") as f:
            json.dump({"schema_hash": schema_hash, "tools": tools}, f)

    def _schema_current(self):
        hb = self.tunnel.health()
        return hb.get("schema_hash")

    # ---- stdio loop ----
    def serve(self):
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except Exception:
                continue
            try:
                self._handle(msg)
            except Exception as e:
                # never let one bad request kill the server; report it if it had an id
                traceback.print_exc(file=sys.stderr)
                if msg.get("id") is not None:
                    self._reply(msg.get("id"), error={"code": -32000, "message": str(e)})

    def _reply(self, id_, result=None, error=None):
        out = {"jsonrpc": "2.0", "id": id_}
        if error is not None:
            out["error"] = error
        else:
            out["result"] = result
        sys.stdout.write(json.dumps(out) + "\n"); sys.stdout.flush()

    def _handle(self, msg):
        method, id_ = msg.get("method"), msg.get("id")
        if method == "initialize":
            # Answer locally; capabilities advertise tools.
            self._reply(id_, {
                "protocolVersion": msg.get("params", {}).get("protocolVersion", "2025-06-18"),
                "serverInfo": {"name": "vdi-mcp-tunnel", "version": "0.1"},
                "capabilities": {"tools": {"listChanged": True}},
            })
        elif method == "notifications/initialized":
            pass  # notification, no reply
        elif method == "tools/list":
            self._reply(id_, {"tools": self._tools()})
        elif method == "tools/call":
            self._forward(msg)
        else:
            # forward anything else verbatim
            self._forward(msg)

    def _tools(self):
        # Serve the cache immediately if present — fast startup, and no screen access
        # (the bridge's schema_hash is currently always 0, so the cache never staleness-
        # invalidates; delete host/tools_cache.json to force a fresh pull).
        if self._tools_cache and self._tools_cache.get("tools"):
            return self._tools_cache["tools"]
        # No cache yet: pull once over the tunnel (large transfer, drives the screen).
        try:
            cur = self._schema_current()
        except Exception:
            cur = None
        req = json.dumps({"jsonrpc": "2.0", "id": "tl", "method": "tools/list"}).encode()
        resp = json.loads(self.tunnel.request(req))
        tools = resp.get("result", {}).get("tools", [])
        self._save_cache(tools, cur); self._tools_cache = {"schema_hash": cur, "tools": tools}
        return tools

    def _forward(self, msg):
        try:
            resp = self.tunnel.request(json.dumps(msg).encode())
            sys.stdout.write(resp.decode() + "\n"); sys.stdout.flush()
        except TunnelError as e:
            self._reply(msg.get("id"), error={"code": -32000, "message": str(e)})
