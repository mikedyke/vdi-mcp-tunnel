package com.vditunnel.bridge

/** LT fountain ENCODER. MUST match host fountain.py bit-for-bit (same LCG, same neighbours). */
object Fountain {
    private const val GOLDEN = -0x61c8864680b583ebL   // 0x9E3779B97F4A7C15 as signed Long
    private const val MUL = 6364136223846793005L
    private const val INC = 1442695040888963407L

    private fun lcg(x: ULong): ULong = (x * MUL.toULong() + INC.toULong())

    /** Sorted source indices this symbol XORs together. Identical to Python _neighbors. */
    fun neighbors(symbolId: Int, k: Int, dmax: Int): List<Int> {
        if (symbolId < k) return listOf(symbolId)
        if (k <= 1) return listOf(0)
        var state = lcg(symbolId.toULong() xor GOLDEN.toULong())
        val hi = minOf(k, dmax)
        var d = if (hi > 2) 2 + ((state shr 33) % (hi - 1).toULong()).toInt() else 2
        d = minOf(d, k)
        val ns = LinkedHashSet<Int>()
        while (ns.size < d) {
            state = lcg(state)
            ns.add(((state shr 33) % k.toULong()).toInt())
        }
        return ns.sorted()
    }

    fun encodeSymbol(payload: ByteArray, symbolId: Int, symbolSize: Int, dmax: Int): ByteArray {
        val k = (payload.size + symbolSize - 1) / symbolSize
        val out = ByteArray(symbolSize)
        for (idx in neighbors(symbolId, k, dmax)) {
            val base = idx * symbolSize
            for (j in 0 until symbolSize) {
                val src = base + j
                if (src < payload.size) out[j] = (out[j].toInt() xor payload[src].toInt()).toByte()
            }
        }
        return out
    }
}
