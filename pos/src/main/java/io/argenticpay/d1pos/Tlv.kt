package io.argenticpay.d1pos

/** BER-TLV mínimo (construcción y parseo plano con descenso en tags constructed). */
object Tlv {
    private const val HEX = "0123456789ABCDEF"

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xFF; sb.append(HEX[v shr 4]).append(HEX[v and 0x0F]) }
        return sb.toString()
    }

    fun bytes(h: String): ByteArray {
        val s = h.replace(" ", "").uppercase()
        require(s.length % 2 == 0) { "hex impar: $h" }
        return ByteArray(s.length / 2) { i -> ((HEX.indexOf(s[2 * i]) shl 4) or HEX.indexOf(s[2 * i + 1])).toByte() }
    }

    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), (n and 0xFF).toByte())
    }

    /** tag en hex ("9F26") + valor */
    fun t(tag: String, value: ByteArray): ByteArray = bytes(tag) + len(value.size) + value
    fun t(tag: String, hexValue: String): ByteArray = t(tag, bytes(hexValue))
    fun cat(vararg parts: ByteArray): ByteArray { var out = ByteArray(0); for (p in parts) out += p; return out }

    /** Parseo plano: devuelve tag(hex) → valor, bajando dentro de los constructed (bit 6 del primer byte). */
    fun parse(data: ByteArray, out: MutableMap<String, ByteArray> = LinkedHashMap()): Map<String, ByteArray> {
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            if (b0 == 0x00 || b0 == 0xFF) { i++; continue }
            var tagEnd = i + 1
            if ((b0 and 0x1F) == 0x1F) { while (tagEnd < data.size && (data[tagEnd].toInt() and 0x80) != 0) tagEnd++; tagEnd++ }
            if (tagEnd > data.size) break
            val tag = hex(data.copyOfRange(i, tagEnd))
            var j = tagEnd
            if (j >= data.size) break
            var l = data[j].toInt() and 0xFF; j++
            if ((l and 0x80) != 0) {
                val n = l and 0x7F; l = 0
                repeat(n) { if (j < data.size) { l = (l shl 8) or (data[j].toInt() and 0xFF); j++ } }
            }
            val end = minOf(j + l, data.size)
            val v = data.copyOfRange(j, end)
            out[tag] = v
            if ((b0 and 0x20) != 0) parse(v, out)   // constructed → bajar
            i = end
        }
        return out
    }

    /** Lista de (tag, length) de un DOL (PDOL/CDOL). */
    fun parseDol(dol: ByteArray): List<Pair<String, Int>> {
        val res = ArrayList<Pair<String, Int>>()
        var i = 0
        while (i < dol.size) {
            val b0 = dol[i].toInt() and 0xFF
            var tagEnd = i + 1
            if ((b0 and 0x1F) == 0x1F) { while (tagEnd < dol.size && (dol[tagEnd].toInt() and 0x80) != 0) tagEnd++; tagEnd++ }
            if (tagEnd >= dol.size) break
            res.add(hex(dol.copyOfRange(i, tagEnd)) to (dol[tagEnd].toInt() and 0xFF))
            i = tagEnd + 1
        }
        return res
    }

    fun bcd(digits: String, bytesLen: Int): ByteArray = bytes(digits.padStart(bytesLen * 2, '0').takeLast(bytesLen * 2))
}
