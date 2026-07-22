package com.vditunnel.bridge

import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicReference

/** Owns the tunnel state machine on the VDI side:
 *  reassemble typed REQ frames -> forward to IDE MCP server -> LT-encode reply into RESP QR frames.
 *  The panel polls [heartbeatImage] and [currentDownlinkImage] to paint. */
class TunnelController(
    private val ide: McpLocalClient,
    private val symbolSize: Int = 512,        // MUST equal host Config.symbol_size
    private val dmax: Int = 8,
    private val qrPx: Int = 720,              // render resolution; panel scales to fit
    private val repairRatio: Double = 0.25,   // extra repair symbols beyond K
) {
    enum class State(val code: Int) { IDLE(0), RECEIVING(1), FORWARDING(2), SENDING(3), ERROR(4) }

    @Volatile var state = State.IDLE
    @Volatile var genId = 0
    @Volatile var rxHash = 0L
    @Volatile var schemaHash = 0L

    private val chunks = HashMap<Int, ByteArray>()   // seq -> chunk
    private var expectedTotal = -1
    private var reqCodec = 0

    // downlink symbols (rendered QR) cycled by the panel
    private val downFrames = AtomicReference<List<BufferedImage>>(emptyList())
    @Volatile private var downIdx = 0

    /** Forwarding happens here, never on the caller's thread -- see [finishRequest].
     *  Single-threaded on purpose: one request is in flight at a time. */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "vdi-tunnel-forward").apply { isDaemon = true }
    }

    // ---- uplink: called per completed textarea line ----
    fun acceptLine(line: String) {
        if (line.trim() == "END") { finishRequest(); return }
        val r = Protocol.parseReqLine(line) ?: return
        if (state != State.RECEIVING || r.genId != genId) {   // new request
            chunks.clear(); rxHash = 0L; genId = r.genId; state = State.RECEIVING
        }
        if (!chunks.containsKey(r.seq)) {
            chunks[r.seq] = r.chunk
            rxHash = Protocol.rollingAck(rxHash, r.chunk)   // ACK surfaced via heartbeat
            expectedTotal = r.total; reqCodec = r.codec
        }
        // Every REQ carries `total`, so the last chunk is self-announcing: finish here and
        // treat END as a fallback. Chunks are ACKed (rxHash) and retried, but END is typed
        // once and never acknowledged -- a dropped keystroke used to truncate it ("E") and
        // park the bridge in RECEIVING until the host's 120s timeout, with the request
        // fully received and never forwarded.
        if (expectedTotal in 1..chunks.size) finishRequest()
    }

    private fun finishRequest() {
        // Idempotent: END may arrive after the auto-finish above, or be retyped by the
        // host. Without this a second END re-forwards the last request to the IDE, which
        // would silently re-apply a mutation.
        if (state != State.RECEIVING) return
        if (expectedTotal <= 0 || chunks.size < expectedTotal) return
        state = State.FORWARDING
        // Snapshot the reassembled request on the caller's thread: `chunks` is only ever
        // touched from there, so the worker never sees it and a request arriving mid-flight
        // can reset it safely.
        val comp = ByteArray(chunks.values.sumOf { it.size }).also {
            var o = 0; for (s in 0 until expectedTotal) { val c = chunks[s]!!; c.copyInto(it, o); o += c.size }
        }
        val codec = reqCodec
        // acceptLine runs on the Swing EDT (panel DocumentListener), and ide.call blocks for
        // as long as the IDE takes. Doing that inline froze the entire IDE for the duration
        // of every tool call, and -- once the last chunk started the forward instead of END
        // -- also stalled the repaint carrying rxHash back to the host, so the 1.5s ACK
        // window for the final chunk could never be met.
        worker.execute {
            val reply = try {
                ide.call(String(inflateIfNeeded(codec, comp), Charsets.UTF_8))
            } catch (e: Exception) { errorReply(e.message) }
            sendResponse(reply.toByteArray(Charsets.UTF_8))
        }
    }

    private fun inflateIfNeeded(codec: Int, data: ByteArray): ByteArray {
        if (codec == 0) return data
        val inf = java.util.zip.Inflater(); inf.setInput(data)
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }

    // ---- downlink: LT-encode reply into a cycling QR animation ----
    private fun sendResponse(payloadRaw: ByteArray) {
        val (codec, payload) = Protocol.compress(payloadRaw)
        val k = (payload.size + symbolSize - 1) / symbolSize
        val nSymbols = k + Math.ceil(k * repairRatio).toInt() + 2
        val sha = Protocol.shaTail(payload)
        val imgs = ArrayList<BufferedImage>(nSymbols)
        for (s in 0 until nSymbols) {
            val symbol = Fountain.encodeSymbol(payload, s, symbolSize, dmax)
            val frame = Protocol.packResp(genId, s, k, payload.size, codec, sha, symbol)
            imgs.add(QrRenderer.render(frame, qrPx))
        }
        downFrames.set(imgs); downIdx = 0
        state = State.SENDING
    }

    private fun errorReply(msg: String?): String =
        """{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":${'"'}${msg ?: "ide error"}${'"'}}}"""

    // ---- panel accessors ----
    fun currentDownlinkImage(): BufferedImage? {
        val f = downFrames.get(); if (f.isEmpty()) return null
        val img = f[downIdx % f.size]; downIdx++   // advance each paint tick
        return img
    }

    fun heartbeatImage(qrPx: Int): BufferedImage {
        val frame = Protocol.packHeartbeat(genId, state.code, rxHash, 1, schemaHash)
        return QrRenderer.render(frame, qrPx)
    }
}
