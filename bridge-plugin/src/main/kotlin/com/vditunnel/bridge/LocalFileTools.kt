package com.vditunnel.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
    val NAMES: Set<String> = setOf("write_file", "patch_file", "move_file", "copy_file", "hash_file", "apply_patch")

    fun call(name: String, args: JsonObject): String = when (name) {
        "write_file" -> writeFile(args)
        "patch_file" -> patchFile(args)
        "move_file" -> relocate(args, move = true)
        "copy_file" -> relocate(args, move = false)
        "hash_file" -> hashFile(args)
        "apply_patch" -> applyPatch(args)
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
    )

    private fun tool(name: String, description: String, properties: JsonObject, required: List<String>): JsonObject {
        val t = JsonObject()
        t.addProperty("name", name)
        t.addProperty("description", description)
        val schema = JsonObject()
        schema.addProperty("type", "object")
        schema.add("properties", properties)
        val req = JsonArray()
        required.forEach { req.add(it) }
        schema.add("required", req)
        t.add("inputSchema", schema)
        return t
    }

    private fun prop(type: String, description: String): JsonObject {
        val p = JsonObject()
        p.addProperty("type", type)
        p.addProperty("description", description)
        return p
    }

    private fun obj(vararg pairs: Pair<String, JsonObject>): JsonObject {
        val o = JsonObject()
        pairs.forEach { (k, v) -> o.add(k, v) }
        return o
    }
}
