package com.vditunnel.bridge

import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.security.MessageDigest
import java.nio.ByteBuffer

/** Frame pack/unpack + zlib codec + CRC. Mirror of host protocol.py. */
object Protocol {
    const val MAGIC_DOWN = 0x5644
    const val MAGIC_UP = 0x4344
    const val VERSION = 1
    const val TYPE_RESP = 0x01
    const val TYPE_HEARTBEAT = 0x02

    private fun crc(b: ByteArray): Long { val c = CRC32(); c.update(b); return c.value }

    fun compress(raw: ByteArray): Pair<Int, ByteArray> {
        val d = Deflater(9); d.setInput(raw); d.finish()
        val buf = ByteArray(raw.size + 64); val out = ArrayList<Byte>()
        while (!d.finished()) { val n = d.deflate(buf); for (i in 0 until n) out.add(buf[i]) }
        val z = out.toByteArray()
        return if (z.size < raw.size) 1 to z else 0 to raw
    }

    fun shaTail(payload: ByteArray): Long {
        val h = MessageDigest.getInstance("SHA-256").digest(payload)
        return ByteBuffer.wrap(h, 0, 4).int.toLong() and 0xFFFFFFFFL
    }

    private fun withCrc(body: ByteArray): ByteArray =
        body + ByteBuffer.allocate(4).putInt(crc(body).toInt()).array()

    fun packResp(genId: Int, symbolId: Int, k: Int, payloadLen: Int, codec: Int,
                 sha: Long, symbol: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(20 + symbol.size)
        bb.putShort(MAGIC_DOWN.toShort()); bb.put(VERSION.toByte()); bb.put(TYPE_RESP.toByte())
        bb.putShort(genId.toShort())
        bb.put(((symbolId shr 16) and 0xFF).toByte()); bb.put(((symbolId shr 8) and 0xFF).toByte())
        bb.put((symbolId and 0xFF).toByte())
        bb.putShort(k.toShort()); bb.putInt(payloadLen); bb.put(codec.toByte()); bb.putInt(sha.toInt())
        bb.put(symbol)
        return withCrc(bb.array())
    }

    fun packHeartbeat(genId: Int, state: Int, rxHash: Long, bridgeVer: Int, schemaHash: Long): ByteArray {
        val bb = ByteBuffer.allocate(16)
        bb.putShort(MAGIC_DOWN.toShort()); bb.put(VERSION.toByte()); bb.put(TYPE_HEARTBEAT.toByte())
        bb.putShort(genId.toShort()); bb.put(state.toByte()); bb.putInt(rxHash.toInt())
        bb.put(bridgeVer.toByte()); bb.putInt(schemaHash.toInt())
        return withCrc(bb.array())
    }

    data class Req(val genId: Int, val seq: Int, val total: Int, val codec: Int, val chunk: ByteArray)

    /** Parse one base64 REQ line typed into the textarea. Returns null on bad frame. */
    fun parseReqLine(line: String): Req? = try {
        val f = Base64.getDecoder().decode(line.trim())
        val body = f.copyOfRange(0, f.size - 4)
        val gotCrc = ByteBuffer.wrap(f, f.size - 4, 4).int.toLong() and 0xFFFFFFFFL
        if (crc(body) != gotCrc) null else {
            val bb = ByteBuffer.wrap(body)
            val magic = bb.short.toInt() and 0xFFFF
            if (magic != MAGIC_UP) null else {
                bb.get() // version
                val gen = bb.short.toInt() and 0xFFFF
                val seq = bb.short.toInt() and 0xFFFF
                val total = bb.short.toInt() and 0xFFFF
                val codec = bb.get().toInt() and 0xFF
                val chunk = ByteArray(bb.remaining()); bb.get(chunk)
                Req(gen, seq, total, codec, chunk)
            }
        }
    } catch (e: Exception) { null }

    /** Equivalent to Python zlib.crc32(chunk, prev): reflected CRC32 seeded with `prev`. */
    fun rollingAck(prev: Long, data: ByteArray): Long {
        // java.util.zip.CRC32 can't seed; use a manual CRC32 with initial value = prev.
        var crc = prev.inv() and 0xFFFFFFFFL
        for (b in data) {
            crc = crc xor (b.toLong() and 0xFF)
            for (i in 0 until 8) crc = if (crc and 1L != 0L) (crc shr 1) xor 0xEDB88320L else crc shr 1
        }
        return crc.inv() and 0xFFFFFFFFL
    }
}
