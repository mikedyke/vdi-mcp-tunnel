from dataclasses import dataclass

@dataclass
class Config:
    # --- IDE MCP endpoint is reached by the BRIDGE (inside the VDI), not the host. ---
    # --- Transport / codec ---
    symbol_size: int = 692          # source-block size = repair-symbol size (bytes).
                                    # Downlink no longer depends on this: the decoder uses
                                    # len(symbol) from the frame, so a bridge with a different
                                    # symbolSize still decodes. Kept as documentation of the
                                    # current bridge value (and for encode-side tooling).
    frame_payload: int = 200        # max compressed bytes per uplink chunk
    dmax: int = 8                   # max LT repair degree
    # --- QR / capture (host decode auto-detects version/ECC; these document the bridge) ---
    qr_version: int = 18            # V18 byte-mode holds a 692B symbol + 24B header at ECC L
    qr_ecc: str = "L"               # raised from M 2026-09-11: +35% payload, same geometry
    module_min_px: int = 3
    downlink_fps: int = 6           # documents the bridge Timer(1000/6); the read loop grabs
                                    # as fast as it can rather than pacing off this
    downlink_timeout_s: int = 120   # large replies span many cycled QR frames
    settle_stable_frames: int = 2   # identical grabs before decoding
    # --- Heartbeat / ARQ ---
    heartbeat_timeout_ms: int = 3000
    arq_max_retries: int = 5
    key_interval_ms: int = 12       # inter-key delay; tune to session lag
    ack_timeout_ms: int = 1500
    # --- Capture / input backend ---
    background_mode: bool = True     # True: PrintWindow capture + posted messages to the ICA
                                     #   display child (reads through occlusion, never steals
                                     #   focus/cursor). False: legacy mss grab + SendInput.
    ica_window_title_substr: str = ""  # optional filter to pick the right Citrix session window
                                     #   when several are open (matched against the title, case-
                                     #   insensitive). Empty = first window with a CtxICADisp child.
    # --- MCP proxy ---
    tools_cache_path: str = "tools_cache.json"

    MAGIC_DOWN = 0x5644
    MAGIC_UP = 0x4344
    VERSION = 1
