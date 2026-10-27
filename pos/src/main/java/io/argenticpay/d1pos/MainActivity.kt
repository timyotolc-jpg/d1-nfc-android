package io.argenticpay.d1pos

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

/**
 * D1 POS — terminal de demo: importe → "Acerque tarjeta o teléfono" → lectura EMV contactless → autorización en la
 * Lambda d1-nfc (valida ARQC con la LUK) → APROBADO / RECHAZADA → ticket (pantalla + impresora Bluetooth ESC/POS).
 * SDK2: además es periférico Bluetooth LE (BleTap): el demo web en Chrome se conecta sin app, recibe la venta, manda el
 * criptograma y recibe el resultado; el toque físico se detecta por NFC (si el teléfono responde) o lo confirma el usuario.
 */
class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    data class Merchant(val key: String, val name: String, val mcc: String, val color: String, val addr: String)

    private val merchants = listOf(
        Merchant("safari", "SantoShops", "5732", "#0f6b3c", "Av. Apoquindo 1234"),
        Merchant("food", "SantoFood", "5814", "#e00025", "Local 12 - Patio de comidas"),
        Merchant("super", "Super3DS", "5411", "#0a7d5c", "Caja 07 - Sucursal Centro"),
        Merchant("tickets", "SantoTickets", "7922", "#6d28d9", "Boleteria Arena"),
    )

    private enum class St { AMOUNT, WAITING, READING, SEE_PHONE, AUTH, APPROVED, DECLINED, NO_CARD }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var amountTv: TextView
    private lateinit var label: TextView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var apdu: TextView
    private lateinit var bigIcon: TextView
    private lateinit var brandTv: TextView
    private lateinit var brandImg: android.widget.ImageView
    private val printerDev get() = prefs.getString("printerDev", "") ?: ""
    private var player: android.media.MediaPlayer? = null
    private lateinit var keys: GridLayout
    private lateinit var btnMain: Button
    private lateinit var merchantSp: Spinner
    private lateinit var merchantName: TextView
    private lateinit var foot: TextView
    private var nfc: NfcAdapter? = null
    private var ble: BleTap? = null
    private var blePhone = ""
    private var saleUn = ""
    private var lastProximity = ""   // SDK2: cómo se probó la cercanía del teléfono (campo NFC del POS / sticker NFC / ninguna)
    private var st = St.AMOUNT
    private var cents = 0L
    private var ticketNo = 317
    private var lastReceipt: List<String> = emptyList()
    private val backend = Backend { api }

    private val api get() = prefs.getString("api", DEFAULT_API) ?: DEFAULT_API
    private val session get() = prefs.getString("session", "") ?: ""
    private val currency get() = prefs.getString("currency", "USD") ?: "USD"
    private val printerMac get() = prefs.getString("printer", "") ?: ""
    private val terminalId get() = prefs.getString("terminal", "POS-0412") ?: "POS-0412"
    /** v2.3 «tap simple»: cualquier teléfono en el campo NFC (o el sticker leído por el teléfono) cobra el token de la sesión Live;
     *  no hace falta app ni EMV en el teléfono. Apagado = EMV real (motor D1 / HCE). */
    private val tapSimple get() = prefs.getBoolean("tapSimple", true)
    private var pollJob: kotlinx.coroutines.Job? = null
    private var pollSince = 0L
    private val dec get() = if (currency in setOf("CLP", "ARS", "PYG", "COP", "UYU")) 0 else 2
    private val merchant get() = merchants[merchantSp.selectedItemPosition.coerceIn(0, merchants.size - 1)]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("d1pos", Context.MODE_PRIVATE)
        ticketNo = prefs.getInt("ticket", 317)
        amountTv = findViewById(R.id.amount); label = findViewById(R.id.label); status = findViewById(R.id.status)
        detail = findViewById(R.id.detail); apdu = findViewById(R.id.apdu); bigIcon = findViewById(R.id.bigIcon); brandTv = findViewById(R.id.brand); brandImg = findViewById(R.id.brandImg)
        keys = findViewById(R.id.keys); btnMain = findViewById(R.id.btnMain); merchantSp = findViewById(R.id.merchant)
        merchantName = findViewById(R.id.merchantName); foot = findViewById(R.id.foot)
        findViewById<TextView>(R.id.terminal).text = terminalId

        merchantSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, merchants.map { it.name })
        merchantSp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { paintMerchant() }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        buildKeys()
        btnMain.setOnClickListener { onMain() }
        findViewById<Button>(R.id.btnConfig).setOnClickListener { showConfig() }
        nfc = NfcAdapter.getDefaultAdapter(this)
        if (nfc == null) foot.text = "Este equipo no tiene NFC"
        paintMerchant()
        render()
        ensureBle()
    }

    override fun onDestroy() { ble?.stop(); super.onDestroy() }

    // ---------- SDK2: canal Bluetooth LE (periférico GATT) ----------
    private fun blePerms(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    private fun ensureBle() {
        val missing = blePerms().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { ActivityCompat.requestPermissions(this, missing.toTypedArray(), 10); return }
        startBle()
    }

    private fun startBle() {
        if (ble != null) return
        val b = BleTap(this, object : BleTap.Listener {
            override fun onBleStatus(text: String) { runOnUiThread { bleStatus = text; render() } }
            override fun onPhone(connected: Boolean, name: String) {
                runOnUiThread {
                    blePhone = if (connected) name else ""
                    if (connected && st == St.WAITING) sendSale()
                    render()
                }
            }
            override fun onCommand(cmd: JSONObject) {
                runOnUiThread {
                    when (cmd.optString("t")) {
                        "crypto" -> authorizeBle(cmd)
                        "cancel" -> if (st == St.WAITING || st == St.READING) cancelSale()
                        else -> {}
                    }
                }
            }
        })
        ble = b
        if (!b.start()) ble = null
    }

    private var bleStatus = ""

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startBle()
    }

    private fun sendSale() {
        ble?.send("sale", JSONObject().put("amount", cents).put("currency", currency).put("un", saleUn).put("merchant", merchant.name)
            .put("merchantKey", merchant.key).put("mcc", merchant.mcc).put("terminal", terminalId).put("session", session))
    }

    /** Criptograma recibido por Bluetooth (web SDK2): misma autorización que el tap NFC. */
    private fun authorizeBle(cmd: JSONObject) {
        if (st != St.WAITING && st != St.READING) return
        disableReader()
        val tagUid = cmd.optString("tag", "")
        lastProximity = if (tagUid.isNotEmpty()) "sticker NFC " + tagUid.take(23) else if (st == St.READING) "campo NFC del POS" else "sin toque (confirmación remota)"
        val r = EmvReader.Result(
            kind = EmvReader.Kind.DATA,
            aid = cmd.optString("aid", "A0000000041010"),
            dpan = cmd.optString("dpan"),
            arqc = cmd.optString("arqc"),
            atc = cmd.optInt("atc"),
            un = cmd.optString("un", saleUn),
            log = listOf("BLE ← crypto " + cmd.optString("arqc").take(16) + " · ATC " + cmd.optInt("atc"), "proximidad: $lastProximity"),
        )
        apdu.text = r.log.joinToString("\n")
        live("reading", JSONObject().put("via", "ble"))
        authorize(r, viaBle = true)
    }

    private fun paintMerchant() {
        merchantName.text = merchant.name
        merchantName.setBackgroundColor(Color.parseColor(merchant.color))
    }

    private fun buildKeys() {
        keys.removeAllViews()
        val labels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "⌫")
        for (k in labels) {
            val b = Button(this).apply {
                text = k; textSize = 20f; setTextColor(Color.parseColor("#111111"))
                setBackgroundColor(if (k == "C" || k == "⌫") Color.parseColor("#eef0f7") else Color.WHITE)
                setOnClickListener { key(k) }
            }
            val lp = GridLayout.LayoutParams().apply { width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(4, 4, 4, 4) }
            keys.addView(b, lp)
        }
    }

    private fun key(k: String) {
        if (st != St.AMOUNT) return
        when (k) {
            "C" -> cents = 0
            "⌫" -> cents /= 10
            else -> if (cents < 100_000_000) cents = cents * 10 + k.toLong()
        }
        render()
    }

    private fun fmt(minor: Long): String {
        val v = minor / Math.pow(10.0, dec.toDouble())
        return runCatching {
            val nf = NumberFormat.getCurrencyInstance(Locale.forLanguageTag(when (currency) { "CLP" -> "es-CL"; "ARS" -> "es-AR"; "MXN" -> "es-MX"; "BRL" -> "pt-BR"; "UYU" -> "es-UY"; "PYG" -> "es-PY"; "PEN" -> "es-PE"; "COP" -> "es-CO"; else -> "en-US" }))
            nf.currency = Currency.getInstance(currency); nf.maximumFractionDigits = dec; nf.minimumFractionDigits = dec
            nf.format(v)
        }.getOrElse { "$currency $v" }
    }

    // ---------- máquina de estados ----------
    private fun onMain() {
        when (st) {
            St.AMOUNT -> if (cents > 0) startSale()
            St.WAITING, St.READING, St.SEE_PHONE, St.AUTH -> cancelSale()
            St.APPROVED, St.DECLINED, St.NO_CARD -> newSale()
        }
    }

    private fun startSale() {
        st = St.WAITING; render()
        saleUn = "%08X".format(java.security.SecureRandom().nextInt())
        enableReader()
        live("sale", JSONObject().put("cents", cents).put("currency", currency).put("merchant", merchant.key).put("mcc", merchant.mcc).put("terminal", terminalId))
        if (blePhone.isNotEmpty()) sendSale()
        startPoll()
        lifecycleScope.launch {
            val r = runCatching { backend.ping() }
            if (r.isFailure && (st == St.WAITING || st == St.READING)) {
                detail.text = "⚠ Sin conexión con el backend (" + (r.exceptionOrNull()?.message ?: "error").take(50) + ") · revisá Wi-Fi/datos y la URL en ⚙"
                vibrate(200)
            }
        }
    }

    private fun cancelSale() { stopPoll(); disableReader(); st = St.AMOUNT; render(); live("reset", JSONObject()); ble?.send("reset", JSONObject()) }

    private fun newSale() { stopPoll(); st = St.AMOUNT; cents = 0; apdu.text = ""; render(); live("reset", JSONObject()); ble?.send("reset", JSONObject()) }

    // ---------- tap simple (v2.3) ----------
    /** Mientras la venta espera, mira la sesión Live: «tapweb» = el teléfono leyó el sticker NFC del POS (Web NFC o link de iPhone). */
    private fun startPoll() {
        stopPoll()
        if (!tapSimple || session.isEmpty()) return
        pollJob = lifecycleScope.launch {
            val first = runCatching { backend.get("/nfc/events?session=$session&since=0") }.getOrNull()
            pollSince = first?.optLong("last", 0L) ?: 0L
            while (st == St.WAITING) {
                kotlinx.coroutines.delay(1000)
                if (st != St.WAITING) break
                val r = runCatching { backend.get("/nfc/events?session=$session&since=$pollSince") }.getOrNull() ?: continue
                pollSince = r.optLong("last", pollSince)
                val evs = r.optJSONArray("events") ?: continue
                for (i in 0 until evs.length()) {
                    val ev = evs.getJSONObject(i)
                    if (ev.optString("type") == "tapweb" && st == St.WAITING) {
                        val d = ev.optJSONObject("data") ?: JSONObject()
                        val tag = d.optString("tag", "")
                        authorizeSimple(if (tag.isNotEmpty()) "sticker NFC $tag" else "link NFC (teléfono)", tag)
                        return@launch
                    }
                }
            }
        }
    }

    private fun stopPoll() { pollJob?.cancel(); pollJob = null }

    /** El POS vio un teléfono (o el teléfono vio el sticker): el backend cobra el token de la sesión y genera el criptograma él. */
    private fun authorizeSimple(prox: String, tag: String) {
        if (st != St.WAITING) return
        stopPoll(); disableReader()
        lastProximity = prox
        setStatus(St.AUTH, "Autorizando…", "Tap simple · token de la sesión $session · $prox")
        live("reading", JSONObject().put("via", "tap-simple").put("prox", prox))
        lifecycleScope.launch {
            val body = JSONObject().put("session", session).put("tapOnly", true).put("amount", cents).put("currency", currency).put("un", saleUn)
                .put("merchant", merchant.name).put("mcc", merchant.mcc).put("terminal", terminalId).put("tag", tag)
            val res = runCatching { backend.post("/nfc/authorize", body) }.getOrElse { JSONObject().put("approved", false).put("de39", "96").put("reason", "sin conexión: " + it.message) }
            val r = EmvReader.Result(
                kind = EmvReader.Kind.DATA,
                aid = if (res.optString("scheme") == "VISA") "A0000000031010" else "A0000000041010",
                dpan = "000000000000" + res.optString("tokenLast4", "0000"),
                arqc = res.optString("cryptogram", ""),
                atc = res.optInt("atc", 0),
                un = saleUn,
                log = listOf("TAP SIMPLE · $prox", "backend → cloud cryptogram " + res.optString("cryptogram", "").take(16) + " · ATC " + res.optInt("atc", 0)),
            )
            apdu.text = r.log.joinToString("\n")
            approvalSequence(r.aid, r.atc, r.arqc, res.optBoolean("approved")) {
                if (res.optBoolean("approved")) {
                    val auth = res.optString("authCode", "------")
                    ticketNo += 1; prefs.edit().putInt("ticket", ticketNo).apply()
                    lastReceipt = receipt(r, res, auth, viaBle = false, simple = true)
                    setStatus(St.APPROVED, "APROBADO", "Aut. $auth · " + schemeName(r.aid) + " •••• " + r.dpan.takeLast(4) + " · ticket ${"%06d".format(ticketNo)}")
                    vibrate(150)
                    printIfConfigured(lastReceipt)
                } else {
                    setStatus(St.DECLINED, "RECHAZADA", res.optString("de39", "05") + " · " + res.optString("reason", "declinada"))
                    vibrate(300)
                }
            }
        }
    }

    /** v2.4: «compatibilidad» = foreground dispatch (el polling normal del sistema) en vez de reader mode; para ROMs
     *  (MediaTek/Android 8) donde enableReaderMode no detecta teléfonos. En ⚙. */
    private val nfcCompat get() = prefs.getBoolean("nfcCompat", false)
    private var readerOn = false

    private fun enableReader() {
        val a = nfc ?: return
        readerOn = true
        if (nfcCompat) {
            runCatching {
                val pi = android.app.PendingIntent.getActivity(this, 0, android.content.Intent(this, javaClass).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
                val tech = arrayOf(arrayOf(IsoDep::class.java.name), arrayOf(android.nfc.tech.NfcA::class.java.name), arrayOf(android.nfc.tech.NfcB::class.java.name))
                a.enableForegroundDispatch(this, pi, null, tech)
            }.onFailure { readerOn = false; Log.w("D1POS", "foreground dispatch: $it") }
            return
        }
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        runCatching { a.enableReaderMode(this, this, flags, Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }) }
            .onFailure { readerOn = false; Log.w("D1POS", "reader mode: $it") }
    }

    private fun disableReader() {
        readerOn = false
        runCatching { nfc?.disableReaderMode(this) }
        runCatching { nfc?.disableForegroundDispatch(this) }
    }

    /** modo compatibilidad: el sistema entrega el tag por intent */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) onTagDiscovered(tag)
    }

    /** estado del NFC para mostrarlo mientras se espera el toque */
    private fun nfcStatus(): String {
        val a = nfc ?: return "⚠ este equipo no tiene NFC"
        if (!a.isEnabled) return "⚠ NFC apagado: activalo en Ajustes"
        return if (nfcCompat) "NFC lector (compatibilidad)" else "NFC lector activo"
    }

    override fun onResume() { super.onResume(); if (st == St.WAITING || st == St.SEE_PHONE) enableReader() }
    override fun onPause() { disableReader(); super.onPause() }

    /** Callback de NfcAdapter.ReaderCallback: llegó un tag al campo. */
    override fun onTagDiscovered(tag: Tag) {
        if (st != St.WAITING && st != St.SEE_PHONE) return
        // v2.2: solo cuentan tarjetas/teléfonos ISO-DEP. Un sticker NFC (NTAG) pegado en el POS para la proximidad del SDK2
        // queda siempre dentro del campo y no debe disparar nada acá.
        val iso = IsoDep.get(tag) ?: run { Log.d("D1POS", "tag no ISO-DEP ignorado: " + tag.techList.joinToString()); return }
        if (blePhone.isNotEmpty() && st == St.WAITING) {
            // SDK2: el teléfono conectado por BLE tocó el lector → le pedimos el criptograma por Bluetooth
            runOnUiThread { setStatus(St.READING, "Teléfono detectado", "Bluetooth · esperando criptograma de " + blePhone); vibrate(40) }
            live("reading", JSONObject().put("via", "ble"))
            ble?.send("tap", JSONObject().put("un", saleUn).put("amount", cents).put("currency", currency))
            return
        }
        if (tapSimple && st == St.WAITING) {
            // v2.3 tap simple: con que un teléfono entre al campo alcanza; el backend cobra el token de la sesión
            if (session.isEmpty()) { runOnUiThread { setStatus(St.WAITING, "Falta la sesión Live", "Tap simple: poné en ⚙ el código de 4 dígitos de Demo ⚙ «Sesión Live»") ; vibrate(80) }; return }
            runOnUiThread { vibrate(40); sound(R.raw.snd_tap); authorizeSimple("campo NFC del POS", "") }
            return
        }
        runOnUiThread { st = St.READING; render(); vibrate(40); sound(R.raw.snd_tap) }
        live("reading", JSONObject())
        lifecycleScope.launch {
            val reader = EmvReader(cents, EmvReader.CURRENCY[currency] ?: "0840", EmvReader.COUNTRY[currency] ?: "0840")
            val r = withContext(Dispatchers.IO) { runCatching { iso.connect(); reader.run(iso) }.getOrElse { EmvReader.Result(EmvReader.Kind.ERROR, error = it.toString()) } }
            apdu.text = r.log.takeLast(6).joinToString("\n")
            when (r.kind) {
                EmvReader.Kind.NO_CARD -> { disableReader(); setStatus(St.NO_CARD, "TARJETA NO RECONOCIDA", "Use otra tarjeta · " + r.error); live("nocard", JSONObject().put("error", r.error)) }
                EmvReader.Kind.ERROR -> { setStatus(St.WAITING, "Acerque de nuevo", "Lectura interrumpida · " + r.error.take(60)) }
                EmvReader.Kind.SEE_PHONE -> { setStatus(St.SEE_PHONE, "VEA SU TELÉFONO", "Verifique en el celular y vuelva a acercar · CTQ " + r.ctq); vibrate(80); live("seephone", JSONObject().put("ctq", r.ctq)) }
                EmvReader.Kind.DATA -> { disableReader(); authorize(r) }
            }
        }
    }

    // ---------- v2.5: experiencia de terminal — marca, sonidos y tiempos ----------
    private fun sound(resId: Int) {
        runCatching {
            player?.release()
            player = android.media.MediaPlayer.create(this, resId)?.apply { setOnCompletionListener { it.release(); if (player === it) player = null }; start() }
        }
    }

    /** nombre y color de la marca por AID (solo texto: los logos y sonidos oficiales son activos de cada marca; si los tenés,
     *  reemplazá res/raw/snd_visa.wav y snd_mc.wav y la app los usa tal cual) */
    private fun brand(aid: String): Pair<String, String> = if (aid.startsWith("A0000000031010") || aid.startsWith("A0000000032010")) "VISA" to "#1a1f71" else "Mastercard" to "#eb001b"

    /** logo oficial si está en res/drawable (logo_mc.png / logo_visa.png, los provee el cliente); si no, el nombre en texto */
    private fun brandLogo(aid: String): Int = resources.getIdentifier(if (brand(aid).first == "VISA") "logo_visa" else "logo_mc", "drawable", packageName)

    private fun showBrand(aid: String?, big: Boolean = false, onDark: Boolean = false) {
        if (aid.isNullOrEmpty()) { brandTv.visibility = View.GONE; brandImg.visibility = View.GONE; return }
        val (name, color) = brand(aid)
        val logo = brandLogo(aid)
        if (logo != 0) {
            brandImg.setImageResource(logo); brandImg.visibility = View.VISIBLE; brandTv.visibility = View.GONE
            val lp = brandImg.layoutParams; val d = resources.displayMetrics.density
            lp.width = ((if (big) 260 else 120) * d).toInt(); lp.height = ((if (big) 160 else 72) * d).toInt(); brandImg.layoutParams = lp
        } else {
            brandImg.visibility = View.GONE
            brandTv.text = name; brandTv.textSize = if (big) 46f else 22f
            brandTv.setTextColor(Color.parseColor(if (onDark) "#ffffff" else color))
            brandTv.setBackgroundColor(if (big) Color.TRANSPARENT else Color.WHITE)
            brandTv.visibility = View.VISIBLE
        }
    }

    /** Momento de marca (sensory branding): pantalla de la marca + su sonido, como en una terminal real; ~2,5 s */
    private fun brandMoment(aid: String, onDone: () -> Unit) {
        val (name, color) = brand(aid)
        val visa = name == "VISA"
        val box = findViewById<View>(R.id.statusBox)
        box.setBackgroundColor(Color.parseColor(if (visa) "#1434CB" else "#ffffff"))
        status.visibility = View.INVISIBLE; detail.visibility = View.INVISIBLE; apdu.visibility = View.INVISIBLE; bigIcon.visibility = View.GONE
        showBrand(aid, big = true, onDark = visa)
        val v: View = if (brandImg.visibility == View.VISIBLE) brandImg else brandTv
        v.scaleX = 0.55f; v.scaleY = 0.55f; v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(480).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
        sound(if (visa) R.raw.snd_visa else R.raw.snd_mc)
        vibrate(120)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2600)
            v.animate().alpha(0f).setDuration(220).start()
            kotlinx.coroutines.delay(230)
            box.setBackgroundColor(Color.TRANSPARENT)
            status.visibility = View.VISIBLE; detail.visibility = View.VISIBLE; apdu.visibility = View.VISIBLE
            v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f
            showBrand(aid)
            onDone()
        }
    }

    /** Leyendo → marca → autorizando → momento de marca (sonido) → resultado */
    private fun approvalSequence(aid: String, atc: Int, arqc: String, approved: Boolean, onDone: () -> Unit) {
        val (name, _) = brand(aid)
        lifecycleScope.launch {
            setStatus(St.READING, "Leyendo tarjeta…", "SELECT PPSE → AID " + aid.take(10) + "… · " + name)
            showBrand(null)
            kotlinx.coroutines.delay(700)
            showBrand(aid)
            setStatus(St.AUTH, "Tarjeta $name", "GPO → criptograma ARQC " + arqc.take(8) + "… · ATC " + "%04d".format(atc))
            kotlinx.coroutines.delay(750)
            setStatus(St.AUTH, "Autorizando…", "0100 → red $name → emisor · esperando 0110")
            kotlinx.coroutines.delay(1000)
            if (approved) brandMoment(aid) { onDone() }
            else { onDone(); sound(R.raw.snd_decline) }
        }
    }

    private fun printIfConfigured(lines: List<String>) {
        if (printerDev.isNotEmpty()) {
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { InternalPrinter.print(printerDev, lines) }
                foot.text = if (r.isSuccess) "Ticket impreso ✓ (interna)" else "Impresora interna: " + (r.exceptionOrNull()?.message ?: "error")
            }
        } else if (printerMac.isNotEmpty()) printReceipt(lines)
    }

    private fun authorize(r: EmvReader.Result, viaBle: Boolean = false) {
        setStatus(St.AUTH, "Autorizando…", "ARQC " + r.arqc.take(8) + "… · ATC " + "%04d".format(r.atc) + " · token •••• " + r.dpan.takeLast(4))
        lifecycleScope.launch {
            val body = JSONObject().put("dpan", r.dpan).put("atc", r.atc).put("cryptogram", r.arqc).put("amount", cents).put("currency", currency)
                .put("un", r.un).put("merchant", merchant.name).put("mcc", merchant.mcc).put("terminal", terminalId).put("session", session).put("aid", r.aid)
            val res = runCatching { backend.post("/nfc/authorize", body) }.getOrElse { JSONObject().put("approved", false).put("de39", "96").put("reason", "sin conexión: " + it.message) }
            approvalSequence(r.aid, r.atc, r.arqc, res.optBoolean("approved")) {
                if (res.optBoolean("approved")) {
                    val auth = res.optString("authCode", "------")
                    ticketNo += 1; prefs.edit().putInt("ticket", ticketNo).apply()
                    lastReceipt = receipt(r, res, auth, viaBle)
                    setStatus(St.APPROVED, "APROBADO", "Aut. $auth · " + schemeName(r.aid) + " •••• " + r.dpan.takeLast(4) + " · ticket ${"%06d".format(ticketNo)}")
                    vibrate(150)
                    printIfConfigured(lastReceipt)
                } else {
                    setStatus(St.DECLINED, "RECHAZADA", res.optString("de39", "05") + " · " + res.optString("reason", "declinada"))
                    vibrate(300)
                }
                if (viaBle) ble?.send("result", JSONObject().put("approved", res.optBoolean("approved")).put("authCode", res.optString("authCode", "")).put("de39", res.optString("de39", ""))
                    .put("reason", res.optString("reason", "")).put("rrn", res.optString("rrn", "")).put("amount", cents).put("currency", currency).put("merchant", merchant.name)
                    .put("merchantKey", merchant.key).put("ticket", "%06d".format(ticketNo)).put("luksLeft", res.optInt("luksLeft", -1)).put("atc", r.atc))
            }
        }
    }

    private fun schemeName(aid: String) = if (aid.startsWith("A0000000031010") || aid.startsWith("A0000000032010")) "Visa" else "Mastercard"

    private fun receipt(r: EmvReader.Result, res: JSONObject, auth: String, viaBle: Boolean = false, simple: Boolean = false): List<String> {
        val m = merchant
        val now = Date()
        return listOf(
            "#" + m.name.uppercase(),
            "=" + m.addr,
            "=Terminal $terminalId · Lote 0007",
            "=Ticket ${"%06d".format(ticketNo)}",
            "-",
            SimpleDateFormat("dd/MM/yyyy", Locale.US).format(now) + "              " + SimpleDateFormat("HH:mm", Locale.US).format(now),
            if (simple) "!VENTA · TAP SIMPLE" else if (viaBle) "!VENTA · TAP BLUETOOTH" else "!VENTA · CONTACTLESS",
            schemeName(r.aid) + " •••• " + r.dpan.takeLast(4) + "        Token",
            if (simple) "D1 cloud token · sesion $session   Telefono" else if (viaBle) "D1 Wallet SDK2 · BLE         Telefono" else "D1 NFC Wallet · HCE          Telefono",
            if (viaBle || simple) "=Proximidad: $lastProximity" else "=Lectura: campo NFC",
            "AID " + r.aid,
            "ATC " + "%04d".format(r.atc) + "   ARQC " + r.arqc.take(8) + "…",
            "CDCVM ✓ biometria        Sin firma",
            "-",
            "TOTAL              " + fmt(cents),
            "!APROBADO · Aut. $auth",
            "=RRN " + res.optString("rrn", ""),
            "=¡Gracias por su compra!",
            "=Thales D1 · demo",
        )
    }

    private fun printReceipt(lines: List<String>) {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 9); return
        }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { EscPos.print(printerMac, lines) }
            runOnUiThread { foot.text = if (r.isSuccess) "Ticket impreso ✓" else "Impresora: " + (r.exceptionOrNull()?.message ?: "error") }
        }
    }

    private fun setStatus(s: St, t: String, d: String) { st = s; status.text = t; detail.text = d; render() }

    private fun render() {
        amountTv.text = fmt(cents)
        amountTv.setTextColor(if (cents == 0L) Color.parseColor("#b5b8c4") else Color.parseColor("#111111"))
        keys.visibility = if (st == St.AMOUNT) View.VISIBLE else View.GONE
        bigIcon.visibility = if (st == St.AMOUNT) View.GONE else View.VISIBLE
        label.text = when (st) { St.AMOUNT -> "IMPORTE"; St.WAITING, St.READING, St.SEE_PHONE -> "TOTAL A PAGAR"; else -> "TOTAL" }
        when (st) {
            St.AMOUNT -> { status.text = ""; detail.text = "Ingresá el importe y tocá COBRAR"; bigIcon.text = ""; brandTv.visibility = View.GONE; brandImg.visibility = View.GONE }
            St.WAITING -> { status.text = "Acerque tarjeta o teléfono"; detail.text = (if (blePhone.isNotEmpty()) "Bluetooth · " + blePhone + " · " else nfcStatus() + " · ") + merchant.name; bigIcon.text = "))))"; bigIcon.setTextColor(Color.parseColor("#0ea5e9")); brandTv.visibility = View.GONE; brandImg.visibility = View.GONE }
            St.READING -> { status.text = "Leyendo… no retire"; bigIcon.text = "))))"; bigIcon.setTextColor(Color.parseColor("#0369a1")) }
            St.SEE_PHONE -> { bigIcon.text = "📱"; bigIcon.setTextColor(Color.parseColor("#f59e0b")) }
            St.AUTH -> { bigIcon.text = "⋯"; bigIcon.setTextColor(Color.parseColor("#0369a1")) }
            St.APPROVED -> { bigIcon.text = "✔"; bigIcon.setTextColor(Color.parseColor("#16a34a")); status.setTextColor(Color.parseColor("#15803d")) }
            St.DECLINED -> { bigIcon.text = "✖"; bigIcon.setTextColor(Color.parseColor("#dc2626")); status.setTextColor(Color.parseColor("#b91c1c")) }
            St.NO_CARD -> { bigIcon.text = "?"; bigIcon.setTextColor(Color.parseColor("#dc2626")); status.setTextColor(Color.parseColor("#b91c1c")) }
        }
        if (st != St.APPROVED && st != St.DECLINED && st != St.NO_CARD) status.setTextColor(Color.parseColor("#111111"))
        btnMain.text = when (st) { St.AMOUNT -> "COBRAR"; St.APPROVED, St.DECLINED, St.NO_CARD -> "NUEVA VENTA"; else -> "CANCELAR" }
        btnMain.setBackgroundColor(Color.parseColor(when (st) { St.AMOUNT -> if (cents > 0) "#16a34a" else "#9ca3af"; St.APPROVED, St.DECLINED, St.NO_CARD -> "#1a5fa0"; else -> "#6b7280" }))
        merchantSp.isEnabled = st == St.AMOUNT
        val bleTxt = when {
            blePhone.isNotEmpty() -> "BLE ✓ " + blePhone
            ble?.advertising == true -> "BLE listo"
            bleStatus.isNotEmpty() -> bleStatus
            else -> "BLE –"
        }
        foot.text = (if (session.isNotEmpty()) "Live · sesión $session · " else "") + (if (tapSimple) "tap simple · " else "EMV · ") + (if (printerDev.isNotEmpty()) "impresora interna ✓" else if (printerMac.isNotEmpty()) "impresora BT ✓" else "sin impresora") + " · " + currency + " · " + bleTxt
    }

    private fun live(type: String, data: JSONObject) {
        if (session.isEmpty()) return
        lifecycleScope.launch { runCatching { backend.post("/nfc/events", JSONObject().put("session", session).put("type", type).put("data", data)) } }
    }

    // ---------- configuración ----------
    @SuppressLint("MissingPermission")
    private fun showConfig() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 9)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        val eApi = EditText(this).apply { hint = "API base (…/prod)"; setText(api) }
        val eSes = EditText(this).apply { hint = "Sesión Live (4 dígitos, opcional)"; setText(session) }
        val eTerm = EditText(this).apply { hint = "Terminal ID"; setText(terminalId) }
        val curList = listOf("USD", "CLP", "ARS", "MXN", "BRL", "UYU", "PYG", "PEN", "COP")
        val spCur = Spinner(this).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, curList); setSelection(curList.indexOf(currency).coerceAtLeast(0)) }
        val printers = EscPos.bonded()
        val names = listOf("Sin impresora") + printers.map { (it.name ?: "?") + " · " + it.address }
        val spPr = Spinner(this).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, names); setSelection((printers.indexOfFirst { it.address == printerMac } + 1).coerceAtLeast(0)) }
        val lbl = { t: String -> TextView(this).apply { text = t; setTextColor(Color.GRAY); textSize = 12f; setPadding(0, pad / 2, 0, 0) } }
        val swSimple = android.widget.Switch(this).apply { text = "Tap simple (sin app en el teléfono): cualquier teléfono en el campo NFC cobra el token de la sesión Live"; isChecked = tapSimple; textSize = 13f; setPadding(0, pad / 2, 0, 0) }
        val swCompat = android.widget.Switch(this).apply { text = "Lector NFC en modo compatibilidad (si con la venta esperando no detecta el teléfono: equipos MediaTek / Android 8)"; isChecked = nfcCompat; textSize = 13f; setPadding(0, pad / 2, 0, 0) }
        box.addView(eApi); box.addView(eSes); box.addView(eTerm); box.addView(swSimple); box.addView(swCompat); box.addView(lbl("NFC: " + (if (nfc == null) "no disponible" else if (nfc?.isEnabled == true) "encendido" else "APAGADO (Ajustes → NFC)"))); box.addView(lbl("Moneda")); box.addView(spCur); box.addView(lbl("Impresora Bluetooth (emparejada)")); box.addView(spPr)
        box.addView(lbl("Tap Bluetooth (SDK2): " + when { ble == null -> if (bleStatus.isNotEmpty()) bleStatus else "sin permisos / no soportado"; blePhone.isNotEmpty() -> "teléfono conectado · $blePhone"; ble?.advertising == true -> "anunciando, esperando teléfono"; else -> bleStatus }))
        val btnTest = Button(this).apply { text = "Imprimir ticket de prueba"; gravity = Gravity.CENTER }
        box.addView(btnTest)
        val avail = InternalPrinter.available()
        box.addView(lbl("Impresora interna (puerto serie): " + (if (printerDev.isNotEmpty()) "usando $printerDev" else if (avail.isEmpty()) "no se ven puertos escribibles" else "puertos: " + avail.joinToString(", "))))
        val btnInt = Button(this).apply { text = if (printerDev.isNotEmpty()) "Olvidar impresora interna" else "Probar impresora interna"; gravity = Gravity.CENTER }
        box.addView(btnInt)
        btnInt.setOnClickListener {
            if (printerDev.isNotEmpty()) { prefs.edit().remove("printerDev").apply(); Toast.makeText(this, "Impresora interna olvidada", Toast.LENGTH_SHORT).show(); render(); return@setOnClickListener }
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { InternalPrinter.probe() }
                if (ok.isEmpty()) Toast.makeText(this@MainActivity, "Ningún puerto aceptó la impresión. Mandame el SDK de Scangle y la integro.", Toast.LENGTH_LONG).show()
                else {
                    prefs.edit().putString("printerDev", ok.first()).apply()
                    Toast.makeText(this@MainActivity, "Salió por " + ok.joinToString(", ") + " · guardado " + ok.first() + " (si el papel no se movió, tocá Olvidar)", Toast.LENGTH_LONG).show()
                }
                render()
            }
        }
        val dlg = AlertDialog.Builder(this).setTitle("D1 POS · configuración").setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.edit().putString("api", eApi.text.toString().trim()).putString("session", eSes.text.toString().trim()).putString("terminal", eTerm.text.toString().trim())
                    .putString("currency", curList[spCur.selectedItemPosition]).putString("printer", if (spPr.selectedItemPosition == 0) "" else printers[spPr.selectedItemPosition - 1].address)
                    .putBoolean("tapSimple", swSimple.isChecked).putBoolean("nfcCompat", swCompat.isChecked).apply()
                if (st == St.WAITING) { disableReader(); enableReader() }
                findViewById<TextView>(R.id.terminal).text = terminalId
                render()
            }.setNegativeButton("Cerrar", null).create()
        btnTest.setOnClickListener {
            val sel = spPr.selectedItemPosition
            if (sel == 0) { Toast.makeText(this, "Elegí una impresora", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            prefs.edit().putString("printer", printers[sel - 1].address).apply()
            printReceipt(listOf("#D1 POS", "=prueba de impresora", "-", "Hola Santi, la impresora anda.", "=Thales D1 · demo"))
        }
        dlg.show()
    }

    private fun vibrate(ms: Long) {
        runCatching {
            val v = getSystemService(android.os.Vibrator::class.java)
            v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    companion object { const val DEFAULT_API = "https://hnkl4mjikh.execute-api.us-east-1.amazonaws.com/prod" }
}
