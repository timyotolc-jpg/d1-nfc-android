package io.argenticpay.d1wallet

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Cola de eventos hacia la Lambda d1-nfc (sesión Live). El demo web corre en Chrome, no dentro de la app, así que
 * todo lo que antes llegaba por window.d1NativeEvent ahora viaja por POST /nfc/events (type "wallet") y el demo
 * lo lee con GET /nfc/events. Un solo hilo de envío → los eventos salen en orden.
 */
object Relay {
    private val queue = Channel<Pair<String, JSONObject>>(Channel.UNLIMITED)
    @Volatile private var store: WalletStore? = null

    fun init(ctx: Context) {
        if (store != null) return
        synchronized(this) {
            if (store != null) return
            val s = WalletStore(ctx.applicationContext)
            store = s
            CoroutineScope(Dispatchers.IO).launch {
                for ((type, data) in queue) {
                    val ses = s.session
                    if (ses.isEmpty()) continue
                    runCatching { Backend(s).post("/nfc/events", JSONObject().put("session", ses).put("type", type).put("data", data)) }
                }
            }
        }
    }

    /** Evento para el demo web (mismo JSON que antes recibía window.d1NativeEvent). */
    fun wallet(ev: JSONObject) {
        queue.trySend("wallet" to ev)
        NfcEvents.sink?.invoke(ev)   // dentro de la app D1 Showcase: directo a window.d1NativeEvent
    }

    /** Evento "crudo" de la sesión Live (por ejemplo "tap", que el POS de pantalla espeja). */
    fun post(type: String, data: JSONObject) { queue.trySend(type to data) }
}
