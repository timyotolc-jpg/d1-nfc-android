package io.argenticpay.d1pos

import android.nfc.tech.IsoDep
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lector contactless mínimo (lado terminal): SELECT PPSE → SELECT AID → GPO (+ READ RECORD / GENERATE AC si hace falta).
 * Funciona con el D1 Wallet (que devuelve todo en el GPO) y lee también tarjetas reales (que después el backend rechaza
 * con "token desconocido", lo cual es correcto: no son nuestras).
 */
class EmvReader(private val amountMinor: Long, private val currencyNum: String, private val countryNum: String) {

    enum class Kind { NO_CARD, SEE_PHONE, DATA, ERROR }

    data class Result(
        val kind: Kind,
        val aid: String = "",
        val dpan: String = "",
        val exp: String = "",
        val arqc: String = "",
        val atc: Int = 0,
        val un: String = "",
        val ctq: String = "",
        val iad: String = "",
        val track2: String = "",
        val log: List<String> = emptyList(),
        val error: String = "",
    )

    private val log = ArrayList<String>()
    val un: String = Tlv.hex(ByteArray(4).also { SecureRandom().nextBytes(it) })

    private fun tx(iso: IsoDep, apdu: ByteArray, name: String): Pair<Map<String, ByteArray>, String> {
        log.add("→ $name " + Tlv.hex(apdu))
        val r = iso.transceive(apdu)
        val sw = if (r.size >= 2) Tlv.hex(r.copyOfRange(r.size - 2, r.size)) else "0000"
        log.add("← $sw " + Tlv.hex(r).take(96))
        val body = if (r.size > 2) r.copyOfRange(0, r.size - 2) else ByteArray(0)
        return Tlv.parse(body) to sw
    }

    private fun dolData(dol: ByteArray): ByteArray {
        val today = SimpleDateFormat("yyMMdd", Locale.US).format(Date())
        val now = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        var out = ByteArray(0)
        for ((tag, len) in Tlv.parseDol(dol)) {
            val v: ByteArray = when (tag) {
                "9F66" -> Tlv.bytes("36204000")                       // TTQ: contactless EMV, online capable, CDCVM supported
                "9F02" -> Tlv.bcd(amountMinor.toString(), 6)
                "9F03" -> ByteArray(6)
                "9F1A" -> Tlv.bytes(countryNum.padStart(4, '0'))
                "95" -> ByteArray(5)
                "5F2A" -> Tlv.bytes(currencyNum.padStart(4, '0'))
                "9A" -> Tlv.bytes(today)
                "9C" -> Tlv.bytes("00")
                "9F37" -> Tlv.bytes(un)
                "9F35" -> Tlv.bytes("22")
                "9F21" -> Tlv.bytes(now)
                "9F33" -> Tlv.bytes("E0F8C8")
                "9F34" -> Tlv.bytes("1F0302")
                "9F40" -> Tlv.bytes("6000F0A001")
                "9F1E" -> "D1POS041".toByteArray()
                "9F53" -> Tlv.bytes("52")
                "9F7A" -> Tlv.bytes("01")
                else -> ByteArray(len)
            }
            out += if (v.size >= len) v.copyOfRange(v.size - len, v.size) else ByteArray(len - v.size) + v
        }
        return out
    }

    fun run(iso: IsoDep): Result {
        return try {
            iso.timeout = 4000
            // 1) PPSE
            val (ppse, sw1) = tx(iso, Tlv.bytes("00A404000E") + Tlv.bytes(PPSE) + Tlv.bytes("00"), "SELECT PPSE")
            if (sw1 != "9000") return Result(Kind.NO_CARD, log = log, error = "PPSE $sw1")
            val aidB = ppse["4F"] ?: return Result(Kind.NO_CARD, log = log, error = "sin AID")
            val aid = Tlv.hex(aidB)
            // 2) AID
            val (fci, sw2) = tx(iso, Tlv.bytes("00A40400") + byteArrayOf(aidB.size.toByte()) + aidB + Tlv.bytes("00"), "SELECT AID")
            if (sw2 != "9000") return Result(Kind.NO_CARD, aid = aid, log = log, error = "AID $sw2")
            val pdol = fci["9F38"] ?: ByteArray(0)
            // 3) GPO
            val pd = dolData(pdol)
            val gpoData = Tlv.t("83", pd)
            val (gpo, sw3) = tx(iso, Tlv.bytes("80A80000") + byteArrayOf(gpoData.size.toByte()) + gpoData + Tlv.bytes("00"), "GPO")
            if (sw3 != "9000") return Result(Kind.ERROR, aid = aid, log = log, error = "GPO $sw3")
            val all = LinkedHashMap<String, ByteArray>(gpo)
            // formato 1 (tag 80): AIP(2) + AFL
            gpo["80"]?.let { f1 -> if (f1.size > 2) { all["82"] = f1.copyOfRange(0, 2); all["94"] = f1.copyOfRange(2, f1.size) } }
            // 4) ¿faltan datos? → READ RECORD según AFL
            if (all["57"] == null || all["9F26"] == null) {
                val afl = all["94"]
                if (afl != null && all["57"] == null) {
                    var i = 0
                    while (i + 3 < afl.size) {
                        val sfi = (afl[i].toInt() and 0xF8) shr 3; val first = afl[i + 1].toInt() and 0xFF; val last = afl[i + 2].toInt() and 0xFF
                        for (rec in first..last) {
                            val (r, sw) = tx(iso, byteArrayOf(0x00, 0xB2.toByte(), rec.toByte(), ((sfi shl 3) or 4).toByte(), 0x00), "READ RECORD sfi=$sfi rec=$rec")
                            if (sw == "9000") for ((k, v) in r) if (k !in all) all[k] = v
                        }
                        i += 4
                    }
                }
                // GENERATE AC (M/Chip): pedimos ARQC
                if (all["9F26"] == null && all["8C"] != null) {
                    val cd = dolData(all["8C"]!!)
                    val (ac, sw) = tx(iso, Tlv.bytes("80AE8000") + byteArrayOf(cd.size.toByte()) + cd + Tlv.bytes("00"), "GENERATE AC")
                    if (sw == "9000") { for ((k, v) in ac) all[k] = v; ac["80"]?.let { f -> if (f.size >= 11) { all["9F27"] = f.copyOfRange(0, 1); all["9F36"] = f.copyOfRange(1, 3); all["9F26"] = f.copyOfRange(3, 11) } } }
                }
            }
            val ctq = all["9F6C"]?.let { Tlv.hex(it) } ?: ""
            if (all["9F26"] == null && all["57"] == null) {
                return if (ctq.isNotEmpty()) Result(Kind.SEE_PHONE, aid = aid, ctq = ctq, un = un, log = log)
                else Result(Kind.ERROR, aid = aid, log = log, error = "sin criptograma")
            }
            val t2 = all["57"]?.let { Tlv.hex(it) } ?: ""
            val dpan = t2.substringBefore("D").filter { it.isDigit() }
            val ym = t2.substringAfter("D", "").take(4)
            val exp = if (ym.length == 4) ym.substring(2, 4) + "/" + ym.substring(0, 2) else ""
            val atc = all["9F36"]?.let { Tlv.hex(it).toInt(16) } ?: 0
            Result(
                Kind.DATA, aid = aid, dpan = dpan, exp = exp, arqc = all["9F26"]?.let { Tlv.hex(it) } ?: "", atc = atc, un = un,
                ctq = ctq, iad = all["9F10"]?.let { Tlv.hex(it) } ?: "", track2 = t2, log = log,
            )
        } catch (e: Exception) {
            Result(Kind.ERROR, log = log, error = e.toString())
        } finally {
            runCatching { iso.close() }
        }
    }

    companion object {
        const val PPSE = "325041592E5359532E4444463031"
        val CURRENCY = mapOf("USD" to "0840", "CLP" to "0152", "ARS" to "0032", "MXN" to "0484", "BRL" to "0986", "UYU" to "0858", "PYG" to "0600", "PEN" to "0604", "COP" to "0170", "EUR" to "0978")
        val COUNTRY = mapOf("USD" to "0840", "CLP" to "0152", "ARS" to "0032", "MXN" to "0484", "BRL" to "0076", "UYU" to "0858", "PYG" to "0600", "PEN" to "0604", "COP" to "0170", "EUR" to "0724")
    }
}
