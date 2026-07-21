@echo off
REM ---------------------------------------------------------------------------
REM Create the host-side Python venv and install the tunnel's dependencies.
REM
REM   setup-venv.bat            create .venv if missing, then install/refresh deps
REM   setup-venv.bat --force    delete .venv first, then rebuild it from scratch
REM
REM Creates .venv\ in the repo root, which start-vdi-tunnel.bat picks up
REM automatically in preference to whatever python is on PATH.
REM ---------------------------------------------------------------------------
setlocal

set "VENV=%~dp0.venv"
set "REQ=%~dp0host\requirements.txt"
set "VPY=%VENV%\Scripts\python.exe"

if not exist "%REQ%" (
    echo [setup-venv] no requirements.txt at "%REQ%" - run this from the repo checkout>&2
    exit /b 1
)

REM Prefer `python` on PATH: that is the interpreter the tunnel is known to work
REM on. `py -3` can pick a different (even 32-bit) install, and screen capture
REM over a large multi-monitor desktop wants the 64-bit one.
set "BOOT=python"
python --version >nul 2>&1 || set "BOOT=py -3-64"
%BOOT% --version >nul 2>&1 || set "BOOT=py -3"
%BOOT% --version >nul 2>&1 || (
    echo [setup-venv] no Python 3 found on PATH - install it first>&2
    exit /b 1
)

if /i "%~1"=="--force" (
    if exist "%VENV%" (
        echo [setup-venv] removing existing venv
        rmdir /s /q "%VENV%" || exit /b 1
    )
)

if exist "%VPY%" (
    echo [setup-venv] reusing venv at "%VENV%"
) else (
    echo [setup-venv] creating venv at "%VENV%"
    %BOOT% -m venv "%VENV%" || exit /b 1
)

echo [setup-venv] installing dependencies
"%VPY%" -m pip install --upgrade pip || exit /b 1
"%VPY%" -m pip install -r "%REQ%" || exit /b 1

REM Fail loudly here rather than mid-tunnel: cv2.aruco only ships in
REM opencv-contrib, and QR decoding must be zxing-cpp (pyzbar's bundled zbar
REM DLL access-violates on the bridge's binary QR frames).
echo [setup-venv] verifying imports
"%VPY%" -c "import mss, numpy, zxingcpp, cv2, platform; cv2.aruco.DICT_4X4_50; print('[setup-venv] ok - python ' + platform.python_version() + ' ' + platform.architecture()[0] + ', opencv ' + cv2.__version__)" || (
    echo [setup-venv] dependency check FAILED - see the traceback above>&2
    exit /b 1
)

echo [setup-venv] done. start-vdi-tunnel.bat will now use "%VENV%".
exit /b 0
