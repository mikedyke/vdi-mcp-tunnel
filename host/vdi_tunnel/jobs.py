"""Host-side long-running job helpers, exposed as synthetic MCP tools next to the IDE's own.

`run_tests_and_report` starts a test run in the IDE without waiting for it, then polls the
Gradle result XMLs with backoff and returns one digest (totals + failing tests). The caller
makes a single tool call instead of kicking a run off and then having to remember to look.

Completion heuristic (Gradle only -- the IDE's own JUnit runner writes no XML): result files
newer than the moment we started exist AND no 'Gradle Test Executor' JVM is alive. Every poll
is one `execute_terminal_command` round trip, which on the legacy backend briefly drives the
real keyboard, hence the backoff. All PowerShell goes through -EncodedCommand so quoting in the
IDE's process-mode launcher can't mangle it; no cmd.exe is involved.

I/O is injected: `call_tool(name, arguments) -> str` returns the tool's text content, so the
logic is testable without a tunnel."""
import base64, json, time

RUN_TESTS_TOOL = {
    "name": "run_tests_and_report",
    "description": (
        "Start a Gradle test run in the VDI IDE and wait for it to finish, then return a digest "
        "(totals and every failing test with a short message). Polls the result XMLs with "
        "backoff; one call, no manual follow-up. Give either configurationName or "
        "filePath+line (a run point from get_run_configurations). resultsDir is the Gradle "
        "test-results directory relative to the project root. Gradle runs only."),
    "inputSchema": {
        "type": "object",
        "properties": {
            "projectPath": {"type": "string", "description": "IDE project path."},
            "resultsDir": {"type": "string",
                           "description": "Gradle test-results dir relative to projectPath, "
                                          "e.g. main/<module>/build/test-results/test"},
            "configurationName": {"type": "string"},
            "filePath": {"type": "string"},
            "line": {"type": "integer"},
            "timeoutMinutes": {"type": "integer", "description": "Default 30."},
        },
        "required": ["projectPath", "resultsDir"],
    },
}
LOCAL_TOOLS = {RUN_TESTS_TOOL["name"]: RUN_TESTS_TOOL}

POLL_INTERVALS_S = (30, 45, 60, 90, 120)   # then stays at the last value


PS_PREFIX = "$ProgressPreference='SilentlyContinue';"   # keeps CLIXML progress noise off the output


def ps_command(script: str) -> str:
    enc = base64.b64encode((PS_PREFIX + script).encode("utf-16-le")).decode()
    return f"powershell -NoProfile -EncodedCommand {enc}"


def _q(s: str) -> str:
    """PowerShell single-quoted literal."""
    return "'" + s.replace("'", "''") + "'"


def _abs_dir(project_path: str, results_dir: str) -> str:
    return project_path.rstrip("/\\") + "/" + results_dir.strip("/\\")


NOW_SCRIPT = "(Get-Date).ToUniversalTime().ToString('o')"


def poll_script(directory: str, t0: str) -> str:
    return (
        f"$t0=[datetime]::Parse({_q(t0)},$null,'RoundtripKind');"
        f"$f=@(Get-ChildItem -LiteralPath {_q(directory)} -Filter 'TEST-*.xml' -Recurse "
        "-ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTimeUtc -gt $t0 });"
        "$w=@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
        "Where-Object { $_.CommandLine -match 'Gradle Test Executor' }).Count;"
        "'fresh=' + $f.Count; 'workers=' + $w")


def summary_script(directory: str, t0: str) -> str:
    return (
        f"$t0=[datetime]::Parse({_q(t0)},$null,'RoundtripKind');"
        "$tot=0;$fail=0;$skip=0;$lines=@();"
        f"foreach($x in (Get-ChildItem -LiteralPath {_q(directory)} -Filter 'TEST-*.xml' -Recurse "
        "| Where-Object { $_.LastWriteTimeUtc -gt $t0 })){"
        "[xml]$d=Get-Content -LiteralPath $x.FullName -Raw -Encoding UTF8;$s=$d.testsuite;"
        "$tot+=[int]$s.tests;$skip+=[int]$s.skipped;"
        "foreach($c in $s.testcase){ if($c.failure -or $c.error){ $fail++;"
        "$e=if($c.failure){$c.failure}else{$c.error};$m=([string]$e.message) -replace '\\s+',' ';"
        "if($m.Length -gt 120){$m=$m.Substring(0,120)};"
        "$lines+=($s.name+'.'+$c.name+' :: '+$m) } } };"
        "'total=' + $tot + ' failed=' + $fail + ' skipped=' + $skip; $lines")


def parse_kv(text: str) -> dict:
    out = {}
    for line in text.splitlines():
        k, sep, v = line.strip().partition("=")
        if sep:
            out[k.strip()] = v.strip()
    return out


def terminal_output(raw: str) -> str:
    """The IDE returns {"command_exit_code":N,"command_output":"..."} as the tool text."""
    try:
        d = json.loads(raw)
    except ValueError:
        return raw
    if isinstance(d, dict) and "command_output" in d:
        if d.get("command_exit_code") not in (0, None):
            raise RuntimeError(f"terminal command exited {d['command_exit_code']}: "
                               f"{str(d['command_output'])[:300]}")
        return str(d["command_output"])
    return raw


def is_finished(status: dict) -> bool:
    try:
        return int(status.get("fresh", 0)) > 0 and int(status.get("workers", 1)) == 0
    except ValueError:
        return False


def run_tests(call_tool, args, progress=lambda msg: None, sleep=time.sleep, clock=time.monotonic):
    """Returns (text, is_error)."""
    project = args["projectPath"]
    directory = _abs_dir(project, args["resultsDir"])

    def terminal(script, lines=300):
        return terminal_output(call_tool("execute_terminal_command", {
            "command": ps_command(script), "executeInShell": False,
            "projectPath": project, "maxLinesCount": lines}))

    t0 = next((l.strip() for l in reversed(terminal(NOW_SCRIPT).splitlines())
               if l.strip().endswith("Z") and l.strip()[:4].isdigit()), "")
    if not t0:
        return "could not read the VDI clock; run not started", True

    run_args = {"projectPath": project, "waitForExit": False}
    if args.get("configurationName"):
        run_args["configurationName"] = args["configurationName"]
    elif args.get("filePath") and args.get("line"):
        run_args["filePath"], run_args["line"] = args["filePath"], args["line"]
    else:
        return "give configurationName, or filePath together with line", True
    call_tool("execute_run_configuration", run_args)
    progress("test run started")

    deadline = clock() + 60 * int(args.get("timeoutMinutes") or 30)
    status = {}
    for i in range(10 ** 6):
        sleep(POLL_INTERVALS_S[min(i, len(POLL_INTERVALS_S) - 1)])
        status = parse_kv(terminal(poll_script(directory, t0)))
        progress(f"polled: {status.get('fresh', '?')} fresh result files, "
                 f"{status.get('workers', '?')} test workers alive")
        if is_finished(status):
            return terminal(summary_script(directory, t0)).strip(), False
        if clock() > deadline:
            return (f"timed out waiting for results in {directory} "
                    f"(last status: {status}). The run may still be going, may not have "
                    "started, or may not be a Gradle run (the IDE runner writes no XML)."), True
    raise AssertionError("unreachable")
