package io.argenticpay.d1wallet

import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * "Vea su teléfono": el POS pidió CDCVM (modo 2 toques o teléfono bloqueado). Se abre sobre la pantalla bloqueada,
 * pide huella/cara (BiometricPrompt real) y deja la credencial válida 60 s para el segundo toque.
 */
class AuthActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val store = WalletStore(this)
        Relay.init(this)
        runCatching { getSystemService(NotificationManager::class.java).cancel(D1HceService.NOTIF_ID) }
        val amount = intent?.getStringExtra("amount") ?: ""
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            Relay.wallet(JSONObject().put("type", "auth").put("ok", false).put("error", "no biometrics/keyguard"))
            finish(); return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                store.setPreauth(60)
                Relay.wallet(JSONObject().put("type", "auth").put("ok", true).put("preauth", true).put("until", store.preauthUntil).put("state", store.stateJson(this@AuthActivity)))
                Relay.wallet(JSONObject().put("type", "ready").put("until", store.preauthUntil))
                finish(); overridePendingTransition(0, 0)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Relay.wallet(JSONObject().put("type", "auth").put("ok", false).put("error", errString.toString()))
                finish(); overridePendingTransition(0, 0)
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Pago sin contacto")
            .setSubtitle(if (amount.isBlank()) "Confirmá el pago y volvé a acercar el teléfono" else "Confirmá el pago de $amount y volvé a acercar el teléfono")
            .setAllowedAuthenticators(allowed)
            .build()
        prompt.authenticate(info)
    }
}
