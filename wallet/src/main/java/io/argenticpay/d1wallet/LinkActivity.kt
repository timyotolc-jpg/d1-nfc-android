package io.argenticpay.d1wallet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Puerta de entrada sin interfaz. El demo web (en Chrome) abre d1wallet://nfc/<acción>?… y esta actividad
 * transparente hace lo que antes hacía window.D1Native (provision, replenish, biometría, wallet predeterminada…),
 * publica el resultado en la sesión Live (POST /nfc/events, type "wallet") y se cierra: el usuario vuelve a Chrome.
 *
 * Parámetros comunes en todas las acciones: api (URL base de la Lambda), session (código Live), tap (1|2).
 *   setup | state                 → publica el estado (y pide permiso de notificaciones la primera vez)
 *   provision?scheme&last4&exp&holder[&auth=1&title]  → (biometría) + POST /nfc/provision → guarda token + LUK
 *   replenish                     → POST /nfc/replenish
 *   delete[&auth=1&title]         → (biometría) + DELETE /nfc/token
 *   preauth?seconds[&title]       → biometría → CDCVM válido N s (modo 2 toques / pago manual)
 *   auth?title                    → solo biometría
 *   tapMode?tap=1|2               → guarda el modo de toque
 *   cancelPreauth
 *   default                       → diálogo del sistema ACTION_CHANGE_DEFAULT (app de pago predeterminada)
 */
class LinkActivity : AppCompatActivity() {

    private lateinit var store: WalletStore
    private lateinit var backend: Backend

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WalletStore(this)
        backend = Backend(store)
        Relay.init(this)
        val u: Uri? = intent?.data
        if (u == null || u.scheme != "d1wallet") { done(); return }
        u.getQueryParameter("api")?.takeIf { it.isNotBlank() }?.let { store.api = it }
        u.getQueryParameter("session")?.takeIf { it.isNotBlank() }?.let { store.session = it }
        u.getQueryParameter("tap")?.toIntOrNull()?.let { store.tapMode = it }
        val action = u.lastPathSegment ?: u.host ?: ""
        val wantAuth = u.getQueryParameter("auth") == "1"
        val title = u.getQueryParameter("title")?.takeIf { it.isNotBlank() } ?: "Confirmá con tu huella o cara"
        when (action) {
            "setup", "state", "nfc" -> { emitState(); askNotifications { done() } }
            "provision" -> { pendingAction = "provision"; gate(wantAuth, title) { provision(u) } }
            "replenish" -> replenish()
            "delete", "deleteToken" -> { pendingAction = "delete"; gate(wantAuth, title) { deleteToken() } }
            "preauth" -> {
                pendingAction = "preauth"
                val secs = u.getQueryParameter("seconds")?.toIntOrNull()?.takeIf { it > 0 } ?: 60
                biometric(title) {
                    store.setPreauth(secs)
                    wallet(JSONObject().put("type", "auth").put("ok", true).put("preauth", true).put("until", store.preauthUntil).put("state", state()))
                    wallet(JSONObject().put("type", "ready").put("until", store.preauthUntil))
                    done()
                }
            }
            "auth", "authenticate" -> biometric(title) { wallet(JSONObject().put("type", "auth").put("ok", true).put("state", state())); done() }
            "tapMode", "setTapMode" -> { wallet(JSONObject().put("type", "tapMode").put("value", store.tapMode)); done() }
            "cancelPreauth" -> { store.clearPreauth(); wallet(JSONObject().put("type", "preauthCleared")); done() }
            "default", "setDefaultPaymentApp" -> requestDefault()
            else -> done()
        }
    }

    // ---------- helpers ----------
    private fun state(): JSONObject = store.stateJson(this)
    private fun wallet(ev: JSONObject) = Relay.wallet(ev)
    private fun emitState() = wallet(JSONObject().put("type", "state").put("state", state()))

    private fun done() {
        if (!isFinishing) finish()
        overridePendingTransition(0, 0)
    }

    private var pendingAction = ""
    private fun failPending(err: String) {
        when (pendingAction) {
            "provision" -> wallet(JSONObject().put("type", "provision").put("ok", false).put("error", err))
            "delete" -> wallet(JSONObject().put("type", "deleted").put("ok", false).put("error", err))
            "preauth" -> wallet(JSONObject().put("type", "ready").put("ok", false).put("error", err))
            else -> {}
        }
    }

    private fun gate(wantAuth: Boolean, title: String, block: () -> Unit) {
        if (!wantAuth) { block(); return }
        biometric(title) {
            wallet(JSONObject().put("type", "auth").put("ok", true))
            block()
        }
    }

    /** Si el equipo no tiene huella/cara ni bloqueo de pantalla (teléfono de demo), se omite la verificación y sigue. */
    private fun biometric(title: String, onOk: () -> Unit) {
        val bm = BiometricManager.from(this)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (bm.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Sin huella ni bloqueo de pantalla: se omite la verificación (demo)", Toast.LENGTH_LONG).show()
            wallet(JSONObject().put("type", "auth").put("ok", true).put("skipped", true).put("error", "no biometrics/keyguard"))
            onOk(); return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                wallet(JSONObject().put("type", "auth").put("ok", false).put("error", errString.toString()))
                failPending(errString.toString())
                done()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Pago sin contacto")
            .setSubtitle(title)
            .setAllowedAuthenticators(allowed)
            .build()
        prompt.authenticate(info)
    }

    private fun provision(u: Uri) {
        val scheme = (u.getQueryParameter("scheme") ?: "MC").uppercase()
        val last4 = u.getQueryParameter("last4") ?: "0000"
        val exp = u.getQueryParameter("exp") ?: "12/30"
        val holder = u.getQueryParameter("holder") ?: "SANTIAGO SANTOS"
        lifecycleScope.launch {
            val r = runCatching { backend.provision(scheme, last4, exp, holder) }.getOrElse { JSONObject().put("error", it.toString()) }
            if (r.has("tokenRef")) {
                store.saveProvision(r, scheme, last4, holder)
                wallet(JSONObject().put("type", "provision").put("ok", true).put("tokenRef", store.tokenRef).put("last4", store.last4).put("dpan", store.dpan)
                    .put("luks", store.luksLeft()).put("aid", Emv.aidFor(store.scheme)).put("state", state()))
                Toast.makeText(this@LinkActivity, "Pago sin contacto activado · " + store.luksLeft() + " claves", Toast.LENGTH_SHORT).show()
            } else {
                wallet(JSONObject().put("type", "provision").put("ok", false).put("error", r.optString("error", "backend")))
                Toast.makeText(this@LinkActivity, "No se pudo activar: " + r.optString("error", "backend"), Toast.LENGTH_LONG).show()
            }
            done()
        }
    }

    private fun replenish() {
        lifecycleScope.launch {
            val r = runCatching { backend.replenish() }.getOrElse { JSONObject().put("error", it.toString()) }
            if (r.has("luks")) { store.addLuks(r); wallet(JSONObject().put("type", "replenished").put("luks", store.luksLeft()).put("state", state())) }
            else wallet(JSONObject().put("type", "replenishFailed").put("error", r.optString("error", "backend")))
            done()
        }
    }

    private fun deleteToken() {
        lifecycleScope.launch {
            runCatching { backend.delete() }
            store.clearToken()
            wallet(JSONObject().put("type", "deleted").put("state", state()))
            done()
        }
    }

    private fun requestDefault() {
        if (HceUtil.isDefault(this)) { wallet(JSONObject().put("type", "defaultWallet").put("value", true)); done(); return }
        val ok = runCatching { startActivityForResult(HceUtil.requestDefaultIntent(this), 42) }.isSuccess
        if (!ok) {
            Toast.makeText(this, "Ajustes → NFC → Pagos sin contacto → D1 Wallet", Toast.LENGTH_LONG).show()
            wallet(JSONObject().put("type", "defaultWallet").put("value", false))
            done()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42) {
            wallet(JSONObject().put("type", "defaultWallet").put("value", HceUtil.isDefault(this)).put("state", state()))
            done()
        }
    }

    private var afterPermission: (() -> Unit)? = null
    private fun askNotifications(then: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            afterPermission = then
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        } else then()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val f = afterPermission; afterPermission = null
        f?.invoke() ?: done()
    }
}
