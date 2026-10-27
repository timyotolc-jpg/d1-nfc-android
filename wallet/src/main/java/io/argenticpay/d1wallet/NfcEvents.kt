package io.argenticpay.d1wallet

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import org.json.JSONObject

/** Eventos del servicio HCE → demo web: por la sesión Live (Relay) y, dentro de la app D1 Showcase, directo al WebView (sink). */
object NfcEvents {
    /** la app con WebView se engancha acá y manda cada evento a window.d1NativeEvent */
    @Volatile var sink: ((JSONObject) -> Unit)? = null

    fun emit(type: String, build: JSONObject.() -> Unit = {}) {
        val j = JSONObject().put("type", type).put("ts", System.currentTimeMillis())
        j.build()
        Relay.wallet(j)   // Relay también se lo pasa al sink (app con WebView)
    }
}

object HceUtil {
    fun component(ctx: Context) = ComponentName(ctx, D1HceService::class.java)

    fun isDefault(ctx: Context): Boolean {
        val ad = NfcAdapter.getDefaultAdapter(ctx) ?: return false
        return runCatching { CardEmulation.getInstance(ad).isDefaultServiceForCategory(component(ctx), CardEmulation.CATEGORY_PAYMENT) }.getOrDefault(false)
    }

    /** Teléfono desbloqueado (keyguard abajo): en modo 1 toque cuenta como CDCVM, como el "device unlock" del SDK. */
    fun unlocked(ctx: Context): Boolean {
        val km = ctx.getSystemService(KeyguardManager::class.java) ?: return true
        return !km.isKeyguardLocked && !km.isDeviceLocked
    }

    /** Diálogo del sistema "¿usar D1 Wallet como app de pago predeterminada?" (como HceHelper del sample de Thales). */
    fun requestDefaultIntent(ctx: Context): Intent = Intent(CardEmulation.ACTION_CHANGE_DEFAULT).apply {
        putExtra(CardEmulation.EXTRA_CATEGORY, CardEmulation.CATEGORY_PAYMENT)
        putExtra(CardEmulation.EXTRA_SERVICE_COMPONENT, component(ctx))
    }
}
