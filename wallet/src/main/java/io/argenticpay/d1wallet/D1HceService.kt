package io.argenticpay.d1wallet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * El teléfono como tarjeta: responde los APDU del POS (SELECT PPSE / SELECT AID / GPO / READ RECORD / GENERATE AC).
 * Espejo (simplificado) de lo que hace AsyncHCEService del NFC Wallet SDK:
 *  - sin token digitalizado → 6A82 (el POS dice "tarjeta no reconocida")
 *  - CDCVM no realizado → respuesta "see phone" + evento authRequired (la app pide biometría; 2.º toque)
 *  - CDCVM ok → ARQC con una LUK de un solo uso + evento transactionCompleted; replenishment si quedan < 2
 */
class D1HceService : HostApduService() {

    private lateinit var store: WalletStore
    private var selected: String = ""
    private var lastReq: Emv.GpoReq? = null
    private var lastArqc: ByteArray = ByteArray(8)

    override fun onCreate() {
        super.onCreate()
        store = WalletStore(this)
        Relay.init(this)
    }

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        if (apdu.size < 4) return Emv.SW_WRONG_LENGTH
        val cla = apdu[0].toInt() and 0xFF
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val hex = Tlv.hex(apdu)
        return try {
            when {
                ins == 0xA4 && p1 == 0x04 -> select(apdu, hex)
                cla == 0x80 && ins == 0xA8 -> gpo(apdu, hex)
                ins == 0xB2 -> readRecord(hex)
                cla == 0x80 && ins == 0xAE -> generateAc(hex)
                cla == 0x80 && ins == 0xCA -> Emv.SW_NOT_FOUND
                else -> { log("C-APDU ?", hex); Emv.SW_INS_NOT_SUPPORTED }
            }
        } catch (e: Exception) {
            log("error", e.toString()); Emv.SW_NOT_ALLOWED
        }
    }

    private fun select(apdu: ByteArray, hex: String): ByteArray {
        val lc = apdu[4].toInt() and 0xFF
        val name = Tlv.hex(apdu.copyOfRange(5, minOf(5 + lc, apdu.size)))
        return when (name) {
            Emv.PPSE -> {
                NfcEvents.emit("transactionStarted") { put("apdu", "SELECT PPSE"); put("active", store.active) }
                if (!store.active) { log("SELECT PPSE → 6A82 (sin tarjeta digitalizada)", hex); NfcEvents.emit("noCard"); return Emv.SW_NOT_FOUND }
                selected = "PPSE"
                log("SELECT PPSE → FCI con AID " + Emv.aidFor(store.scheme), hex)
                Emv.ppseFci(store.scheme)
            }
            Emv.AID_VISA, Emv.AID_MC, "A0000000032010", "A0000000043060" -> {
                if (!store.active) return Emv.SW_NOT_FOUND
                selected = name
                log("SELECT AID $name → FCI + PDOL", hex)
                Emv.aidFci(store.scheme)
            }
            else -> { log("SELECT $name → 6A82", hex); Emv.SW_NOT_FOUND }
        }
    }

    private fun gpo(apdu: ByteArray, hex: String): ByteArray {
        if (!store.active) return Emv.SW_NOT_FOUND
        val req = Emv.parseGpo(apdu) ?: return Emv.SW_WRONG_LENGTH
        lastReq = req
        val currency = Emv.CURRENCY_ALPHA[req.currencyNum] ?: "USD"
        // 1 toque: teléfono desbloqueado = CDCVM ("device unlock", como el SDK); 2 toques: biometría en cada tap
        val cdcvmOk = store.cdcvmValid() || (store.tapMode == 1 && HceUtil.unlocked(this))
        if (!cdcvmOk) {
            log("GPO → CDCVM requerido (9F6C 01 00) · see phone", hex)
            NfcEvents.emit("authRequired") { put("amount", req.amountMinor); put("currency", currency); put("un", req.un); put("ttq", req.ttq) }
            vibrate(60)
            notifyAuth(req.amountMinor, currency)
            return Emv.gpoCdcvmRequired(store.atc + 1)
        }
        val luk = store.takeLuk()
        if (luk == null) {
            log("GPO → sin LUK disponibles (replenishment pendiente)", hex)
            NfcEvents.emit("noKeys")
            return Emv.SW_NOT_ALLOWED
        }
        val (atc, key) = luk
        val arqc = Emv.cryptogram(key, store.tokenRef, atc, req.amountMinor, currency, req.un)
        lastArqc = arqc
        store.clearPreauth()   // la credencial pre-autenticada se consume en una transacción (modo 2 toques)
        val left = store.luksLeft()
        log("GPO → ARQC " + Tlv.hex(arqc) + " · ATC " + "%04d".format(atc) + " · LUK restantes " + left, hex)
        NfcEvents.emit("transactionCompleted") {
            put("amount", req.amountMinor); put("currency", currency); put("un", req.un)
            put("atc", atc); put("arqc", Tlv.hex(arqc)); put("luksLeft", left); put("last4", store.last4); put("scheme", store.scheme)
        }
        // evento "tap" de la sesión Live (lo espeja el POS de pantalla del proyector)
        Relay.post("tap", JSONObject().put("atc", atc).put("arqc", Tlv.hex(arqc)).put("amount", req.amountMinor).put("currency", currency).put("last4", store.last4).put("device", Build.MODEL))
        vibrate(120)
        if (left < 2) replenishInBackground(left)
        return Emv.gpoResponse(store.dpan, store.exp, atc, arqc, req.un)
    }

    private fun readRecord(hex: String): ByteArray {
        // Nuestro perfil manda todo en el GPO; un READ RECORD devuelve el Track 2 por compatibilidad con kernels que lo piden.
        if (!store.active) return Emv.SW_NOT_FOUND
        log("READ RECORD → 70 { 57 }", hex)
        return Tlv.t("70", Tlv.t("57", Emv.track2(store.dpan, store.exp))) + Emv.SW_OK
    }

    private fun generateAc(hex: String): ByteArray {
        // Kernels tipo M/Chip piden GENERATE AC después del GPO: devolvemos el mismo ARQC del último GPO (ya consumió la LUK).
        if (lastReq == null) return Emv.SW_NOT_ALLOWED
        log("GENERATE AC → ARQC (mismo criptograma del GPO)", hex)
        val atc = store.atc
        return Tlv.t("77", Tlv.cat(Tlv.t("9F27", "80"), Tlv.t("9F36", "%04X".format(atc)), Tlv.t("9F26", lastArqc), Tlv.t("9F10", "06010A03A00000"))) + Emv.SW_OK
    }

    override fun onDeactivated(reason: Int) {
        selected = ""
        NfcEvents.emit("deactivated") { put("reason", reason) }
    }

    private fun replenishInBackground(left: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val r = Backend(store).replenish()
                if (r.has("luks")) { store.addLuks(r); NfcEvents.emit("replenished") { put("luks", store.luksLeft()); put("was", left) } }
                else NfcEvents.emit("replenishFailed") { put("error", r.optString("error")) }
            }.onFailure { NfcEvents.emit("replenishFailed") { put("error", it.toString()) } }
        }
    }

    private fun log(msg: String, apdu: String) {
        NfcEvents.emit("apdu") { put("msg", msg); put("apdu", apdu) }
    }

    /** "Vea su teléfono": abre AuthActivity (BiometricPrompt sobre la pantalla bloqueada). Android 10+ puede bloquear
     *  el arranque desde un servicio: por eso además va una notificación heads-up con full-screen intent. */
    private fun notifyAuth(amountMinor: Long, currency: String) {
        val amount = fmt(amountMinor, currency)
        val open = Intent(this, AuthActivity::class.java).apply { putExtra("amount", amount); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        val pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_HIGH))
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.notif_auth_title))
            .setContentText(getString(R.string.notif_auth_body) + "  " + amount)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
        runCatching { startActivity(open) }
    }

    private fun vibrate(ms: Long) {
        runCatching {
            val v = getSystemService(android.os.Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") v.vibrate(ms)
        }
    }

    private fun fmt(minor: Long, cur: String): String {
        val dec = if (cur in setOf("CLP", "ARS", "PYG", "COP", "UYU")) 0 else 2
        return if (dec == 0) "$cur $minor" else "$cur %d.%02d".format(minor / 100, minor % 100)
    }

    companion object { const val CH = "d1wallet_pay"; const val NOTIF_ID = 1 }
}
