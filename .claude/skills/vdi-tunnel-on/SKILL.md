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

2. **Determine the project path.** Use the argument passed to the skill if any; otherwise
   default to `C:\Service\ALM\DEV\git\cusb19`. This is the `projectPath` the VDI tools need.

3. **Write the mode marker** so the mode is visible and survives context compaction —
   use the Write tool to create `.claude/vdi-tunnel.active` containing exactly:
   ```
   projectPath=<the project path from step 2>
   ```

4. **Adopt the routing rule for the rest of this session** (and re-adopt it whenever the
   marker file exists):
   - For any IntelliJ/VDI project action, call the matching `mcp__vdi-tunnel__*` tool and
     pass `projectPath`. This covers at least: `build_project`, `get_run_configurations`,
     `execute_run_configuration`, `lint_files`, `get_file_problems`, `get_project_modules`,
     `get_project_dependencies`, `get_all_open_file_paths`, `create_new_file`,
     `analyze_calls`, and the other tools the server exposes.
   - Do **not** use local Bash/Read/Write/Edit to act on the VDI project — those hit the
     host, not the VDI.
   - Keep using local tools only for this tunnel repo itself (host/, bridge-plugin/, docs).

5. **Remind the user of the operating requirements** (once, briefly): each tunnel call
   drives the real mouse/keyboard against the VDI for a few seconds, so the **bridge tool
   window must stay visible and wide enough that its QR sizing guide reads green**, and
   they should avoid using the machine's input while a call runs.

6. **Confirm** to the user that VDI-tunnel routing is now ON and for which `projectPath`,
   and that `/vdi-tunnel-off` disables it.

## Notes
- If a tunnel call fails (e.g. panel not found / uplink failed), it almost always means
  the bridge window is not fully visible or is too narrow — surface that, don't retry blindly.
- This mode is a routing preference, not a lock: if the user explicitly asks for a local
  action, honor it.
