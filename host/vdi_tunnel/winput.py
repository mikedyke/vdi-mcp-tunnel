"""Windows input via SendInput (ctypes). Types characters and clicks — no global hotkeys.

Character typing uses KEYEVENTF_UNICODE so arbitrary text (base64 lines) lands verbatim
in the focused plain textarea regardless of keyboard layout."""
import ctypes, time
from ctypes import wintypes

user32 = ctypes.WinDLL("user32", use_last_error=True)

# Match physical pixels (as mss captures) so image coords == screen coords under HiDPI scaling.
try:
    ctypes.WinDLL("shcore").SetProcessDpiAwareness(2)   # PROCESS_PER_MONITOR_DPI_AWARE
except Exception:
    try: user32.SetProcessDPIAware()
    except Exception: pass

INPUT_KEYBOARD, INPUT_MOUSE = 1, 0
KEYEVENTF_UNICODE, KEYEVENTF_KEYUP = 0x0004, 0x0002
MOUSEEVENTF_MOVE, MOUSEEVENTF_ABSOLUTE = 0x0001, 0x8000
MOUSEEVENTF_VIRTUALDESK = 0x4000
MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP = 0x0002, 0x0004

# System-metric indices for the VIRTUAL desktop (spans all monitors; origin may be negative).
SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN = 76, 77
SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN = 78, 79

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
    """Click at (x, y) given in SCREEN-GRAB pixel coords (mss monitors[0] origin, i.e. the
    virtual-desktop top-left). Absolute mouse events are normalised to the whole virtual
    desktop with MOUSEEVENTF_VIRTUALDESK, so clicks reach secondary monitors and monitors
    at negative offsets (single-monitor primary-only mapping was the multi-screen bug)."""
    vw = user32.GetSystemMetrics(SM_CXVIRTUALSCREEN)
    vh = user32.GetSystemMetrics(SM_CYVIRTUALSCREEN)
    # x,y are already measured from the virtual-desktop origin (mss grabs from there),
    # so no offset subtraction is needed — just normalise to 0..65535 over the virtual size.
    ax = int(x * 65535 / max(vw - 1, 1)); ay = int(y * 65535 / max(vh - 1, 1))
    flags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK
    move = INPUT(type=INPUT_MOUSE)
    move.u.mi = MOUSEINPUT(ax, ay, 0, flags, 0, None)
    _send(move); time.sleep(0.02)
    for f in (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP):
        clk = INPUT(type=INPUT_MOUSE)
        clk.u.mi = MOUSEINPUT(ax, ay, 0, f | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, 0, None)
        _send(clk); time.sleep(0.01)
