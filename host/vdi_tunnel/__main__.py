import sys

from .config import Config
from .proxy import Proxy

if __name__ == "__main__":
    # MCP stdio is UTF-8, but Windows defaults these streams to cp1252, which
    # blows up on non-ASCII tunnel payloads (tree glyphs, umlauts).
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    Proxy(Config()).serve()
