---
name: vdi-tunnel-on
description: Enable VDI-tunnel routing mode — route all IntelliJ/VDI project operations (build, run, lint, analyze, project structure, file ops) through the mcp__vdi-tunnel__* tools instead of local execution, until /vdi-tunnel-off. Trigger on "enable vdi-tunnel", "use vdi-tunnel for everything", "route through vdi-tunnel", "/vdi-tunnel-on".
---

# Enable VDI-tunnel routing mode

Turns on a session-wide mode where IDE/project operations go through the `vdi-tunnel`
MCP server (the IntelliJ instance inside the VDI) rather than the local host. The VDI
project lives **inside the VDI**, not on this host's filesystem, so local Bash/Read/Write/
Edit cannot reach it — the tunnel is the only path.

## Steps

1. **Confirm the MCP server is loaded.** The `vdi-tunnel` tools (`mcp__vdi-tunnel__*`)
   only load at Claude Code session start. If they are not available in this session,
   tell the user to start a fresh session in this project first (the server is already
   registered in local config), then stop — do not proceed.

2. **Identify which remote machine you're actually connected to.** Different VDI sessions
   are different machines with different projects open — a projectPath remembered for one
   machine is wrong on another. Call `mcp__vdi-tunnel__execute_terminal_command` with
   `command: "powershell -NoProfile -Command hostname"` and `executeInShell: false` (never
   `true` — see PROTOCOL/CLAUDE.md notes on why) to get the live remote computername.
   **Never use `cmd.exe` / `cmd /c`** for this or anything else on a VDI — it's forbidden by
   client org policy on at least one machine, and nothing technically blocks it (Brave Mode
   suppresses the IDE's confirmation prompt), so a violation wouldn't surface as an error;
   always use `powershell -NoProfile -Command "…"` instead. If the hostname call fails, stop
   and tell the user the machine couldn't be identified — do not guess or fall back silently.

3. **Determine the project path for this machine.**
   - If the skill was given an explicit project-path argument, use it for this session and
     save it into the lookup (next sub-step), overwriting any prior entry for this machine.
   - Otherwise, read `.claude/vdi-tunnel-projects.json` (a `{computerName: projectPath}`
     map; treat a missing file as empty). If the detected computername has an entry, use it.
   - If neither of the above yields a path, ask the user for the projectPath for this
     machine — do not default to any hardcoded path.
   - Write the resolved `{computerName: projectPath}` pair into
     `.claude/vdi-tunnel-projects.json` (merge with existing entries for other machines;
     create the file if absent) so future `/vdi-tunnel-on` runs on this same machine don't
     need to ask again.

4. **Write the mode marker** so the mode is visible and survives context compaction —
   use the Write tool to create `.claude/vdi-tunnel.active` containing exactly:
   ```
   computerName=<the computername from step 2>
   projectPath=<the project path from step 3>
   ```

5. **Build this machine's knowledge-base doc if it doesn't exist yet.** Check whether
   `docs/vdi-notes/machines/<computerName>.md` exists. `docs/vdi-notes/` is entirely
   gitignored already — this knowledge is local-only, never commit anything under it. If the
   file is missing, spawn a subagent (Agent tool, `subagent_type: "fork"`) to explore this
   machine read-only over `mcp__vdi-tunnel__*` and write that doc. Brief the subagent to:
   - **Never invoke `cmd.exe`** (no `cmd /c` anything, including for `net use`) — use
     `powershell -NoProfile -Command "…"` for every terminal call.
   - Stay read-only: drives (`Get-PSDrive`/`Get-Volume` + `net use` via PowerShell) and their
     purposes, installed software (registry uninstall keys + `Program Files`/
     `Program Files (x86)` listings), project structure (`get_project_modules`,
     `get_run_configurations`), and which of the cached MCP tools actually respond live for
     this project (spot-check a handful, no need to exhaustively probe all of them).
   - **Do not try to work around** a permission prompt, access-denied result, or "tool not
     found" response — just record it as observed, per the routing-mode ground rules.
   - Follow the structure/tone of `docs/vdi-notes/cusb19.md` (a per-project precedent doc in
     this repo) adapted to per-machine content — factual and concise, not padded.
   - This takes a few minutes of real tunnel round trips; mention to the user that it's
     running in the background rather than blocking routing-mode confirmation on it.
   If the doc already exists, skip this step silently — don't re-explore an already-known machine.

6. **Adopt the routing rule for the rest of this session** (and re-adopt it whenever the
   marker file exists):
   - For any IntelliJ/VDI project action, call the matching `mcp__vdi-tunnel__*` tool and
     pass `projectPath`. This covers at least: `build_project`, `get_run_configurations`,
     `execute_run_configuration`, `lint_files`, `get_file_problems`, `get_project_modules`,
     `get_project_dependencies`, `get_all_open_file_paths`, `create_new_file`,
     `analyze_calls`, and the other tools the server exposes.
   - Do **not** use local Bash/Read/Write/Edit to act on the VDI project — those hit the
     host, not the VDI.
   - Keep using local tools only for this tunnel repo itself (host/, bridge-plugin/, docs).

7. **Remind the user of the operating requirements** (once, briefly): each tunnel call
   drives the real mouse/keyboard against the VDI for a few seconds, so the **bridge tool
   window must stay visible and wide enough that its QR sizing guide reads green**, and
   they should avoid using the machine's input while a call runs.

8. **Confirm** to the user that VDI-tunnel routing is now ON, for which `computerName` and
   `projectPath`, and that `/vdi-tunnel-off` disables it.

## Notes
- If a tunnel call fails (e.g. panel not found / uplink failed), it almost always means
  the bridge window is not fully visible or is too narrow — surface that, don't retry blindly.
- This mode is a routing preference, not a lock: if the user explicitly asks for a local
  action, honor it.
- The `hostname` check in step 2 only runs when `/vdi-tunnel-on` is invoked — a resumed
  session with an existing marker trusts it without re-checking on every call (that would
  drive real keystrokes per tool call). If you suspect the VDI session changed underneath
  an already-active marker (e.g. a tool call reports a project path that doesn't match what
  the marker says), re-run `/vdi-tunnel-on` to refresh it rather than editing the marker by hand.
- `docs/vdi-notes/machines/<computerName>.md` (step 5) is a cache, not a live query — if the
  user reports something on the machine has changed, re-explore and update the doc rather
  than trusting it blindly, the same way `docs/vdi-notes/<project>.md` project maps work.
