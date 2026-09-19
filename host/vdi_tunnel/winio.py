"""Capture + input backends for the host<->panel channel.

Two interchangeable backends implement the same small interface used by tunnel.py:
    grab() -> BGR np.ndarray            # a frame containing the bridge panel
    focus_click(gx, gy)                 # click at grab-image coords to focus the textarea
    type_text(text)                     # type chars into the focused textarea
    clear_field()                       # select-all + delete, so each request starts empty

LegacyBackend  — mss full-desktop grab + SendInput (global; needs the panel visible AND the
                 window foreground/active while typing). The original behaviour.
BgBackend      — PrintWindow(PW_RENDERFULLCONTENT) of the Citrix session window + WM_CHAR/
                 mouse messages posted to the ICA display child. Reads the panel even when
                 occluded, and drives input WITHOUT foregrounding or moving the real cursor.

Why the ICA child: a Citrix HDX session is one opaque bitmap to the host. The ICA client
keeps a client-side framebuffer that renders on demand (PrintWindow) and a display child
('CtxICADisp') whose WndProc processes posted window messages, translating them into ICA
session input — so both capture and input work with the window in the background. Empirically
(2026-09-04) posted mouse messages focus the textarea and SendMessage(WM_CHAR) enters text
into it with the window neither foreground nor cursor-warped.
"""
import ctypes
import time
from ctypes import wintypes
import numpy as np
import cv2
from . import vision as V
from . import winput as W

user32 = ctypes.WinDLL("user32", use_last_error=True)
gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)

# --- messages / flags ---
WM_MOUSEMOVE, WM_LBUTTONDOWN, WM_LBUTTONUP = 0x0200, 0x0201, 0x0202
WM_KEYDOWN, WM_KEYUP, WM_CHAR = 0x0100, 0x0101, 0x0102
MK_LBUTTON = 0x0001
PW_RENDERFULLCONTENT = 0x00000002
SMTO_ABORTIFHUNG = 0x0002
VK_CONTROL, VK_A, VK_BACK, VK_RETURN = 0x11, 0x41, 0x08, 0x0D
ICA_DISPLAY_CLASS = "CtxICADisp"

_EnumProc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)


def _class_of(hwnd) -> str:
    b = ctypes.create_unicode_buffer(256)
    user32.GetClassNameW(hwnd, b, 256)
    return b.value


def _title_of(hwnd) -> str:
    n = user32.GetWindowTextLengthW(hwnd)
    b = ctypes.create_unicode_buffer(n + 1)
    user32.GetWindowTextW(hwnd, b, n + 1)
    return b.value


def _find_child(hwnd, cls):
    found = []

    def cb(h, _):
        if _class_of(h) == cls:
            found.append(h)
        return True

    user32.EnumChildWindows(hwnd, _EnumProc(cb), 0)
    return found[0] if found else None


def find_ica_window(title_substr: str = ""):
    """Return (top_hwnd, ica_display_child_hwnd) for a Citrix session window, or None.

    A session window is any visible top-level that has a 'CtxICADisp' descendant. If
    `title_substr` is given, only windows whose title contains it are considered (use it
    when several sessions are open)."""
    hits = []

    def cb(h, _):
        if not user32.IsWindowVisible(h):
            return True
        if title_substr and title_substr.lower() not in _title_of(h).lower():
            return True
        child = _find_child(h, ICA_DISPLAY_CLASS)
        if child:
            hits.append((h, child))
        return True

    user32.EnumWindows(_EnumProc(cb), 0)
    return hits[0] if hits else None


class _BIH(ctypes.Structure):
    _fields_ = [("biSize", wintypes.DWORD), ("biWidth", wintypes.LONG),
                ("biHeight", wintypes.LONG), ("biPlanes", wintypes.WORD),
                ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD),
                ("biSizeImage", wintypes.DWORD), ("biXPelsPerMeter", wintypes.LONG),
                ("biYPelsPerMeter", wintypes.LONG), ("biClrUsed", wintypes.DWORD),
                ("biClrImportant", wintypes.DWORD)]


def print_window(hwnd) -> np.ndarray | None:
    """Capture a window's own rendering via PrintWindow(PW_RENDERFULLCONTENT). Works when the
    window is occluded or in the background; returns None if it's minimised (no client bitmap)."""
    if user32.IsIconic(hwnd):
        return None
    r = wintypes.RECT()
    if not user32.GetWindowRect(hwnd, ctypes.byref(r)):
        return None
    w, h = r.right - r.left, r.bottom - r.top
    if w <= 0 or h <= 0:
        return None
    hdc = user32.GetWindowDC(hwnd)
    memdc = gdi32.CreateCompatibleDC(hdc)
    bmp = gdi32.CreateCompatibleBitmap(hdc, w, h)
    gdi32.SelectObject(memdc, bmp)
    try:
        user32.PrintWindow(hwnd, memdc, PW_RENDERFULLCONTENT)
        bi = _BIH()
        bi.biSize = ctypes.sizeof(_BIH)
        bi.biWidth, bi.biHeight = w, -h            # negative -> top-down rows
        bi.biPlanes, bi.biBitCount, bi.biCompression = 1, 32, 0
        buf = (ctypes.c_char * (w * h * 4))()
        gdi32.GetDIBits(memdc, bmp, 0, h, buf, ctypes.byref(bi), 0)
        return np.frombuffer(buf, np.uint8).reshape(h, w, 4)[:, :, :3].copy()
    finally:
        gdi32.DeleteObject(bmp)
        gdi32.DeleteDC(memdc)
        user32.ReleaseDC(hwnd, hdc)


def _win_origin(hwnd):
    r = wintypes.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(r))
    return r.left, r.top


def _screen_to_client(hwnd, sx, sy):
    p = wintypes.POINT(sx, sy)
    user32.ScreenToClient(hwnd, ctypes.byref(p))
    return p.x, p.y


def _lp_xy(x, y):
    return (y << 16) | (x & 0xFFFF)


def _sc(vk):
    return user32.MapVirtualKeyW(vk, 0)  # MAPVK_VK_TO_VSC


# ---------------------------------------------------------------------------
class LegacyBackend:
    """mss capture + SendInput (original behaviour)."""

    def __init__(self, cfg):
        self.cfg = cfg
        self.screen = V.Screen()

    def grab(self):
        return self.screen.grab()

    def focus_click(self, gx, gy):
        W.click(gx, gy)

    def type_text(self, text):
        W.type_text(text, self.cfg.key_interval_ms)

    def clear_field(self):
        W.clear_field()


class BgBackend:
    """PrintWindow capture + posted messages to the ICA display child (background, no focus theft)."""

    def __init__(self, cfg):
        self.cfg = cfg
        win = find_ica_window(getattr(cfg, "ica_window_title_substr", "") or "")
        if not win:
            raise RuntimeError(
                "no Citrix session window found (a visible window with a 'CtxICADisp' child). "
                "Is the VDI session open and not minimised?")
        self.top, self.ctx = win

    # --- capture ---
    def grab(self):
        img = print_window(self.top)
        if img is None:
            raise RuntimeError(
                "PrintWindow returned no frame - the Citrix session window is minimised; "
                "restore it (it may stay in the background/occluded, just not minimised).")
        return img

    # --- input ---
    def _key(self, vk, up):
        # SENT, not posted, like the WM_CHARs in type_text: Windows delivers sent messages
        # ahead of anything still sitting in the posted queue, so posted keys could be
        # overtaken by the chars typed after them. Suspected 2026-09-19: a posted Ctrl+A/Backspace
        # landing after the first typed chars would erase them (fits an ARQ retransmit of seq 0); a
        # posted Enter can likewise land after the next line's chars. All-sent keeps order.
        sc = _sc(vk)
        msg = WM_KEYUP if up else WM_KEYDOWN
        lp = (sc << 16) | (0xC0000001 if up else 1)
        res = ctypes.c_ulong(0)
        user32.SendMessageTimeoutW(self.ctx, msg, vk, lp, SMTO_ABORTIFHUNG, 1000, ctypes.byref(res))

    def focus_click(self, gx, gy):
        # grab-image coords -> screen -> ICA display client coords
        ox, oy = _win_origin(self.top)
        cx, cy = _screen_to_client(self.ctx, ox + int(gx), oy + int(gy))
        lp = _lp_xy(cx, cy)
        # The ICA client reacts to WM_LBUTTONDOWN by clamping the host's REAL cursor into the
        # session window (measured 2026-09-19: (3893,1029) -> (2262,1029), foreground or not;
        # MOVE and UP alone don't). It does so asynchronously, a few ms after the message is
        # handled, sometimes with a brief ClipCursor and a second nudge ~60 ms later -- so an
        # immediate restore loses the race. Guard for a short window instead and put the cursor
        # back whenever it was yanked into the window from outside it.
        saved = wintypes.POINT()
        user32.GetCursorPos(ctypes.byref(saved))
        user32.PostMessageW(self.ctx, WM_MOUSEMOVE, 0, lp)
        user32.PostMessageW(self.ctx, WM_LBUTTONDOWN, MK_LBUTTON, lp)
        user32.PostMessageW(self.ctx, WM_LBUTTONUP, 0, lp)
        self._guard_cursor(saved.x, saved.y)

    def _guard_cursor(self, sx, sy, dur=0.3):
        r = wintypes.RECT()
        user32.GetWindowRect(self.top, ctypes.byref(r))
        inside = lambda x, y: r.left <= x < r.right and r.top <= y < r.bottom
        if inside(sx, sy):
            return          # cursor already over the session window: the clamp is a no-op
        p = wintypes.POINT()
        end = time.perf_counter() + dur
        while time.perf_counter() < end:
            user32.GetCursorPos(ctypes.byref(p))
            if (p.x, p.y) != (sx, sy) and inside(p.x, p.y):
                user32.ClipCursor(None)      # drop a transient clip, or SetCursorPos is clamped
                user32.SetCursorPos(sx, sy)
            time.sleep(0.002)

    def type_text(self, text):
        gap = self.cfg.key_interval_ms / 1000.0
        res = ctypes.c_ulong(0)
        for ch in text:
            if ch == "\n":
                # Enter as a real key event (raw), so the Swing textarea inserts a newline
                self._key(VK_RETURN, False)
                self._key(VK_RETURN, True)
            else:
                # WM_CHAR carries the Unicode codepoint directly -> layout-independent, the
                # analogue of the legacy KEYEVENTF_UNICODE path. lParam low word is the key
                # repeat count: it MUST be >=1, or the ICA client's WM_CHAR handler treats it
                # as "zero repeats" and drops the character. SendMessageTimeout so a busy ICA
                # thread can never wedge the proxy.
                user32.SendMessageTimeoutW(self.ctx, WM_CHAR, ord(ch), 1,
                                           SMTO_ABORTIFHUNG, 1000, ctypes.byref(res))
            time.sleep(gap)

    def clear_field(self):
        # Ctrl+A then Backspace (delete selection) -- mirrors winput.clear_field, via raw keys.
        # Small settling gaps so the ICA client tracks the modifier state across the separate
        # key events (Ctrl must still be down when A is pressed).
        self._key(VK_CONTROL, False); time.sleep(0.02)
        self._key(VK_A, False); time.sleep(0.02)
        self._key(VK_A, True); time.sleep(0.02)
        self._key(VK_CONTROL, True)
        time.sleep(0.05)
        self._key(VK_BACK, False)
        self._key(VK_BACK, True)
        time.sleep(0.05)


def make_backend(cfg):
    if getattr(cfg, "background_mode", False):
        return BgBackend(cfg)
    return LegacyBackend(cfg)
