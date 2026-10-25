package io.argenticpay.d1wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Cliente HTTP mínimo contra la Lambda d1-nfc (sin dependencias). */
class Backend(private val store: WalletStore) {

    suspend fun provision(scheme: String, last4: String, exp: String, holder: String): JSONObject =
        post("/nfc/provision", JSONObject().put("deviceId", store.deviceId).put("scheme", scheme).put("last4", last4)
            .put("exp", exp).put("holder", holder).put("session", store.session))

    suspend fun replenish(): JSONObject =
        post("/nfc/replenish", JSONObject().put("tokenRef", store.tokenRef).put("deviceId", store.deviceId).put("session", store.session))

    suspend fun event(type: String, data: JSONObject): JSONObject? =
        if (store.session.isEmpty()) null else runCatching { post("/nfc/events", JSONObject().put("session", store.session).put("type", type).put("data", data)) }.getOrNull()

    suspend fun delete(): JSONObject = request("DELETE", "/nfc/token?tokenRef=" + store.tokenRef + "&session=" + store.session, null)

    suspend fun post(path: String, body: JSONObject): JSONObject = request("POST", path, body)

    private suspend fun request(method: String, path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val c = URL(store.api + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("Accept", "application/json")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "{}"
        val j = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", text) }
        j.put("httpStatus", code)
        if (code >= 400 && !j.has("error")) j.put("error", "HTTP $code")
        j
    }
}
