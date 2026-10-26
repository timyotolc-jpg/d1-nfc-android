package io.argenticpay.d1pos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class Backend(private val base: () -> String) {
    suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val c = URL(base().trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 12000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Accept", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "{}"
        val j = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", text) }
        j.put("httpStatus", code)
        j
    }

    /** GET liviano para saber si el backend responde (cualquier código HTTP cuenta como "hay conexión"). */
    suspend fun ping(): Int = withContext(Dispatchers.IO) {
        val c = URL(base().trimEnd('/') + "/nfc/events?session=0&since=0").openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = 5000; c.readTimeout = 6000
        val code = c.responseCode
        c.disconnect()
        code
    }
}
