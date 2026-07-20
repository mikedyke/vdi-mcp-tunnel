"""Host capture + calibration + QR decode. Windows.

Panel layout is one calibrated unit: 4 ArUco corner markers bound the bridge panel.
Detecting them yields a homography; the QR ROI and the textarea click-point are then
fixed fractional offsets within the rectified panel (set once, see PANEL_LAYOUT)."""
import numpy as np
import cv2
import mss
import zxingcpp

# Fractional offsets inside the rectified panel (0..1), measured live 2026-07-20
# against bridge v0.1.1 (canvas ~896x805 px in the VDI). The unit rectangle spans the
# 4 marker CENTRES; the textarea sits below the canvas, hence fy > 1.0 (the homography
# extrapolates). Downlink QR fractions shift if the tool window is resized (QR px capped
# at 420) — qr_roi is generous but must stay clear of heartbeat_roi so settle detection
# isn't confused by the other QR.
PANEL_LAYOUT = dict(
    qr_roi=(0.10, 0.02, 0.80, 0.68),        # x, y, w, h  (main downlink QR)
    heartbeat_roi=(0.74, 0.71, 0.25, 0.27), # small corner QR (measured 0.78..0.92 x, 0.76..0.91 y)
    textarea_point=(0.50, 1.11),            # click here to focus input (below bottom markers)
    focus_probe=(0.035, 0.656, 0.93, 0.05), # green/red bar; colour flips when focused
)

ARUCO_DICT = cv2.aruco.DICT_4X4_50

class Screen:
    def __init__(self):
        self._sct = mss.mss()

    def grab(self, region=None) -> np.ndarray:
        mon = self._sct.monitors[0] if region is None else \
            {"left": region[0], "top": region[1], "width": region[2], "height": region[3]}
        img = np.array(self._sct.grab(mon))       # BGRA
        return cv2.cvtColor(img, cv2.COLOR_BGRA2BGR)

class Calibration:
    def __init__(self, H, size):
        self.H, self.size = H, size  # homography screen->panel, rectified (w,h)

    def _px(self, fx, fy):
        # map a panel-fractional point back to screen coords via inverse homography
        w, h = self.size
        p = np.array([[fx*w, fy*h]], dtype=np.float32).reshape(-1,1,2)
        inv = np.linalg.inv(self.H)
        s = cv2.perspectiveTransform(p, inv)[0][0]
        return int(s[0]), int(s[1])

    def textarea_point(self):
        return self._px(*PANEL_LAYOUT["textarea_point"])

def find_panel(frame) -> Calibration | None:
    """Locate the 4 ArUco markers and build a homography to a rectified panel."""
    det = cv2.aruco.ArucoDetector(cv2.aruco.getPredefinedDictionary(ARUCO_DICT),
                                  cv2.aruco.DetectorParameters())
    corners, ids, _ = det.detectMarkers(frame)
    if ids is None or len(ids) < 4:
        return None
    # Expect ids 0..3 at TL,TR,BR,BL. Map their centres to a unit rectangle.
    want = {0: None, 1: None, 2: None, 3: None}
    for c, i in zip(corners, ids.flatten()):
        if int(i) in want:
            want[int(i)] = c.reshape(4, 2).mean(axis=0)
    if any(v is None for v in want.values()):
        return None
    W, H = 1000, 700
    src = np.float32([want[0], want[1], want[2], want[3]])
    dst = np.float32([[0, 0], [W, 0], [W, H], [0, H]])
    Hmat, _ = cv2.findHomography(src, dst)
    return Calibration(Hmat, (W, H))

def rectify(frame, calib) -> np.ndarray:
    return cv2.warpPerspective(frame, calib.H, calib.size)

def _roi(img, frac):
    h, w = img.shape[:2]
    x, y, rw, rh = frac
    return img[int(y*h):int((y+rh)*h), int(x*w):int((x+rw)*w)]

def decode_qr(img) -> list[bytes]:
    """Return raw byte payloads of all QR codes found (data QRs use BYTE mode).
    zxing-cpp, not pyzbar: the bundled Windows zbar DLL hard-crashes the process
    (access violation, no traceback) on exactly the bridge's binary QR frames."""
    return [bytes(r.bytes) for r in
            zxingcpp.read_barcodes(img, formats=zxingcpp.BarcodeFormat.QRCode)]

def read_downlink(img, calib):    return decode_qr(_roi(rectify(img, calib), PANEL_LAYOUT["qr_roi"]))
def read_heartbeat(img, calib):   return decode_qr(_roi(rectify(img, calib), PANEL_LAYOUT["heartbeat_roi"]))

def stable(a, b, thresh=1.0) -> bool:
    """True if two grabs of the same ROI are visually settled (defeats progressive display)."""
    if a is None or b is None or a.shape != b.shape:
        return False
    return float(np.mean(cv2.absdiff(a, b))) < thresh
