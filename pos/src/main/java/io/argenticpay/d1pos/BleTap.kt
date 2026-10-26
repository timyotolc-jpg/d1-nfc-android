package io.argenticpay.d1pos

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * SDK2 — canal "tap" por Bluetooth LE. El POS es periférico GATT; el demo web (Chrome Android, Web Bluetooth) se
 * conecta sin ninguna app en el teléfono: recibe la venta, manda el criptograma (LUK) y recibe el resultado.
 *
 *  evt  (notify + read) → tipo corto del último evento: "sale" | "tap" | "result" | "reset"
 *  data (read)          → JSON completo del último evento (el teléfono lo lee tras la notificación: evita el límite del MTU)
 *  cmd  (write)         → JSON del teléfono: {t:"crypto", dpan, atc, arqc, aid, last4, scheme, tokenRef} · {t:"hello", device} · {t:"cancel"}
 */
@SuppressLint("MissingPermission")
class BleTap(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onBleStatus(text: String)
        fun onPhone(connected: Boolean, name: String)
        fun onCommand(cmd: JSONObject)
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("a1d1b1e0-0001-4d31-b1e0-d1b1e0000001")
        val EVT: UUID = UUID.fromString("a1d1b1e0-0002-4d31-b1e0-d1b1e0000001")
        val DATA: UUID = UUID.fromString("a1d1b1e0-0003-4d31-b1e0-d1b1e0000001")
        val CMD: UUID = UUID.fromString("a1d1b1e0-0004-4d31-b1e0-d1b1e0000001")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var evt: BluetoothGattCharacteristic? = null
    private var data: BluetoothGattCharacteristic? = null
    private val subscribed = LinkedHashMap<String, BluetoothDevice>()
    private val prepared = HashMap<String, ByteArrayOutputStream>()
    @Volatile private var lastJson: ByteArray = "{}".toByteArray()
    @Volatile var advertising = false
        private set

    val supported: Boolean get() = adapter != null && adapter.bluetoothLeAdvertiser != null
    val enabled: Boolean get() = adapter?.isEnabled == true
    val connected: Boolean get() = synchronized(subscribed) { subscribed.isNotEmpty() }
    val phoneName: String get() = synchronized(subscribed) { subscribed.values.firstOrNull()?.let { it.name ?: it.address } ?: "" }

    /** Arranca el servidor GATT y el anuncio. Devuelve false si el equipo no puede ser periférico BLE. */
    fun start(): Boolean {
        val ad = adapter ?: run { listener.onBleStatus("Sin Bluetooth"); return false }
        if (!ad.isEnabled) { listener.onBleStatus("Bluetooth apagado"); return false }
        val adv = ad.bluetoothLeAdvertiser ?: run { listener.onBleStatus("Este equipo no soporta modo periférico BLE"); return false }
        if (server != null) return true
        val srv = manager.openGattServer(ctx, callback) ?: run { listener.onBleStatus("No se pudo abrir el servidor GATT"); return false }
        server = srv
        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val e = BluetoothGattCharacteristic(EVT, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        e.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val d = BluetoothGattCharacteristic(DATA, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        val c = BluetoothGattCharacteristic(CMD, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        svc.addCharacteristic(e); svc.addCharacteristic(d); svc.addCharacteristic(c)
        evt = e; data = d
        srv.addService(svc)
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val advData = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(SERVICE)).build()
        val scanResp = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        adv.startAdvertising(settings, advData, scanResp, advCallback)
        return true
    }

    fun stop() {
        runCatching { advertiser?.stopAdvertising(advCallback) }
        runCatching { server?.close() }
        server = null; advertising = false
        synchronized(subscribed) { subscribed.clear() }
    }

    /** Evento al teléfono: guarda el JSON completo en `data` y notifica el tipo corto por `evt`. */
    fun send(type: String, json: JSONObject) {
        val srv = server ?: return
        val e = evt ?: return
        lastJson = json.put("t", type).toString().toByteArray()
        data?.value = lastJson
        e.value = type.toByteArray()
        val targets = synchronized(subscribed) { subscribed.values.toList() }
        for (dev in targets) runCatching { srv.notifyCharacteristicChanged(dev, e, false) }
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { advertising = true; listener.onBleStatus("BLE anunciando") }
        override fun onStartFailure(errorCode: Int) { advertising = false; listener.onBleStatus("BLE: no se pudo anunciar (error $errorCode)") }
    }

    private fun handle(bytes: ByteArray) {
        runCatching { JSONObject(String(bytes)) }.onSuccess { listener.onCommand(it) }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val was = synchronized(subscribed) { subscribed.remove(device.address) != null }
                prepared.remove(device.address)
                if (was) listener.onPhone(false, device.name ?: device.address)
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val v = if (characteristic.uuid == DATA) lastJson else (characteristic.value ?: ByteArray(0))
            val part = if (offset >= v.size) ByteArray(0) else v.copyOfRange(offset, v.size)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val bytes = value ?: ByteArray(0)
            if (preparedWrite) prepared.getOrPut(device.address) { ByteArrayOutputStream() }.write(bytes)
            else if (characteristic.uuid == CMD) handle(bytes)
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buf = prepared.remove(device.address)
            if (execute && buf != null) handle(buf.toByteArray())
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (descriptor.uuid == CCCD) {
                val on = value != null && value.isNotEmpty() && value[0].toInt() != 0
                val changed = synchronized(subscribed) {
                    if (on) subscribed.put(device.address, device) == null else subscribed.remove(device.address) != null
                }
                if (changed) listener.onPhone(on, device.name ?: device.address)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val on = synchronized(subscribed) { subscribed.containsKey(device.address) }
            val v = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }
    }
}
