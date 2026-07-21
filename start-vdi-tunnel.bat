@echo off
REM ---------------------------------------------------------------------------
REM Launch the VDI tunnel MCP stdio server (host side).
REM
REM Two ways to use it:
REM   1. as the command Claude Code spawns:
REM        claude mcp add vdi-tunnel -- "%~f0"
REM   2. by hand, to smoke-test the proxy (it will sit waiting on stdin):
REM        start-vdi-tunnel.bat
REM
REM stdio transport: stdout carries JSON-RPC and nothing else. Never echo to
REM stdout from this script - send diagnostics to stderr with >&2.
REM ---------------------------------------------------------------------------
setlocal

set "HOST_DIR=%~dp0host"

if not exist "%HOST_DIR%\vdi_tunnel\__main__.py" (
    echo [start-vdi-tunnel] no vdi_tunnel package under "%HOST_DIR%" - run this from the repo checkout>&2
    exit /b 1
)

REM Prefer a repo-local venv if one exists, else whatever python is on PATH.
set "PY=python"
if exist "%~dp0.venv\Scripts\python.exe" set "PY=%~dp0.venv\Scripts\python.exe"

set "PYTHONPATH=%HOST_DIR%"
REM Flush per write so stdio never deadlocks behind a block buffer.
set "PYTHONUNBUFFERED=1"
REM Replies from the VDI contain non-ASCII that the Windows cp1252 default
REM cannot encode; without this the proxy dies on 'charmap' codec errors.
set "PYTHONIOENCODING=utf-8"

cd /d "%HOST_DIR%"
"%PY%" -m vdi_tunnel %*
exit /b %ERRORLEVEL%
