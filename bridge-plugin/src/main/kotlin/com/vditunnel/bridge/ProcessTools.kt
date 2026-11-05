package com.vditunnel.bridge

import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process execution handled entirely inside this plugin process -- plain
 * java.lang.ProcessBuilder, no PowerShell/cmd.exe, no IDE terminal widget. That sidesteps two
 * bugs execute_terminal_command has shown: the backslash-escape corruption on Windows paths,
 * and the Brave-Mode terminal-widget focus-stealing wedge when executeInShell=true.
 *
 * run_command also compresses well-known commands' output before it crosses the QR downlink,
 * same idea as github.com/dPeluChe/trs and github.com/rtk-ai/rtk -- their motivation is LLM
 * token/context cost, ours is the ~1-1.2 KB/s QR channel, so a smaller reply is directly less
 * wall-clock time. Only git status/diff/log are parsed for now; anything else gets generic
 * whitespace cleanup + a line cap. Gradle/JUnit output is deliberately NOT duplicated here --
 * host/vdi_tunnel/jobs.py's run_tests_and_report already digests TEST-*.xml host-side.
 */
object ProcessTools {
    val NAMES: Set<String> = setOf("run_command", "run_command_async", "get_command_status", "get_system_info")

    fun call(name: String, args: JsonObject): String = when (name) {
        "run_command" -> runCommand(args)
        "run_command_async" -> runCommandAsync(args)
        "get_command_status" -> getCommandStatus(args)
        "get_system_info" -> getSystemInfo(args)
        else -> throw IllegalArgumentException("unknown local tool: $name")
    }

    // ---- arg helpers ----

    private fun str(args: JsonObject, key: String): String =
        args.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IllegalArgumentException("missing argument: $key")

    private fun strOrNull(args: JsonObject, key: String): String? =
        args.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun intOrDefault(args: JsonObject, key: String, default: Int): Int =
        args.get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: default

    private fun boolOrDefault(args: JsonObject, key: String, default: Boolean): Boolean =
        args.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: default

    private fun argList(args: JsonObject, key: String): List<String> {
        val arr = args.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.map { it.asString }
    }

    // ---- run_command: synchronous, with output-compressing parsers ----

    private fun runCommand(args: JsonObject): String {
        val command = str(args, "command")
        val userArgs = argList(args, "args")
        val cwd = strOrNull(args, "cwd")
        val timeoutMs = intOrDefault(args, "timeoutMs", 30000)
        val maxLines = intOrDefault(args, "maxLines", 200)
        val requested = strOrNull(args, "parser") ?: "auto"
        val parser = if (requested == "auto") detectParser(command, userArgs) else requested

        val effectiveArgs = augmentArgsForParser(command, userArgs, parser)
        val pb = ProcessBuilder(listOf(command) + effectiveArgs)
        if (cwd != null) pb.directory(Path.of(cwd).toFile())

        val (exitCode, stdout, stderr) = execCapture(pb, timeoutMs)

        val compact = try {
            compressOutput(parser, stdout, maxLines)
        } catch (e: Exception) {
            cleanupRaw(stdout, maxLines)
        }
        val sb = StringBuilder()
        sb.append("exit=$exitCode")
        if (parser != "raw" && parser != "auto") sb.append(" parser=$parser")
        sb.append("\n").append(compact)
        if (stderr.isNotBlank()) sb.append("\n--- stderr ---\n").append(cleanupRaw(stderr, 40))
        return sb.toString()
    }

    private fun execCapture(pb: ProcessBuilder, timeoutMs: Int): Triple<Int, String, String> {
        val process = pb.start()
        val stdoutBuf = ByteArrayOutputStream()
        val stderrBuf = ByteArrayOutputStream()
        val t1 = Thread { process.inputStream.copyTo(stdoutBuf) }.apply { isDaemon = true; start() }
        val t2 = Thread { process.errorStream.copyTo(stderrBuf) }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            t1.join(1000); t2.join(1000)
            throw IllegalStateException("command timed out after ${timeoutMs}ms")
        }
        t1.join(2000); t2.join(2000)
        return Triple(process.exitValue(), stdoutBuf.toString(StandardCharsets.UTF_8), stderrBuf.toString(StandardCharsets.UTF_8))
    }

    private fun detectParser(command: String, userArgs: List<String>): String {
        val exe = command.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (exe != "git" && exe != "git.exe") return "raw"
        return when (userArgs.firstOrNull()) {
            "status" -> "git_status"
            "diff" -> "git_diff"
            "log" -> "git_log"
            else -> "raw"
        }
    }

    /** Inject machine-readable flags for a recognized git subcommand, unless the caller
     *  already asked for something that would conflict -- deterministic parsing beats
     *  scraping human-formatted text. */
    private fun augmentArgsForParser(command: String, userArgs: List<String>, parser: String): List<String> {
        val exe = command.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (exe != "git" && exe != "git.exe") return userArgs
        return when (parser) {
            "git_status" -> if (userArgs.any { it.startsWith("--porcelain") }) userArgs
                else userArgs + listOf("--porcelain=v2", "--branch")
            "git_log" -> if (userArgs.any { it == "--pretty" || it.startsWith("--pretty=") || it.startsWith("--format=") })
                userArgs
            else userArgs + listOf("--pretty=format:%h|%an|%s", "-n", "50")
            "git_diff" -> if (userArgs.any { it == "--stat" || it == "-p" || it == "--patch" || it.startsWith("-U") })
                userArgs
            else userArgs + listOf("--stat")
            else -> userArgs
        }
    }

    private fun compressOutput(parser: String, stdout: String, maxLines: Int): String = when (parser) {
        "git_status" -> parseGitStatusPorcelainV2(stdout)
        "git_log" -> parseGitLogPipe(stdout, maxLines)
        else -> cleanupRaw(stdout, maxLines) // git_diff --stat is already compact; raw/unknown get generic cleanup
    }

    private fun cleanupRaw(output: String, maxLines: Int): String {
        val lines = output.lines().map { it.trimEnd() }
        val trimmed = lines.dropLastWhile { it.isBlank() }.dropWhile { it.isBlank() }
        if (trimmed.isEmpty()) return "(no output)"
        val capped = trimmed.take(maxLines)
        val suffix = if (trimmed.size > maxLines) "\n... truncated, ${trimmed.size - maxLines} more lines" else ""
        return capped.joinToString("\n") + suffix
    }

    private fun parseGitStatusPorcelainV2(output: String): String {
        var branch: String? = null
        var ab: String? = null
        val staged = mutableListOf<String>()
        val unstaged = mutableListOf<String>()
        val untracked = mutableListOf<String>()
        val unmerged = mutableListOf<String>()
        for (raw in output.lineSequence()) {
            if (raw.isBlank()) continue
            when {
                raw.startsWith("# branch.head ") -> branch = raw.removePrefix("# branch.head ").trim()
                raw.startsWith("# branch.ab ") -> ab = raw.removePrefix("# branch.ab ").trim()
                raw.startsWith("# ") -> {}
                raw.startsWith("? ") -> untracked.add(raw.removePrefix("? "))
                raw.startsWith("u ") -> unmerged.add(raw.substringAfterLast(' '))
                raw.startsWith("1 ") || raw.startsWith("2 ") -> {
                    val parts = raw.split(' ', limit = 9)
                    if (parts.size >= 9) {
                        val xy = parts[1]
                        val path = parts.last()
                        val x = xy.getOrElse(0) { '.' }
                        val y = xy.getOrElse(1) { '.' }
                        if (x != '.') staged.add("$x $path")
                        if (y != '.') unstaged.add("$y $path")
                    }
                }
            }
        }
        val sb = StringBuilder()
        sb.append("branch: ${branch ?: "?"}")
        if (ab != null) sb.append(" ($ab)")
        sb.append("\n")
        if (staged.isNotEmpty()) sb.append("staged (${staged.size}):\n").append(staged.joinToString("\n") { "  $it" }).append("\n")
        if (unstaged.isNotEmpty()) sb.append("unstaged (${unstaged.size}):\n").append(unstaged.joinToString("\n") { "  $it" }).append("\n")
        if (unmerged.isNotEmpty()) sb.append("unmerged (${unmerged.size}): ${unmerged.joinToString(", ")}\n")
        if (untracked.isNotEmpty()) {
            val shown = untracked.take(30)
            sb.append("untracked (${untracked.size}):\n").append(shown.joinToString("\n") { "  $it" })
            if (untracked.size > 30) sb.append("\n  ... +${untracked.size - 30} more")
            sb.append("\n")
        }
        if (staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() && unmerged.isEmpty()) sb.append("clean\n")
        return sb.toString().trim()
    }

    private fun parseGitLogPipe(output: String, maxLines: Int): String {
        val lines = output.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return "(no commits)"
        val capped = lines.take(maxLines)
        val formatted = capped.map { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size == 3) "${parts[0]} ${parts[1]}: ${parts[2]}" else line
        }
        val suffix = if (lines.size > maxLines) "\n... truncated, ${lines.size - maxLines} more commits" else ""
        return formatted.joinToString("\n") + suffix
    }

    // ---- run_command_async / get_command_status: background jobs ----

    private class Job(val process: Process, val startedAt: Long) {
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        val lock = Any()
        @Volatile var exitCode: Int? = null
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val jobCounter = AtomicInteger(0)
    private const val MAX_BUFFER_CHARS = 200_000

    private fun runCommandAsync(args: JsonObject): String {
        val command = str(args, "command")
        val userArgs = argList(args, "args")
        val cwd = strOrNull(args, "cwd")
        val pb = ProcessBuilder(listOf(command) + userArgs)
        if (cwd != null) pb.directory(Path.of(cwd).toFile())

        val process = pb.start()
        val jobId = "job-${jobCounter.incrementAndGet()}"
        val job = Job(process, System.currentTimeMillis())
        jobs[jobId] = job

        drain(process.inputStream, job, job.stdoutBuf)
        drain(process.errorStream, job, job.stderrBuf)
        Thread { job.exitCode = process.waitFor() }.apply { isDaemon = true; start() }

        return "started $jobId: ${pb.command().joinToString(" ")}"
    }

    private fun drain(stream: java.io.InputStream, job: Job, sink: StringBuilder) {
        Thread {
            val reader = stream.bufferedReader(StandardCharsets.UTF_8)
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                synchronized(job.lock) {
                    sink.append(buf, 0, n)
                    if (sink.length > MAX_BUFFER_CHARS) sink.delete(0, sink.length - MAX_BUFFER_CHARS)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun getCommandStatus(args: JsonObject): String {
        val jobId = str(args, "jobId")
        val job = jobs[jobId] ?: throw IllegalStateException("unknown jobId: $jobId")
        val tailLines = intOrDefault(args, "tailLines", 100)
        val running = job.exitCode == null
        val (stdoutSnapshot, stderrSnapshot) = synchronized(job.lock) {
            job.stdoutBuf.toString() to job.stderrBuf.toString()
        }
        val sb = StringBuilder()
        sb.append(if (running) "status=running" else "status=exited exit=${job.exitCode}")
        sb.append(" elapsedMs=${System.currentTimeMillis() - job.startedAt}\n")
        sb.append("--- stdout (last $tailLines lines) ---\n").append(tail(stdoutSnapshot, tailLines))
        if (stderrSnapshot.isNotBlank()) {
            sb.append("\n--- stderr (last $tailLines lines) ---\n").append(tail(stderrSnapshot, tailLines))
        }
        return sb.toString()
    }

    private fun tail(text: String, n: Int): String {
        val lines = text.lines()
        val trimmed = if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
        if (trimmed.isEmpty()) return "(no output yet)"
        return trimmed.takeLast(n).joinToString("\n")
    }

    // ---- get_system_info ----

    private fun getSystemInfo(args: JsonObject): String {
        val includeEnv = boolOrDefault(args, "includeEnv", false)
        val sb = StringBuilder()
        sb.append("hostname=${java.net.InetAddress.getLocalHost().hostName}\n")
        sb.append("os=${System.getProperty("os.name")} ${System.getProperty("os.version")}\n")
        sb.append("user=${System.getProperty("user.name")}\n")
        sb.append("javaVersion=${System.getProperty("java.version")}\n")
        sb.append("drives:\n")
        for (root in java.io.File.listRoots()) {
            sb.append("  ${root.path} total=${root.totalSpace} free=${root.freeSpace} usable=${root.usableSpace}\n")
        }
        if (includeEnv) {
            sb.append("env:\n")
            System.getenv().toSortedMap().forEach { (k, v) -> sb.append("  $k=$v\n") }
        }
        return sb.toString().trim()
    }

    // ---- tools/list schema entries, spliced into the IDE's own list ----

    fun toolDefinitions(): List<JsonObject> = listOf(
        tool(
            "run_command",
            "Runs a command via plain java.lang.ProcessBuilder -- no PowerShell/cmd.exe, no IDE " +
                "terminal widget -- and returns a compact result instead of raw output. For " +
                "recognized git subcommands (status/diff/log) it adds machine-readable flags " +
                "(--porcelain=v2/--pretty=format) when the caller didn't already specify " +
                "conflicting ones, then parses that into a short summary; same motivation as " +
                "dPeluChe/trs and rtk-ai/rtk but for the QR downlink's ~1 KB/s ceiling, not LLM " +
                "token cost. Anything else falls back to whitespace-cleaned, line-capped raw " +
                "output. Blocks until the command exits or timeoutMs elapses.",
            obj(
                "command" to prop("string", "The program to run, e.g. 'git'."),
                "args" to prop("array", "Argument list, e.g. ['status']. Each element a plain string."),
                "cwd" to prop("string", "Working directory (absolute path)."),
                "timeoutMs" to prop("integer", "Kill the process and fail if it hasn't exited by then. Default 30000."),
                "parser" to prop("string", "'auto' (default), 'git_status', 'git_diff', 'git_log', or 'raw' to force generic cleanup."),
                "maxLines" to prop("integer", "Cap on output lines after compression. Default 200."),
            ),
            listOf("command"),
        ),
        tool(
            "run_command_async",
            "Starts a command in the background (ProcessBuilder, no PowerShell/Start-Process, " +
                "no hidden window, no sentinel-file convention needed) and returns a jobId " +
                "immediately -- for long-running commands (builds, big transfers) that would " +
                "otherwise outlast a single tunnel request. Poll with get_command_status. Job " +
                "state lives only for the life of the IDE process (not persisted across restarts, " +
                "not automatically cleaned up).",
            obj(
                "command" to prop("string", "The program to run, e.g. 'gradlew.bat'."),
                "args" to prop("array", "Argument list, each element a plain string."),
                "cwd" to prop("string", "Working directory (absolute path)."),
            ),
            listOf("command"),
        ),
        tool(
            "get_command_status",
            "Polls a job started by run_command_async: whether it's still running or its exit " +
                "code, plus the tail of its captured stdout/stderr (each stream capped at ~200K " +
                "characters total in memory, oldest data dropped first).",
            obj(
                "jobId" to prop("string", "The jobId returned by run_command_async."),
                "tailLines" to prop("integer", "How many lines of stdout/stderr to return, from the end. Default 100."),
            ),
            listOf("jobId"),
        ),
        tool(
            "get_system_info",
            "Reports hostname, OS, user, Java version, and drive space -- all via plain JDK APIs " +
                "(InetAddress, System properties, File.listRoots), no PowerShell. Pass " +
                "includeEnv=true to also dump environment variables.",
            obj("includeEnv" to prop("boolean", "Include environment variables. Default false.")),
            listOf(),
        ),
    )
}
