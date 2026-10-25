package io.argenticpay.d1wallet

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Construcción de las respuestas EMV del teléfono (perfil demo "D1 NFC Wallet", estilo qVSDC). */
object Emv {
    const val PPSE = "325041592E5359532E4444463031"
    const val AID_VISA = "A0000000031010"
    const val AID_MC = "A0000000041010"
    val SW_OK = Tlv.bytes("9000")
    val SW_NOT_FOUND = Tlv.bytes("6A82")
    val SW_NOT_ALLOWED = Tlv.bytes("6986")
    val SW_INS_NOT_SUPPORTED = Tlv.bytes("6D00")
    val SW_WRONG_LENGTH = Tlv.bytes("6700")

    // PDOL que le pedimos al terminal: TTQ, monto, otro monto, país, TVR, moneda, fecha, tipo, UN
    const val PDOL = "9F66049F02069F03069F1A0295055F2A029A039C019F3704"

    val CURRENCY_ALPHA = mapOf(
        "0840" to "USD", "0152" to "CLP", "0032" to "ARS", "0484" to "MXN", "0986" to "BRL", "0858" to "UYU",
        "0600" to "PYG", "0604" to "PEN", "0170" to "COP", "0978" to "EUR", "0068" to "BOB", "0188" to "CRC",
        "0320" to "GTQ", "0340" to "HNL", "0558" to "NIO", "0214" to "DOP", "0928" to "VES",
    )

    fun aidFor(scheme: String) = if (scheme == "VISA") AID_VISA else AID_MC
    fun label(scheme: String) = if (scheme == "VISA") "VISA CREDIT" else "MASTERCARD"

    fun ppseFci(scheme: String): ByteArray {
        val entry = Tlv.t("61", Tlv.cat(Tlv.t("4F", aidFor(scheme)), Tlv.t("50", label(scheme).toByteArray()), Tlv.t("87", "01")))
        val fci = Tlv.t("6F", Tlv.cat(Tlv.t("84", PPSE), Tlv.t("A5", Tlv.t("BF0C", entry))))
        return fci + SW_OK
    }

    fun aidFci(scheme: String): ByteArray {
        val a5 = Tlv.cat(
            Tlv.t("50", label(scheme).toByteArray()),
            Tlv.t("87", "01"),
            Tlv.t("9F38", PDOL),
            Tlv.t("5F2D", "esen".toByteArray()),
            Tlv.t("9F11", "01"),
        )
        return Tlv.t("6F", Tlv.cat(Tlv.t("84", aidFor(scheme)), Tlv.t("A5", a5))) + SW_OK
    }

    /** Datos del PDOL que mandó el terminal en el GPO (83 len data). */
    data class GpoReq(val amountMinor: Long, val currencyNum: String, val un: String, val ttq: String)

    fun parseGpo(apdu: ByteArray): GpoReq? {
        if (apdu.size < 5) return null
        val lc = apdu[4].toInt() and 0xFF
        val body = apdu.copyOfRange(5, minOf(5 + lc, apdu.size))
        val m = Tlv.parse(body)
        val data = m["83"] ?: return null
        // orden fijo según nuestro PDOL: TTQ(4) amount(6) other(6) country(2) TVR(5) currency(2) date(3) type(1) UN(4)
        if (data.size < 33) return GpoReq(0, "0840", "00000000", "")
        val ttq = Tlv.hex(data.copyOfRange(0, 4))
        val amount = Tlv.hex(data.copyOfRange(4, 10)).toLong()
        val cur = Tlv.hex(data.copyOfRange(23, 25))
        val un = Tlv.hex(data.copyOfRange(29, 33))
        return GpoReq(amount, cur, un, ttq)
    }

    /** Track 2 equivalent: DPAN + 'D' + YYMM + service 201 + discretionary. */
    fun track2(dpan: String, expMMYY: String): ByteArray {
        val yy = expMMYY.takeLast(2); val mm = expMMYY.take(2)
        var s = dpan + "D" + yy + mm + "2010000000000"
        if (s.length % 2 == 1) s += "F"
        return Tlv.bytes(s)
    }

    /** ARQC demo = HMAC-SHA256(LUK, "tokenRef|ATC4|amountMinor|currencyAlpha|UN")[:8 bytes] — idéntico al backend. */
    fun cryptogram(lukHex: String, tokenRef: String, atc: Int, amountMinor: Long, currencyAlpha: String, un: String): ByteArray {
        val msg = "$tokenRef|${"%04d".format(atc)}|$amountMinor|$currencyAlpha|$un"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Tlv.bytes(lukHex), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray()).copyOfRange(0, 8)
    }

    /** Respuesta GPO con CDCVM NO realizado: el terminal muestra "vea su teléfono". */
    fun gpoCdcvmRequired(atc: Int): ByteArray {
        val body = Tlv.cat(Tlv.t("82", "0000"), Tlv.t("9F36", "%04X".format(atc)), Tlv.t("9F6C", "0100"))
        return Tlv.t("77", body) + SW_OK
    }

    /** Respuesta GPO completa con criptograma (CDCVM realizado). */
    fun gpoResponse(dpan: String, exp: String, atc: Int, arqc: ByteArray, un: String): ByteArray {
        val body = Tlv.cat(
            Tlv.t("82", "0000"),
            Tlv.t("57", track2(dpan, exp)),
            Tlv.t("5F34", "01"),
            Tlv.t("9F10", "06010A03A00000"),
            Tlv.t("9F26", arqc),
            Tlv.t("9F27", "80"),
            Tlv.t("9F36", "%04X".format(atc)),
            Tlv.t("9F37", un.ifEmpty { "00000000" }),
            Tlv.t("9F6C", "0180"),
            Tlv.t("9F6E", "238C0000"),
        )
        return Tlv.t("77", body) + SW_OK
    }

    fun randomHex(n: Int): String { val b = ByteArray(n); SecureRandom().nextBytes(b); return Tlv.hex(b) }
}
