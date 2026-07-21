package com.vditunnel.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64
import java.util.zip.Inflater

/**
 * Bridge half of the cross-language codec parity test.
 *
 * Checks Protocol.kt / Fountain.kt against vectors/parity-vectors.txt -- the same file
 * host/tests/test_parity.py checks the Python implementation against. A framing, PRNG or
 * codec change that lands in only one language fails on one side or the other.
 *
 * The vectors are generated from the host implementation by
 * host/tools/gen_parity_vectors.py; regenerating them is a deliberate protocol change and
 * must come with a PROTOCOL.md update.
 */
class ParityTest {

    private data class Record(val name: String, val args: List<String>, val expected: List<String>)

    private val records: List<Record> = load()

    private fun load(): List<Record> {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var file: File? = null
        while (dir != null && file == null) {
            val candidate = File(dir, "vectors/parity-vectors.txt")
            if (candidate.isFile) file = candidate else dir = dir.parentFile
        }
        checkNotNull(file) { "vectors/parity-vectors.txt not found above ${System.getProperty("user.dir")}" }
        val out = file.readLines().mapIndexedNotNull { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            val at = line.indexOf(" => ")
            check(at > 0) { "${file.name}:${i + 1}: no ' => ' in $line" }
            val lhs = line.substring(0, at).split(" ")
            Record(lhs[0], lhs.drop(1), line.substring(at + 4).split(" "))
        }
        check(out.isNotEmpty()) { "no vectors in ${file.path}" }
        return out
    }

    private fun of(name: String) = records.filter { it.name == name }

    private fun unhex(s: String): ByteArray =
        if (s == "-") ByteArray(0)
        else ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun inflate(z: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(z)
        val buf = ByteArray(4096)
        val out = java.io.ByteArrayOutputStream()
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && inf.needsInput()) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    @Test
    fun `fountain neighbours match the host`() {
        val rows = of("NEIGHBORS")
        assertTrue(rows.size >= 9, "expected at least the nine PROTOCOL.md vectors, got ${rows.size}")
        for (r in rows) {
            val (sid, k, dmax) = r.args.map { it.toInt() }
            val want = r.expected[0].split(",").map { it.toInt() }
            assertEquals(want, Fountain.neighbors(sid, k, dmax), "neighbors($sid, k=$k, dmax=$dmax)")
        }
    }

    @Test
    fun `encoded symbols match the host`() {
        val rows = of("SYMBOL")
        assertTrue(rows.isNotEmpty())
        for (r in rows) {
            val payload = unhex(r.args[0])
            val (sid, size, dmax) = r.args.drop(1).map { it.toInt() }
            assertEquals(hex(unhex(r.expected[0])), hex(Fountain.encodeSymbol(payload, sid, size, dmax)),
                "encodeSymbol(symbolId=$sid, symbolSize=$size)")
        }
    }

    @Test
    fun `RESP frames are byte-identical to the host`() {
        val rows = of("RESP")
        assertTrue(rows.isNotEmpty())
        for (r in rows) {
            val gen = r.args[0].toInt()
            val sid = r.args[1].toInt()
            val k = r.args[2].toInt()
            val payloadLen = r.args[3].toLong().toInt()   // u32 on the wire; may exceed Int
            val codec = r.args[4].toInt()
            val sha = r.args[5].toLong(16)
            val symbol = unhex(r.args[6])
            assertEquals(r.expected[0], hex(Protocol.packResp(gen, sid, k, payloadLen, codec, sha, symbol)),
                "packResp(genId=$gen, symbolId=$sid)")
        }
    }

    @Test
    fun `HEARTBEAT frames are byte-identical to the host`() {
        val rows = of("HEARTBEAT")
        assertTrue(rows.isNotEmpty())
        for (r in rows) {
            val gen = r.args[0].toInt()
            val state = r.args[1].toInt()
            val rx = r.args[2].toLong(16)
            val bver = r.args[3].toInt()
            val schema = r.args[4].toLong(16)
            assertEquals(r.expected[0], hex(Protocol.packHeartbeat(gen, state, rx, bver, schema)),
                "packHeartbeat(genId=$gen, state=$state)")
        }
    }

    @Test
    fun `REQ lines typed by the host parse back to the same fields`() {
        val rows = of("REQ")
        assertTrue(rows.isNotEmpty())
        for (r in rows) {
            val line = r.expected[0]
            val got = Protocol.parseReqLine(line)
            assertNotNull(got, "parseReqLine returned null for $line")
            got!!
            assertEquals(r.args[0].toInt(), got.genId, "genId")
            assertEquals(r.args[1].toInt(), got.seq, "seq")
            assertEquals(r.args[2].toInt(), got.total, "total")
            assertEquals(r.args[3].toInt(), got.codec, "codec")
            assertEquals(r.args[4].let { if (it == "-") "" else it }, hex(got.chunk), "chunk")
        }
    }

    @Test
    fun `a REQ line with a flipped bit is rejected`() {
        val line = of("REQ").first { unhex(it.args[4]).isNotEmpty() }.expected[0]
        val frame = Base64.getDecoder().decode(line)
        frame[frame.size - 6] = (frame[frame.size - 6].toInt() xor 0x01).toByte()
        assertNull(Protocol.parseReqLine(Base64.getEncoder().encodeToString(frame)),
            "CRC guard let a corrupted chunk through")
    }

    @Test
    fun `rolling ACK matches Python zlib crc32 with a seed`() {
        val rows = of("ACK")
        assertTrue(rows.isNotEmpty())
        for (r in rows) {
            val prev = r.args[0].toLong(16)
            val chunk = unhex(r.args[1])
            assertEquals(r.expected[0], "%08x".format(Protocol.rollingAck(prev, chunk)),
                "rollingAck(prev=${r.args[0]})")
        }
    }

    @Test
    fun `sha tail matches the host`() {
        val rows = of("SHATAIL")
        assertTrue(rows.isNotEmpty())
        for (r in rows) {
            assertEquals(r.expected[0], "%08x".format(Protocol.shaTail(unhex(r.args[0]))),
                "shaTail(${r.args[0].take(16)})")
        }
    }

    /**
     * Deflate output is not required to be byte-identical across zlib implementations, so
     * this asserts the two things that actually have to hold: both sides pick the same
     * codec for the same input, and each can inflate the other's bytes.
     */
    @Test
    fun `codec choice matches and each side inflates the other`() {
        val rows = of("ZLIB")
        assertTrue(rows.isNotEmpty())
        for (r in rows) {
            val raw = unhex(r.args[0])
            val wantCodec = r.expected[0].toInt()
            val hostBytes = unhex(r.expected[1])
            val (codec, ours) = Protocol.compress(raw)
            assertEquals(wantCodec, codec, "codec choice for ${r.args[0].take(16)}")
            if (codec == 0) {
                assertEquals(hex(raw), hex(ours), "codec 0 must pass the payload through untouched")
                assertEquals(hex(raw), hex(hostBytes))
            } else {
                assertEquals(hex(raw), hex(inflate(hostBytes)), "bridge cannot inflate the host's bytes")
                assertEquals(hex(raw), hex(inflate(ours)), "our own deflate does not round-trip")
                assertTrue(ours.size < raw.size, "codec 1 must actually be smaller")
            }
        }
    }
}
