from dataclasses import dataclass

@dataclass
class Config:
    # --- IDE MCP endpoint is reached by the BRIDGE (inside the VDI), not the host. ---
    # --- Transport / codec ---
    symbol_size: int = 150          # source-block size = repair-symbol size (bytes)
    frame_payload: int = 200        # max compressed bytes per uplink chunk
    dmax: int = 8                   # max LT repair degree
    # --- QR / capture ---
    qr_version: int = 12
    qr_ecc: str = "Q"
    module_min_px: int = 3
    downlink_fps: int = 3
    downlink_timeout_s: int = 120   # large replies span many cycled QR frames
    settle_stable_frames: int = 2   # identical grabs before decoding
    # --- Heartbeat / ARQ ---
    heartbeat_timeout_ms: int = 3000
    arq_max_retries: int = 5
    key_interval_ms: int = 12       # inter-key delay; tune to session lag
    ack_timeout_ms: int = 1500
    # --- MCP proxy ---
    tools_cache_path: str = "tools_cache.json"

    MAGIC_DOWN = 0x5644
    MAGIC_UP = 0x4344
    VERSION = 1
