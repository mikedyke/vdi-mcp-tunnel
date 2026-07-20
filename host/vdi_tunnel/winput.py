"""Windows input via SendInput (ctypes). Types characters and clicks — no global hotkeys.

Character typing uses KEYEVENTF_UNICODE so arbitrary text (base64 lines) lands verbatim
in the focused plain textarea regardless of keyboard layout."""
import ctypes, time
from ctypes import wintypes

user32 = ctypes.WinDLL("user32", use_last_error=True)

INPUT_KEYBOARD, INPUT_MOUSE = 1, 0
KEYEVENTF_UNICODE, KEYEVENTF_KEYUP = 0x0004, 0x0002
MOUSEEVENTF_MOVE, MOUSEEVENTF_ABSOLUTE = 0x0001, 0x8000
MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP = 0x0002, 0x0004

class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                ("dwExtraInfo", ctypes.POINTER(wintypes.ULONG))]
class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG),
                ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.POINTER(wintypes.ULONG))]
class _U(ctypes.Union):
    _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT)]
class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("u", _U)]

def _send(inp):
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))

def _key_unicode(ch, up=False):
    flags = KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if up else 0)
    inp = INPUT(type=INPUT_KEYBOARD)
    inp.u.ki = KEYBDINPUT(0, ord(ch), flags, 0, None)
    _send(inp)

def type_text(text: str, interval_ms: int = 12):
    for ch in text:
        if ch == "\n":
            _vk(0x0D)  # Enter
        else:
            _key_unicode(ch, False); _key_unicode(ch, True)
        time.sleep(interval_ms / 1000.0)

def _vk(vk, up=False):
    inp = INPUT(type=INPUT_KEYBOARD)
    inp.u.ki = KEYBDINPUT(vk, 0, KEYEVENTF_KEYUP if up else 0, 0, None)
    _send(inp)

def press_enter():
    _vk(0x0D); _vk(0x0D, True)

def click(x: int, y: int):
    sw = user32.GetSystemMetrics(0); sh = user32.GetSystemMetrics(1)
    ax = int(x * 65535 / max(sw - 1, 1)); ay = int(y * 65535 / max(sh - 1, 1))
    move = INPUT(type=INPUT_MOUSE)
    move.u.mi = MOUSEINPUT(ax, ay, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, 0, None)
    _send(move); time.sleep(0.02)
    for f in (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP):
        clk = INPUT(type=INPUT_MOUSE)
        clk.u.mi = MOUSEINPUT(0, 0, 0, f, 0, None)
        _send(clk); time.sleep(0.01)
