package io.argenticpay.d1showcase

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.argenticpay.d1wallet.Backend
import io.argenticpay.d1wallet.Emv
import io.argenticpay.d1wallet.HceUtil
import io.argenticpay.d1wallet.NfcEvents
import io.argenticpay.d1wallet.Relay
import io.argenticpay.d1wallet.WalletStore
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * D1 Showcase — la demo web (sdk.argenticpay.io) en un WebView, con el motor NFC (HCE, LUK, biometría, billetera
 * predeterminada) dentro de la misma app. La web habla con el motor por window.D1Native (mismo contrato que el
 * motor invisible por intents) y recibe los eventos por window.d1NativeEvent. Cada HTML nuevo que subís se ve acá
 * sin recompilar: la app solo carga la URL.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var store: WalletStore
    private lateinit var backend: Backend
    private val prefs by lazy { getSharedPreferences("d1showcase", MODE_PRIVATE) }
    private val home get() = prefs.getString("home", "index.html") ?: "index.html"
    private val base get() = prefs.getString("base", BASE) ?: BASE

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WalletStore(this)
        backend = Backend(store)
        Relay.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0b1030"))
        setContentView(web)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // la web reconoce "D1Wallet/" como contenedor nativo (no usa intents ni Web Bluetooth)
            userAgentString = userAgentString + " D1App/" + BuildConfig_VERSION + " D1Wallet/3.0"
        }
        web.addJavascriptInterface(Bridge(), "D1Native")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val h = u.host ?: ""
                // la demo (y sus assets) se quedan adentro; todo lo demás (GitHub, bancos…) va al navegador
                if (u.scheme == "https" && (h == HOST || h.endsWith(".argenticpay.io"))) return false
                if (u.scheme == "intent" || u.scheme == "d1wallet") return true
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                // la web ya está: le avisamos el estado del motor (window.d1NativeEvent lo espera al montar)
                emit(JSONObject().put("type", "nativeReady").put("state", state()))
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean { android.util.Log.d("D1Web", m.message()); return true }
            override fun onPermissionRequest(request: PermissionRequest) {
                // cámara (biometría "real" de la demo / escaneo) y nada más
                val ok = request.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }.toTypedArray()
                if (ok.isNotEmpty() && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) request.grant(ok) else request.deny()
            }
        }
        NfcEvents.sink = { ev -> emit(ev) }
        askPermissions()
        web.loadUrl(base + "/" + home)
    }

    override fun onDestroy() { NfcEvents.sink = null; super.onDestroy() }
    override fun onResume() { super.onResume(); web.onResume(); emit(JSONObject().put("type", "state").put("state", state())) }
    override fun onPause() { web.onPause(); super.onPause() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    // ---------- eventos motor → web ----------
    private fun emit(ev: JSONObject) {
        runOnUiThread { runCatching { web.evaluateJavascript("window.d1NativeEvent && window.d1NativeEvent(" + ev.toString() + ")", null) } }
    }
    private fun state(): JSONObject = store.stateJson(this).put("engine", "app").put("app", true).put("home", home)

    // ---------- biometría ----------
    private fun biometric(title: String, onOk: () -> Unit, onFail: (String) -> Unit) {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Sin huella ni bloqueo de pantalla: se omite la verificación (demo)", Toast.LENGTH_LONG).show()
            emit(JSONObject().put("type", "auth").put("ok", true).put("skipped", true).put("error", "no biometrics/keyguard"))
            onOk(); return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                emit(JSONObject().put("type", "auth").put("ok", false).put("error", errString.toString()))
                onFail(errString.toString())
            }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Banco D1").setSubtitle(title).setAllowedAuthenticators(allowed).build())
    }

    private fun askPermissions() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 7)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42) emit(JSONObject().put("type", "defaultWallet").put("value", HceUtil.isDefault(this)).put("state", state()))
    }

    // ---------- window.D1Native (mismo contrato que el motor invisible / el shim de la web) ----------
    inner class Bridge {
        @JavascriptInterface fun getState(): String = state().toString()
        @JavascriptInterface fun version(): String = "D1Wallet/3.0 app " + BuildConfig_VERSION
        @JavascriptInterface fun log(msg: String?) { android.util.Log.d("D1Web", msg ?: "") }
        @JavascriptInterface fun getConfig(): String = JSONObject().put("api", store.api).put("session", store.session).put("engine", "app").put("home", home).put("base", base).toString()

        @JavascriptInterface fun setConfig(api: String?, session: String?, home: String?) {
            api?.takeIf { it.isNotBlank() }?.let { store.api = it.trim().trimEnd('/') }
            session?.takeIf { it.isNotBlank() }?.let { store.session = it.trim() }
            home?.takeIf { it.isNotBlank() }?.let { prefs.edit().putString("home", it.trim()).apply() }
        }

        /** la web pide biometría y después provision/delete en la misma ida (como el shim): acá la biometría es real */
        @JavascriptInterface fun authenticate(title: String?) {
            runOnUiThread {
                biometric(title ?: "Confirmá con tu huella o cara", onOk = { emit(JSONObject().put("type", "auth").put("ok", true).put("state", state())) }, onFail = {})
            }
        }

        @JavascriptInterface fun provision(scheme: String?, last4: String?, exp: String?, holder: String?) {
            val sc = (scheme ?: "MC").uppercase(); val l4 = last4 ?: "0000"; val ex = exp ?: "12/30"; val ho = holder ?: "SANTIAGO SANTOS"
            lifecycleScope.launch {
                val r = runCatching { backend.provision(sc, l4, ex, ho) }.getOrElse { JSONObject().put("error", it.toString()) }
                if (r.has("tokenRef")) {
                    store.saveProvision(r, sc, l4, ho)
                    emit(JSONObject().put("type", "provision").put("ok", true).put("tokenRef", store.tokenRef).put("last4", store.last4).put("dpan", store.dpan)
                        .put("luks", store.luksLeft()).put("aid", Emv.aidFor(store.scheme)).put("state", state()))
                } else emit(JSONObject().put("type", "provision").put("ok", false).put("error", r.optString("error", "backend")))
            }
        }

        @JavascriptInterface fun replenish() {
            lifecycleScope.launch {
                val r = runCatching { backend.replenish() }.getOrElse { JSONObject().put("error", it.toString()) }
                if (r.has("luks")) { store.addLuks(r); emit(JSONObject().put("type", "replenished").put("luks", store.luksLeft()).put("state", state())) }
                else emit(JSONObject().put("type", "replenishFailed").put("error", r.optString("error", "backend")))
            }
        }

        @JavascriptInterface fun deleteToken() {
            lifecycleScope.launch {
                runCatching { backend.delete() }
                store.clearToken()
                emit(JSONObject().put("type", "deleted").put("state", state()))
            }
        }

        @JavascriptInterface fun preauth(seconds: Int) {
            runOnUiThread {
                biometric("Autenticate para pagar sin contacto", onOk = {
                    store.setPreauth(if (seconds > 0) seconds else 60)
                    emit(JSONObject().put("type", "auth").put("ok", true).put("preauth", true).put("until", store.preauthUntil).put("state", state()))
                    emit(JSONObject().put("type", "ready").put("until", store.preauthUntil))
                }, onFail = { emit(JSONObject().put("type", "ready").put("ok", false).put("error", it)) })
            }
        }

        @JavascriptInterface fun setTapMode(mode: Int) {
            store.tapMode = if (mode == 2) 2 else 1
            emit(JSONObject().put("type", "tapMode").put("value", store.tapMode))
        }

        @JavascriptInterface fun cancelPreauth() { store.clearPreauth(); emit(JSONObject().put("type", "preauthCleared")) }

        @JavascriptInterface fun isDefaultPaymentApp(): Boolean = HceUtil.isDefault(this@MainActivity)

        @JavascriptInterface fun setDefaultPaymentApp() {
            runOnUiThread {
                if (HceUtil.isDefault(this@MainActivity)) { emit(JSONObject().put("type", "defaultWallet").put("value", true)); return@runOnUiThread }
                val ok = runCatching { startActivityForResult(HceUtil.requestDefaultIntent(this@MainActivity), 42) }.isSuccess
                if (!ok) {
                    Toast.makeText(this@MainActivity, "Ajustes → NFC → Pagos sin contacto → D1 Showcase", Toast.LENGTH_LONG).show()
                    emit(JSONObject().put("type", "defaultWallet").put("value", false))
                }
            }
        }

        /** la app: cambiar la pantalla de inicio (index.html DigiBank / index1.html marca blanca) y recargar */
        @JavascriptInterface fun setHome(page: String?) {
            val p = (page ?: "index.html").trim()
            prefs.edit().putString("home", p).apply()
            runOnUiThread { web.loadUrl(base + "/" + p) }
        }

        @JavascriptInterface fun openExternal(url: String?) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url ?: BASE))) }
        }
    }

    companion object {
        const val HOST = "sdk.argenticpay.io"
        const val BASE = "https://sdk.argenticpay.io"
        const val BuildConfig_VERSION = "1.1"
    }
}
