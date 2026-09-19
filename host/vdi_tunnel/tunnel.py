"""Ties uplink (ARQ typing) + downlink (QR capture/decode) into request()->response.

One request/response cycle:
  1. locate panel (ArUco) -> Calibration
  2. click textarea, verify focus glyph
  3. type REQ frames with stop-and-wait ARQ (heartbeat.rx_hash acks each chunk)
  4. read animated RESP QR frames until the LT decoder reconstructs; verify SHA-256
"""
import sys, time, itertools, random
from . import protocol as P
from . import vision as V
from . import winio
from .fountain import Decoder

class TunnelError(Exception): ...

class Tunnel:
    def __init__(self, cfg):
        self.cfg = cfg
        self.io = winio.make_backend(cfg)
        # Random start so a fresh session's first gen_id doesn't collide with a stale
        # response of the same gen_id still animating on the bridge from a prior session
        # (that mixes two replies' symbols -> SHA mismatch). 16-bit space, wraps in request().
        self._gen = itertools.count(random.randint(1, 0xFFFF))

    # ---------- calibration / heartbeat ----------
    def _calibrate(self):
        for _ in range(20):
            calib = V.find_panel(self.io.grab())
            if calib:
                return calib
            time.sleep(0.1)
        raise TunnelError("panel not found (bridge tool window visible? fiducials in view?)")

    def _heartbeat(self, calib):
        for raw in V.read_heartbeat(self.io.grab(), calib):
            try:
                return P.unpack_heartbeat(raw)
            except Exception:
                pass
        return None

    def health(self):
        calib = self._calibrate()
        hb = self._heartbeat(calib)
        return {"alive": hb is not None, **(hb or {})}

    # ---------- uplink ----------
    def _send_request(self, calib, gen_id, payload: bytes):
        codec, comp = P.compress(payload)
        fp = self.cfg.frame_payload
        chunks = [comp[i:i+fp] for i in range(0, len(comp), fp)] or [b""]
        total = len(chunks)
        self._focus_textarea(calib)
        self.io.clear_field()  # start each request from an empty textarea (multi-command safe)
        ack = 0
        for seq, chunk in enumerate(chunks):
            line = P.req_to_line(P.pack_req(gen_id, seq, total, codec, chunk))
            expected = P.rolling_ack(ack, chunk)
            for attempt in range(self.cfg.arq_max_retries):
                self.io.type_text(line + "\n")
                if self._await_ack(calib, expected):
                    ack = expected; break
                print(f"[arq] gen={gen_id} seq={seq} retransmit (attempt {attempt+1})",
                      file=sys.stderr, flush=True)
                time.sleep(0.1 * (attempt + 1))
            else:
                raise TunnelError(f"uplink ARQ failed at chunk {seq}")
        self.io.type_text("END\n")

    def _focus_textarea(self, calib):
        """Click the textarea and confirm the panel's focus bar goes green before typing.
        Retries the click a few times; raises if the panel never reports focus so we never
        type keystrokes into the void (or the wrong window).
        Skips the click when the textarea is already focused: every click (even a posted one)
        makes the Citrix client yank the host's real cursor into its window."""
        if V.is_focused(self.io.grab(), calib):
            return
        for attempt in range(5):
            self.io.focus_click(*calib.textarea_point())
            time.sleep(0.15)
            focused = V.is_focused(self.io.grab(), calib)
            if focused:
                return
            time.sleep(0.1 * (attempt + 1))
        raise TunnelError("textarea never reported focus (bridge panel visible & active?)")

    def _await_ack(self, calib, expected):
        deadline = time.time() + self.cfg.ack_timeout_ms / 1000.0
        while time.time() < deadline:
            hb = self._heartbeat(calib)
            if hb and hb["rx_hash"] == expected:
                return True
            time.sleep(0.05)
        return False

    # ---------- downlink ----------
    def _read_response(self, calib, gen_id) -> bytes:
        """Read the LT-coded RESP QR animation. The bridge cycles a fixed set of QR
        frames; we grab as fast as we can, decode every QR in the whole panel, and feed
        each distinct symbol to the fountain decoder until it reconstructs. No stable()
        gating — the QR is a crisp rendered image, and gating fought the animation."""
        dec = None; meta = None
        seen: set[int] = set()
        state = None        # last bridge state seen, from the heartbeat QR in the same grab
        deadline = time.time() + self.cfg.downlink_timeout_s
        last_log = time.time()
        while time.time() < deadline:
            for raw in V.read_all(self.io.grab(), calib):
                try:
                    f = P.unpack_resp(raw)
                except Exception:
                    try:
                        state = P.unpack_heartbeat(raw)["state"]
                    except Exception:
                        pass
                    continue
                if f["type"] != P.TYPE_RESP or f["gen_id"] != gen_id:
                    continue        # heartbeat QR or a stale generation
                if dec is None:
                    meta = f
                    # Symbol size comes from the frame, not from config: the bridge pads
                    # every symbol to its own symbolSize, so len() is authoritative. This
                    # decouples the two sides -- a bridge that raises its symbol size (e.g.
                    # for denser QR) keeps working against an unchanged host instead of
                    # silently decoding garbage on a constant mismatch.
                    dec = Decoder(f["k"], f["payload_len"], len(f["symbol"]), self.cfg.dmax)
                if f["symbol_id"] in seen:
                    continue
                seen.add(f["symbol_id"])
                if dec.add(f["symbol_id"], f["symbol"]):
                    payload = dec.result()
                    if P.sha_tail(payload) != meta["sha"]:
                        # symbols from two same-gen responses got mixed (unlikely now that
                        # gen is randomised); discard and keep reading fresh frames
                        print(f"[downlink] gen={gen_id} SHA mismatch, resetting decoder",
                              file=sys.stderr, flush=True)
                        dec = None; meta = None; seen.clear()
                        continue
                    return P.decompress(meta["codec"], payload)
            if dec is not None and time.time() - last_log > 3:
                last_log = time.time()
                # progress breadcrumb for large replies (K symbols needed)
                print(f"[downlink] gen={gen_id} k={meta['k']} symbols={len(seen)}",
                      file=sys.stderr, flush=True)
            time.sleep(0.03)
        need = meta["k"] if meta else "?"
        where = P.STATE_NAMES.get(state, "unseen" if state is None else str(state))
        hint = ""
        if where in ("FORWARDING", "RECEIVING"):
            # the bridge never got a reply out of the IDE — usually the IDE is blocked on a
            # confirmation dialog (terminal commands need "Brave Mode" off to prompt)
            hint = "; bridge still waiting on the IDE - check the VDI for a modal dialog"
        elif where == "unseen":
            hint = "; no heartbeat either - panel occluded or not visible"
        raise TunnelError(
            f"downlink timeout ({len(seen)} symbols seen, need k={need}, bridge={where}){hint}")

    # ---------- public ----------
    def request(self, mcp_json: bytes) -> bytes:
        """Send a serialized MCP JSON-RPC message, return the serialized reply."""
        calib = self._calibrate()
        gen_id = next(self._gen) & 0xFFFF
        self._send_request(calib, gen_id, mcp_json)
        return self._read_response(calib, gen_id)
