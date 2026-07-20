package com.vditunnel.bridge

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** MCP client to the IDE's built-in server on localhost (streamable-HTTP transport,
 *  e.g. http://127.0.0.1:64342/stream — the IDE also offers /sse, which needs an
 *  EventSource handshake instead and is NOT supported here).
 *
 *  The host proxy answers `initialize` locally and only forwards later messages, so this
 *  client performs its own handshake lazily: initialize -> capture Mcp-Session-Id ->
 *  notifications/initialized. Responses may arrive SSE-framed even on POST; the JSON-RPC
 *  reply is then the last `data:` payload carrying a result or error. */
class McpLocalClient(private val baseUrl: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Volatile private var initialized = false
    @Volatile private var sessionId: String? = null
    @Volatile private var protocolVersion = "2025-06-18"

    /** Send one JSON-RPC message string, return the response string. Streamable-HTTP
     *  sessions are ephemeral: if the IDE has expired ours (HTTP 404 / "session not
     *  found"), drop it, re-handshake once, and resend. */
    fun call(mcpJson: String): String {
        ensureSession()
        var resp = post(mcpJson)
        if (sessionLost(resp)) {
            synchronized(this) { initialized = false; sessionId = null }
            ensureSession()
            resp = post(mcpJson)
        }
        return extractReply(resp)
    }

    private fun sessionLost(resp: HttpResponse<String>): Boolean {
        if (resp.statusCode() == 404) return true
        val body = resp.body() ?: return false
        return body.contains("session not found", ignoreCase = true) ||
               body.contains("session has been closed", ignoreCase = true)
    }

    @Synchronized
    private fun ensureSession() {
        if (initialized) return
        val init = """{"jsonrpc":"2.0","id":"bridge-init","method":"initialize","params":{"protocolVersion":"$protocolVersion","capabilities":{},"clientInfo":{"name":"vdi-tunnel-bridge","version":"0.1"}}}"""
        val resp = post(init)
        resp.headers().firstValue("mcp-session-id").ifPresent { sessionId = it }
        Regex("\"protocolVersion\"\\s*:\\s*\"([^\"]+)\"").find(extractReply(resp))
            ?.let { protocolVersion = it.groupValues[1] }
        initialized = true   // before the notification: it goes through post() too
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    }

    private fun post(body: String): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create(baseUrl))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        sessionId?.let { b.header("Mcp-Session-Id", it) }
        if (initialized) b.header("MCP-Protocol-Version", protocolVersion)
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun extractReply(resp: HttpResponse<String>): String {
        val body = resp.body() ?: ""
        val isSse = resp.headers().firstValue("content-type").orElse("").contains("text/event-stream")
        if (!isSse) return body
        // SSE framing: events separated by blank lines; an event's payload is its
        // concatenated `data:` lines. The reply is the last payload with result/error.
        val payloads = body.split("\n\n", "\r\n\r\n").mapNotNull { event ->
            val data = event.lines()
                .filter { it.startsWith("data:") }
                .joinToString("\n") { it.removePrefix("data:").trim() }
            data.ifBlank { null }
        }
        return payloads.lastOrNull { it.contains("\"result\"") || it.contains("\"error\"") }
            ?: payloads.lastOrNull() ?: body
    }
}
