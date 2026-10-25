package io.argenticpay.d1wallet

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Estado del wallet en el dispositivo: token NFC, claves de uso limitado (LUK), ATC, pre-autenticación (CDCVM).
 * Demo: SharedPreferences. (En el SDK real las claves viven cifradas y atadas al dispositivo; acá alcanza para la demo.)
 */
class WalletStore(ctx: Context) {
    private val p: SharedPreferences = ctx.applicationContext.getSharedPreferences("d1wallet", Context.MODE_PRIVATE)

    var api: String
        get() = p.getString("api", DEFAULT_API) ?: DEFAULT_API
        set(v) = p.edit().putString("api", v.trim().trimEnd('/')).apply()
    var session: String
        get() = p.getString("session", "") ?: ""
        set(v) = p.edit().putString("session", v.trim()).apply()
    var deviceId: String
        get() {
            var d = p.getString("deviceId", null)
            if (d == null) { d = "dev_" + Emv.randomHex(4).lowercase(); p.edit().putString("deviceId", d).apply() }
            return d
        }
        set(v) = p.edit().putString("deviceId", v).apply()

    val active: Boolean get() = tokenRef.isNotEmpty()
    val tokenRef: String get() = p.getString("tokenRef", "") ?: ""
    val dpan: String get() = p.getString("dpan", "") ?: ""
    val last4: String get() = p.getString("last4", "") ?: ""
    val fpanLast4: String get() = p.getString("fpanLast4", "") ?: ""
    val exp: String get() = p.getString("exp", "12/30") ?: "12/30"
    val scheme: String get() = p.getString("scheme", "MC") ?: "MC"
    val holder: String get() = p.getString("holder", "SANTIAGO SANTOS") ?: "SANTIAGO SANTOS"
    val atc: Int get() = p.getInt("atc", 0)
    val txCount: Int get() = p.getInt("txCount", 0)

    /** LUKs disponibles: atc → key (hex). */
    fun luks(): MutableMap<Int, String> {
        val m = LinkedHashMap<Int, String>()
        val j = JSONObject(p.getString("luks", "{}") ?: "{}")
        for (k in j.keys()) m[k.toInt()] = j.getString(k)
        return m.toSortedMap().toMutableMap()
    }
    private fun saveLuks(m: Map<Int, String>) {
        val j = JSONObject(); for ((k, v) in m) j.put(k.toString(), v)
        p.edit().putString("luks", j.toString()).apply()
    }
    fun luksLeft(): Int = luks().size

    /** Guarda el token recién provisionado (respuesta de /nfc/provision). */
    fun saveProvision(r: JSONObject, scheme: String, fpanLast4: String, holder: String) {
        val m = LinkedHashMap<Int, String>()
        val arr = r.getJSONArray("luks")
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); m[o.getInt("atc")] = o.getString("key") }
        p.edit()
            .putString("tokenRef", r.getString("tokenRef"))
            .putString("dpan", r.getString("dpan"))
            .putString("last4", r.getString("last4"))
            .putString("exp", r.optString("exp", "12/30"))
            .putString("scheme", scheme)
            .putString("fpanLast4", fpanLast4)
            .putString("holder", holder)
            .putInt("atc", 0).putInt("txCount", 0)
            .apply()
        saveLuks(m)
    }

    fun addLuks(r: JSONObject) {
        val m = luks()
        val arr = r.getJSONArray("luks")
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); m[o.getInt("atc")] = o.getString("key") }
        saveLuks(m)
    }

    /** Toma la próxima LUK (menor ATC) y la quema. */
    fun takeLuk(): Pair<Int, String>? {
        val m = luks(); if (m.isEmpty()) return null
        val atc = m.keys.first(); val key = m.remove(atc)!!
        saveLuks(m)
        p.edit().putInt("atc", atc).putInt("txCount", txCount + 1).apply()
        return atc to key
    }

    fun clearToken() {
        p.edit().remove("tokenRef").remove("dpan").remove("last4").remove("luks").putInt("atc", 0).putInt("txCount", 0).remove("preauth").apply()
    }

    /** 1 = un toque (teléfono desbloqueado + app al frente = CDCVM, como "device unlock" del SDK); 2 = biometría en cada tap (see phone + 2.º toque). */
    var tapMode: Int
        get() = p.getInt("tapMode", 1)
        set(v) = p.edit().putInt("tapMode", if (v == 2) 2 else 1).apply()

    // --- CDCVM / pre-autenticación (1 toque) ---
    var preauthUntil: Long
        get() = p.getLong("preauth", 0L)
        set(v) = p.edit().putLong("preauth", v).apply()
    fun cdcvmValid(): Boolean = preauthUntil > System.currentTimeMillis()
    fun setPreauth(seconds: Int) { preauthUntil = System.currentTimeMillis() + seconds * 1000L }
    fun clearPreauth() { preauthUntil = 0L }

    fun stateJson(ctx: Context): JSONObject = JSONObject().apply {
        put("native", true)
        put("active", active)
        put("tokenRef", tokenRef); put("dpan", dpan); put("last4", last4); put("fpanLast4", fpanLast4)
        put("exp", exp); put("scheme", scheme); put("holder", holder)
        put("luks", luksLeft()); put("luksTotal", 5); put("atc", atc); put("txCount", txCount)
        put("preauthUntil", preauthUntil); put("cdcvm", cdcvmValid()); put("tapMode", tapMode); put("unlocked", HceUtil.unlocked(ctx)); put("foreground", HceUtil.unlocked(ctx))
        put("api", api); put("session", session); put("deviceId", deviceId); put("engine", "intent")
        put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
        put("nfc", android.nfc.NfcAdapter.getDefaultAdapter(ctx)?.isEnabled == true)
        put("defaultWallet", HceUtil.isDefault(ctx))
        put("aid", Emv.aidFor(scheme))
    }

    companion object {
        const val DEFAULT_API = "https://hnkl4mjikh.execute-api.us-east-1.amazonaws.com/prod"
    }
}
