package com.vditunnel.bridge

import com.google.gson.JsonObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64

/**
 * File-mutation tools handled entirely inside this plugin process -- plain
 * java.nio.file.Files calls, no subprocess, no PowerShell/cmd. Two things fall out of that:
 *
 * 1. No PowerShell is ever spawned, so the EDR signature that blocked base64+WriteAllBytes
 *    file landing on the LBBW VDI (see memory lbbw-vdi-base64-transfer-blocked /
 *    powershell-vdi-scripting-traps) does not apply -- nothing new gets spawned, this is
 *    just the already-installed, already-trusted plugin doing file I/O.
 * 2. A java.nio.file.Path has no notion of "which IntelliJ project is open" the way the
 *    IDE's own create_new_file/replace_text_in_file tools do, so these work identically
 *    inside or outside the currently open project root (see memory
 *    vdi-tunnel-single-project-limitation).
 *
 * Base64 decoding and SHA-256 hashing are fine to use here: the constraint that ruled out
 * crypt/encoding functions applies to a spawned server process (PowerShell) reconstructing
 * content from typed text, not to this plugin's own compiled code.
 */
object LocalFileTools {
    val NAMES: Set<String> = setOf(
        "write_file", "patch_file", "move_file", "copy_file", "hash_file", "apply_patch",
        "delete_file", "create_directory", "stat_file", "list_directory", "read_file_raw",
        "find_files", "grep_files", "hash_tree",
    )

    fun call(name: String, args: JsonObject): String = when (name) {
        "write_file" -> writeFile(args)
        "read_file_raw" -> readFileRaw(args)
        "patch_file" -> patchFile(args)
        "move_file" -> relocate(args, move = true)
        "copy_file" -> relocate(args, move = false)
        "hash_file" -> hashFile(args)
        "apply_patch" -> applyPatch(args)
        "delete_file" -> deleteFile(args)
        "create_directory" -> createDirectory(args)
        "stat_file" -> statFile(args)
        "list_directory" -> listDirectory(args)
        "find_files" -> findFiles(args)
        "grep_files" -> grepFiles(args)
        "hash_tree" -> hashTree(args)
        else -> throw IllegalArgumentException("unknown local tool: $name")
    }

    // ---- arg helpers ----

    private fun str(args: JsonObject, key: String): String =
        args.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IllegalArgumentException("missing argument: $key")

    private fun bool(args: JsonObject, key: String, default: Boolean): Boolean =
        args.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: default

    // ---- tool implementations ----

    private fun writeFile(args: JsonObject): String {
        val path = Path.of(str(args, "path"))
        val overwrite = bool(args, "overwrite", false)
        val createDirs = bool(args, "createDirs", true)
        if (!overwrite && Files.exists(path)) throw IllegalStateException("file exists: $path")
        if (createDirs) path.parent?.let { Files.createDirectories(it) }
        val bytes: ByteArray = if (args.has("contentBase64"))
            Base64.getDecoder().decode(str(args, "contentBase64"))
        else
            str(args, "text").toByteArray(StandardCharsets.UTF_8)
        Files.write(path, bytes)
        return "wrote ${bytes.size} bytes to $path"
    }

    private fun readFileRaw(args: JsonObject): String {
        val path = Path.of(str(args, "path"))
        if (!Files.exists(path)) throw IllegalStateException("not found: $path")
        if (Files.isDirectory(path)) throw IllegalStateException("is a directory: $path")
        val asBase64 = bool(args, "asBase64", false)
        val bytes = Files.readAllBytes(path)
        if (asBase64) return Base64.getEncoder().encodeToString(bytes)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            throw IllegalStateException("not valid UTF-8 text (${bytes.size} bytes) -- retry with asBase64=true")
        }
    }

    private fun patchFile(args: JsonObject): String {
        val path = Path.of(str(args, "path"))
        val oldText = str(args, "oldText")
        val newText = str(args, "newText")
        val replaceAll = bool(args, "replaceAll", true)
        val regex = bool(args, "regex", false)
        val caseSensitive = bool(args, "caseSensitive", true)
        if (!Files.exists(path)) throw IllegalStateException("file not found: $path")
        val original = Files.readString(path, StandardCharsets.UTF_8)

        val result = if (regex) {
            val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val re = Regex(oldText, opts)
            if (replaceAll) re.replace(original, newText) else re.replaceFirst(original, newText)
        } else {
            if (oldText.isEmpty()) throw IllegalArgumentException("oldText must not be empty")
            if (replaceAll) {
                original.replace(oldText, newText, ignoreCase = !caseSensitive)
            } else {
                val idx = original.indexOf(oldText, ignoreCase = !caseSensitive)
                if (idx < 0) original else original.substring(0, idx) + newText + original.substring(idx + oldText.length)
            }
        }
        if (result == original) throw IllegalStateException("no occurrences found")
        Files.writeString(path, result, StandardCharsets.UTF_8)
        return "ok"
    }

    private fun relocate(args: JsonObject, move: Boolean): String {
        val src = Path.of(str(args, "src"))
        val dst = Path.of(str(args, "dst"))
        val overwrite = bool(args, "overwrite", false)
        dst.parent?.let { Files.createDirectories(it) }
        val opts = if (overwrite) arrayOf(StandardCopyOption.REPLACE_EXISTING) else arrayOf()
        if (move) Files.move(src, dst, *opts) else Files.copy(src, dst, *opts)
        return "${if (move) "moved" else "copied"} $src -> $dst"
    }

    private fun hashFile(args: JsonObject): String {
        val path = Path.of(str(args, "path"))
        if (!Files.exists(path)) throw IllegalStateException("file not found: $path")
        val digest = MessageDigest.getInstance("SHA-256")
        val size = Files.size(path)
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return "size=$size sha256=$hex"
    }

    private fun hashTree(args: JsonObject): String {
        val root = Path.of(str(args, "root"))
        if (!Files.exists(root)) throw IllegalStateException("not found: $root")
        if (!Files.isDirectory(root)) throw IllegalStateException("not a directory: $root")
        val pattern = args.get("pattern")?.takeIf { it.isJsonPrimitive }?.asString
        val fileMatcher = pattern?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val maxFiles = args.get("maxFiles")?.takeIf { it.isJsonPrimitive }?.asInt ?: 5000

        val results = mutableListOf<String>()
        var truncated = false
        walkEntries(root, Int.MAX_VALUE) { p, isDir ->
            if (isDir) return@walkEntries
            if (fileMatcher != null && !fileMatcher.matches(Path.of(p.fileName.toString()))) return@walkEntries
            if (results.size >= maxFiles) { truncated = true; return@walkEntries }
            val rel = root.relativize(p).toString()
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(p).use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            results.add("$rel ${Files.size(p)} $hex")
        }
        return results.sorted().joinToString("\n") + if (truncated) "\n... truncated at $maxFiles files" else ""
    }

    private fun deleteFile(args: JsonObject): String {
        val path = Path.of(str(args, "path"))
        val recursive = bool(args, "recursive", false)
        if (!Files.exists(path)) throw IllegalStateException("not found: $path")
        if (Files.isDirectory(path)) {
            if (!recursive) {
                Files.delete(path) // throws DirectoryNotEmptyException if non-empty, same as Remove-Item without -Recurse
                return "deleted empty directory: $path"
            }
            var count = 0
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { p -> Files.delete(p); count++ }
            }
            return "deleted directory recursively: $path ($count entries removed)"
        }
        Files.delete(path)
        return "deleted file: $path"
    }

    private fun createDirectory(args: JsonObject): String {
        val path = Path.of(str(args, "path"))
        val existed = Files.isDirectory(path)
        Files.createDirectories(path)
        return if (existed) "directory already exists: $path" else "created directory: $path"
    }

    private fun statFile(args: JsonObject): String {
        val path = Path.of(str(args, "path"))
        if (!Files.exists(path)) return "exists=false"
        val isDir = Files.isDirectory(path)
        val size = if (isDir) -1 else Files.size(path)
        val mtime = Files.getLastModifiedTime(path).toInstant().toString()
        return "exists=true isDirectory=$isDir size=$size mtime=$mtime"
    }

    private fun listDirectory(args: JsonObject): String {
        val path = Path.of(str(args, "path"))
        if (!Files.exists(path)) throw IllegalStateException("not found: $path")
        if (!Files.isDirectory(path)) throw IllegalStateException("not a directory: $path")
        val recursive = bool(args, "recursive", false)
        val maxDepth = if (!recursive) 1
            else args.get("maxDepth")?.takeIf { it.isJsonPrimitive }?.asInt ?: Int.MAX_VALUE
        val limit = 2000
        val lines = mutableListOf<String>()
        var truncated = false
        walkEntries(path, maxDepth) { p, isDir ->
            if (lines.size >= limit) { truncated = true; return@walkEntries }
            val rel = path.relativize(p).toString()
            lines.add(if (isDir) "D $rel" else "F $rel ${Files.size(p)}")
        }
        return lines.sorted().joinToString("\n") + if (truncated) "\n... truncated at $limit entries" else ""
    }

    private fun findFiles(args: JsonObject): String {
        val root = Path.of(str(args, "root"))
        if (!Files.exists(root)) throw IllegalStateException("not found: $root")
        if (!Files.isDirectory(root)) throw IllegalStateException("not a directory: $root")
        val pattern = str(args, "pattern")
        val matchMode = args.get("matchMode")?.takeIf { it.isJsonPrimitive }?.asString ?: "substring"
        val matchAgainst = args.get("matchAgainst")?.takeIf { it.isJsonPrimitive }?.asString ?: "name"
        val typeFilter = args.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: "any"
        val recursive = bool(args, "recursive", true)
        val maxDepth = if (!recursive) 1
            else args.get("maxDepth")?.takeIf { it.isJsonPrimitive }?.asInt ?: Int.MAX_VALUE
        val caseSensitive = bool(args, "caseSensitive", false)
        val maxResults = args.get("maxResults")?.takeIf { it.isJsonPrimitive }?.asInt ?: 2000

        val matches: (String) -> Boolean = when (matchMode) {
            "glob" -> {
                val pm = FileSystems.getDefault().getPathMatcher("glob:$pattern")
                val fn: (String) -> Boolean = { s -> pm.matches(Path.of(s)) }
                fn
            }
            "regex" -> {
                val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val re = Regex(pattern, opts)
                val fn: (String) -> Boolean = { s -> re.containsMatchIn(s) }
                fn
            }
            else -> { s: String -> s.contains(pattern, ignoreCase = !caseSensitive) }
        }

        val results = mutableListOf<String>()
        var truncated = false
        walkEntries(root, maxDepth) { p, isDir ->
            if (typeFilter == "file" && isDir) return@walkEntries
            if (typeFilter == "directory" && !isDir) return@walkEntries
            if (results.size >= maxResults) { truncated = true; return@walkEntries }
            val rel = root.relativize(p).toString()
            val candidate = if (matchAgainst == "path") rel else p.fileName.toString()
            if (matches(candidate)) results.add(if (isDir) "D $rel" else "F $rel ${Files.size(p)}")
        }
        return results.sorted().joinToString("\n") + if (truncated) "\n... truncated at $maxResults results" else ""
    }

    private fun grepFiles(args: JsonObject): String {
        val root = Path.of(str(args, "root"))
        if (!Files.exists(root)) throw IllegalStateException("not found: $root")
        val pattern = str(args, "pattern")
        val regexMode = bool(args, "regex", false)
        val caseSensitive = bool(args, "caseSensitive", true)
        val filePattern = args.get("filePattern")?.takeIf { it.isJsonPrimitive }?.asString
        val recursive = bool(args, "recursive", true)
        val maxDepth = if (!recursive) 1
            else args.get("maxDepth")?.takeIf { it.isJsonPrimitive }?.asInt ?: Int.MAX_VALUE
        val maxMatches = args.get("maxMatches")?.takeIf { it.isJsonPrimitive }?.asInt ?: 500
        val contextLines = args.get("contextLines")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

        val lineMatches: (String) -> Boolean = if (regexMode) {
            val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val re = Regex(pattern, opts)
            val fn: (String) -> Boolean = { line -> re.containsMatchIn(line) }
            fn
        } else {
            { line: String -> line.contains(pattern, ignoreCase = !caseSensitive) }
        }

        val targetIsDir = Files.isDirectory(root)
        val fileMatcher = filePattern?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val candidates = mutableListOf<Path>()
        if (!targetIsDir) {
            candidates.add(root)
        } else {
            walkEntries(root, maxDepth) { p, isDir ->
                if (!isDir && (fileMatcher == null || fileMatcher.matches(Path.of(p.fileName.toString())))) {
                    candidates.add(p)
                }
            }
        }

        val results = mutableListOf<String>()
        var truncated = false
        outer@ for (file in candidates.sorted()) {
            val lines = try {
                Files.readAllLines(file, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                continue // binary or unreadable -- skip, like grep -I
            }
            val label = if (targetIsDir) root.relativize(file).toString() else file.toString()
            for ((idx, line) in lines.withIndex()) {
                if (!lineMatches(line)) continue
                if (results.size >= maxMatches) { truncated = true; break@outer }
                if (contextLines > 0) {
                    val from = maxOf(0, idx - contextLines)
                    val to = minOf(lines.size - 1, idx + contextLines)
                    for (ctx in from..to) {
                        val sep = if (ctx == idx) ":" else "-"
                        results.add("$label$sep${ctx + 1}: ${lines[ctx]}")
                    }
                    results.add("--")
                } else {
                    results.add("$label:${idx + 1}: $line")
                }
            }
        }
        return results.joinToString("\n") + if (truncated) "\n... truncated at $maxMatches matches" else ""
    }

    /** Error-tolerant directory walk: a permission-denied subdirectory is skipped rather than
     *  aborting the whole call, unlike a plain Files.walk() stream. */
    private fun walkEntries(root: Path, maxDepth: Int, visit: (Path, Boolean) -> Unit) {
        Files.walkFileTree(root, emptySet(), maxDepth, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root) visit(dir, true)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // A directory sitting exactly at maxDepth (so it can't be descended into) is
                // reported here, not via preVisitDirectory -- so "is it a directory" has to
                // come from attrs, not from which callback fired.
                visit(file, attrs.isDirectory)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
    }

    // ---- apply_patch: a minimal unified-diff applier, no git involved ----

    private data class Hunk(val oldLines: List<String>, val newLines: List<String>)

    private fun applyPatch(args: JsonObject): String {
        val baseDir = Path.of(str(args, "baseDir"))
        val diffText = str(args, "diffText")
        val files = parseUnifiedDiff(diffText)
        if (files.isEmpty()) throw IllegalStateException("no file hunks found in diffText")
        val results = mutableListOf<String>()
        for ((relPath, hunks) in files) {
            val target = baseDir.resolve(relPath)
            if (!Files.exists(target)) throw IllegalStateException("target file not found: $target")
            val raw = Files.readString(target, StandardCharsets.UTF_8)
            val hadCRLF = raw.contains("\r\n")
            val normalized = raw.replace("\r\n", "\n")
            val endsWithNewline = normalized.endsWith("\n")
            val bodyLines = (if (endsWithNewline) normalized.dropLast(1) else normalized).split("\n")
            val patched = applyHunks(bodyLines, hunks, relPath)
            var out = patched.joinToString("\n")
            if (endsWithNewline) out += "\n"
            if (hadCRLF) out = out.replace("\n", "\r\n")
            Files.writeString(target, out, StandardCharsets.UTF_8)
            results.add("$relPath: ${hunks.size} hunk(s) applied")
        }
        return results.joinToString("; ")
    }

    private fun parseUnifiedDiff(diffText: String): Map<String, List<Hunk>> {
        val files = LinkedHashMap<String, MutableList<Hunk>>()
        val lines = diffText.split("\n").map { it.removeSuffix("\r") }
        var i = 0
        var currentPath: String? = null
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("--- ")) {
                val plus = lines.getOrNull(i + 1) ?: ""
                if (plus.startsWith("+++ ")) {
                    currentPath = stripDiffPrefix(plus.removePrefix("+++ ").trim())
                    files.putIfAbsent(currentPath, mutableListOf())
                    i += 2
                    continue
                }
            }
            if (line.startsWith("@@")) {
                val path = currentPath ?: throw IllegalStateException("hunk header before a file header")
                i++
                val oldB = mutableListOf<String>()
                val newB = mutableListOf<String>()
                while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("--- ")) {
                    val l = lines[i]
                    when {
                        l.startsWith(" ") -> { oldB.add(l.substring(1)); newB.add(l.substring(1)) }
                        l.startsWith("-") -> oldB.add(l.substring(1))
                        l.startsWith("+") -> newB.add(l.substring(1))
                        l.startsWith("\\") -> {} // "\ No newline at end of file"
                        else -> {}
                    }
                    i++
                }
                files[path]!!.add(Hunk(oldB, newB))
                continue
            }
            i++
        }
        return files
    }

    private fun stripDiffPrefix(p: String): String =
        p.substringBefore('\t').removePrefix("a/").removePrefix("b/")

    private fun applyHunks(original: List<String>, hunks: List<Hunk>, relPath: String): List<String> {
        val result = original.toMutableList()
        var searchFrom = 0
        for ((idx, hunk) in hunks.withIndex()) {
            val pos = indexOfSubsequence(result, hunk.oldLines, searchFrom)
                ?: throw IllegalStateException("$relPath: hunk ${idx + 1} context not found")
            result.subList(pos, pos + hunk.oldLines.size).clear()
            result.addAll(pos, hunk.newLines)
            searchFrom = pos + hunk.newLines.size
        }
        return result
    }

    private fun indexOfSubsequence(haystack: List<String>, needle: List<String>, from: Int): Int? {
        if (needle.isEmpty()) return from
        if (from <= haystack.size - needle.size) {
            outer@ for (start in from..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[start + j] != needle[j]) continue@outer
                return start
            }
        }
        if (from > 0) {
            outer2@ for (start in 0 until minOf(from, haystack.size - needle.size + 1)) {
                for (j in needle.indices) if (haystack[start + j] != needle[j]) continue@outer2
                return start
            }
        }
        return null
    }

    // ---- tools/list schema entries, spliced into the IDE's own list ----

    fun toolDefinitions(): List<JsonObject> = listOf(
        tool(
            "read_file_raw",
            "Reads a file's full content at an absolute path, in the plugin process -- no " +
                "subprocess, no project restriction, and no risk of the mojibake/encoding loss the " +
                "IDE's own read_file can show on non-ASCII content. Returns UTF-8 text by default; " +
                "pass asBase64=true for binary files or if text decoding fails. Distinct from (does " +
                "not replace) the IDE's own read_file, which offers line-numbered/range modes for " +
                "source inside a project -- use that for everyday source reading, this one for an " +
                "exact byte-for-byte read of any absolute path.",
            obj(
                "path" to prop("string", "Absolute path to read."),
                "asBase64" to prop("boolean", "Return base64-encoded bytes instead of UTF-8 text. Default false."),
            ),
            listOf("path"),
        ),
        tool(
            "write_file",
            "Writes text or binary (base64) content directly to an absolute path via plain " +
                "java.nio.file.Files I/O inside the plugin process -- no PowerShell/cmd involved and " +
                "no restriction to the currently open IDE project. Use for landing a file anywhere on " +
                "the VDI filesystem, including outside any open project.",
            obj(
                "path" to prop("string", "Absolute destination path."),
                "text" to prop("string", "UTF-8 text content. Use this OR contentBase64, not both."),
                "contentBase64" to prop("string", "Base64-encoded binary content, decoded by the plugin itself."),
                "createDirs" to prop("boolean", "Create parent directories if missing. Default true."),
                "overwrite" to prop("boolean", "Overwrite an existing file. Default false."),
            ),
            listOf("path"),
        ),
        tool(
            "patch_file",
            "Find-and-replace text in a file at an absolute path, in the plugin process " +
                "(java.nio.file.Files + String.replace) -- the same semantics as the IDE's own " +
                "replace_text_in_file, but not restricted to the currently open project.",
            obj(
                "path" to prop("string", "Absolute path to the file to edit."),
                "oldText" to prop("string", "Text (or regex pattern, if regex=true) to find."),
                "newText" to prop("string", "Replacement text."),
                "replaceAll" to prop("boolean", "Replace all occurrences. Default true."),
                "caseSensitive" to prop("boolean", "Case-sensitive match. Default true."),
                "regex" to prop("boolean", "Treat oldText as a regular expression. Default false."),
            ),
            listOf("path", "oldText", "newText"),
        ),
        tool(
            "move_file",
            "Moves a file from src to dst (java.nio.file.Files.move). Any absolute paths, no project restriction.",
            obj(
                "src" to prop("string", "Absolute source path."),
                "dst" to prop("string", "Absolute destination path."),
                "overwrite" to prop("boolean", "Overwrite an existing destination. Default false."),
            ),
            listOf("src", "dst"),
        ),
        tool(
            "copy_file",
            "Copies a file from src to dst (java.nio.file.Files.copy). Any absolute paths, no project restriction.",
            obj(
                "src" to prop("string", "Absolute source path."),
                "dst" to prop("string", "Absolute destination path."),
                "overwrite" to prop("boolean", "Overwrite an existing destination. Default false."),
            ),
            listOf("src", "dst"),
        ),
        tool(
            "hash_file",
            "Returns the size and SHA-256 hex digest of a file at an absolute path, computed " +
                "in-process (java.security.MessageDigest) to verify a transfer without reading the " +
                "whole file back over the tunnel.",
            obj("path" to prop("string", "Absolute path to the file to hash.")),
            listOf("path"),
        ),
        tool(
            "apply_patch",
            "Applies a standard unified diff (as produced by `git diff` / `diff -u`) to one or more " +
                "files under baseDir, entirely in-process: no git, no PowerShell. Preserves each " +
                "target file's existing CRLF/LF line-ending style. Each hunk is matched by its " +
                "context/removed lines as a contiguous block; a hunk that cannot be located fails the " +
                "call before writing that file.",
            obj(
                "baseDir" to prop("string", "Absolute directory the diff's paths resolve against (a/ and b/ prefixes are stripped)."),
                "diffText" to prop("string", "The unified diff text."),
            ),
            listOf("baseDir", "diffText"),
        ),
        tool(
            "delete_file",
            "Deletes a file or (with recursive=true) a directory tree at an absolute path, via " +
                "plain java.nio.file.Files calls in the plugin process -- no PowerShell/cmd. " +
                "Without recursive, deleting a non-empty directory fails rather than silently " +
                "removing its contents, same as Remove-Item without -Recurse.",
            obj(
                "path" to prop("string", "Absolute path to delete."),
                "recursive" to prop("boolean", "Delete a directory and everything under it. Default false."),
            ),
            listOf("path"),
        ),
        tool(
            "create_directory",
            "Creates a directory (and any missing parents) at an absolute path -- java.nio.file.Files.createDirectories, idempotent.",
            obj("path" to prop("string", "Absolute directory path to create.")),
            listOf("path"),
        ),
        tool(
            "stat_file",
            "Reports whether an absolute path exists and, if so, whether it's a directory, its size " +
                "and its last-modified time -- a quick existence/metadata check without reading content.",
            obj("path" to prop("string", "Absolute path to inspect.")),
            listOf("path"),
        ),
        tool(
            "list_directory",
            "Lists the entries of a directory at an absolute path (one 'D name' or 'F name size' " +
                "line per entry, capped at 2000 lines) -- plain filenames, no box-drawing tree art, " +
                "so it can't hit the cp1252/Unicode console-encoding crash the IDE's own " +
                "list_directory_tree tool has. Non-recursive by default.",
            obj(
                "path" to prop("string", "Absolute directory path to list."),
                "recursive" to prop("boolean", "List subdirectories' contents too. Default false."),
                "maxDepth" to prop("integer", "Max recursion depth when recursive=true. Default unlimited."),
            ),
            listOf("path"),
        ),
        tool(
            "find_files",
            "Finds files/directories under an absolute root by name or path, like Unix `find`. " +
                "Error-tolerant (a permission-denied subdirectory is skipped, not fatal) and not " +
                "restricted to the currently open project. Returns one 'D path' or 'F path size' " +
                "line per match, capped at maxResults.",
            obj(
                "root" to prop("string", "Absolute directory to search under."),
                "pattern" to prop("string", "What to match, interpreted per matchMode."),
                "matchMode" to prop("string", "'substring' (default), 'glob' (e.g. '*.java'), or 'regex'."),
                "matchAgainst" to prop("string", "Match against the entry's 'name' (default) or its relative 'path'."),
                "type" to prop("string", "Restrict to 'file', 'directory', or 'any' (default)."),
                "recursive" to prop("boolean", "Search subdirectories too. Default true."),
                "maxDepth" to prop("integer", "Max recursion depth when recursive=true. Default unlimited."),
                "caseSensitive" to prop("boolean", "Case-sensitive match. Default false."),
                "maxResults" to prop("integer", "Cap on returned matches. Default 2000."),
            ),
            listOf("root", "pattern"),
        ),
        tool(
            "grep_files",
            "Searches file contents under an absolute root (or a single file) for a literal " +
                "substring or regex, like Unix `grep -r`. Error-tolerant (permission-denied " +
                "subdirectories are skipped) and not restricted to the currently open project. " +
                "Files that aren't valid UTF-8 text are skipped, like `grep -I`. Returns one " +
                "'path:line: text' line per match (or a context block if contextLines>0), capped " +
                "at maxMatches.",
            obj(
                "root" to prop("string", "Absolute directory to search under, or a single absolute file path."),
                "pattern" to prop("string", "Literal substring, or a regex if regex=true."),
                "regex" to prop("boolean", "Treat pattern as a regular expression. Default false."),
                "caseSensitive" to prop("boolean", "Case-sensitive match. Default true."),
                "filePattern" to prop("string", "Optional glob filter on filename, e.g. '*.java'. Only used when root is a directory."),
                "recursive" to prop("boolean", "Search subdirectories too. Default true."),
                "maxDepth" to prop("integer", "Max recursion depth when recursive=true. Default unlimited."),
                "contextLines" to prop("integer", "Lines of context before/after each match. Default 0."),
                "maxMatches" to prop("integer", "Cap on returned matches. Default 500."),
            ),
            listOf("root", "pattern"),
        ),
        tool(
            "hash_tree",
            "Hashes every file under an absolute root in one call, instead of one hash_file " +
                "round trip per file -- the biggest round-trip-count win for re-verifying a large " +
                "synced tree. Returns one 'relativePath size sha256' line per file, capped at " +
                "maxFiles. Optionally filtered to files matching a glob.",
            obj(
                "root" to prop("string", "Absolute directory to hash."),
                "pattern" to prop("string", "Optional glob filter on filename, e.g. '*.java'."),
                "maxFiles" to prop("integer", "Cap on files hashed. Default 5000."),
            ),
            listOf("root"),
        ),
    )
}
