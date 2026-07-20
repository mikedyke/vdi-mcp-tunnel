package com.vditunnel.bridge

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** MCP client to the IDE's built-in server on localhost.
 *  Skeleton uses a plain JSON-RPC POST (HTTP-stream style). If the confirmed transport
 *  is SSE, replace [call] with an EventSource handshake + POST (see open question #1). */
class McpLocalClient(private val baseUrl: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    /** Send one JSON-RPC message string, return the response string. */
    fun call(mcpJson: String): String {
        val req = HttpRequest.newBuilder(URI.create(baseUrl))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(mcpJson))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        // TODO: if server replies text/event-stream, extract the JSON-RPC data: line(s).
        return resp.body()
    }
}
