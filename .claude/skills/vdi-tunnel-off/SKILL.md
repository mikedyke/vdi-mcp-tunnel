---
name: vdi-tunnel-off
description: Disable VDI-tunnel routing mode — stop routing project/IDE operations through the mcp__vdi-tunnel__* tools and resume normal local execution. Trigger on "disable vdi-tunnel", "stop using vdi-tunnel", "turn off vdi-tunnel", "/vdi-tunnel-off".
---

# Disable VDI-tunnel routing mode

Turns off the session-wide VDI-tunnel routing mode enabled by `/vdi-tunnel-on`.

## Steps

1. **Remove the mode marker.** Delete `.claude/vdi-tunnel.active` if it exists (use a
   Bash `rm -f .claude/vdi-tunnel.active`). If it does not exist, the mode was already off.

2. **Stop routing through the tunnel.** For the rest of this session, do not default to the
   `mcp__vdi-tunnel__*` tools. Resume normal local execution (Bash/Read/Write/Edit, local
   build/test) for work on this host. Only use a `vdi-tunnel` tool if the user explicitly
   asks for it in a specific request.

3. **Confirm** to the user that VDI-tunnel routing is now OFF.

## Notes
- This does not unregister or stop the `vdi-tunnel` MCP server (it stays available); it only
  turns off the default-routing behavior. Re-enable with `/vdi-tunnel-on`.
