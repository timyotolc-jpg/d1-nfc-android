package io.argenticpay.d1pos

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import java.io.OutputStream
import java.util.UUID

/** Impresora térmica Bluetooth (SPP) con comandos ESC/POS básicos — funciona con las 58 mm genéricas de ML. */
object EscPos {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun bonded(): List<BluetoothDevice> = runCatching { BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList() }.getOrDefault(emptyList())

    private fun ascii(s: String): ByteArray {
        val map = mapOf('á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ñ' to 'n', 'Á' to 'A', 'É' to 'E', 'Í' to 'I', 'Ó' to 'O', 'Ú' to 'U', 'Ñ' to 'N', '•' to '*', '·' to '-', '✓' to 'v', '—' to '-', '–' to '-', '“' to '"', '”' to '"', '’' to '\'')
        return s.map { map[it] ?: if (it.code < 128) it else '?' }.joinToString("").toByteArray(Charsets.US_ASCII)
    }

    /** Líneas con prefijos: "#" = título centrado y negrita, "=" = centrado, "-" = separador, "!" = doble alto, resto normal. */
    @SuppressLint("MissingPermission")
    fun print(mac: String, lines: List<String>): Result<Unit> = runCatching {
        val ad = BluetoothAdapter.getDefaultAdapter() ?: error("sin Bluetooth")
        val dev = ad.getRemoteDevice(mac)
        val sock = dev.createRfcommSocketToServiceRecord(SPP)
        runCatching { ad.cancelDiscovery() }
        sock.connect()
        sock.outputStream.use { o -> write(o, lines) }
        sock.close()
    }

    /** mismo render ESC/POS sobre cualquier OutputStream (Bluetooth SPP o puerto serie interno) */
    fun writeTo(o: OutputStream, lines: List<String>) = write(o, lines)

    private fun write(o: OutputStream, lines: List<String>) {
        o.write(byteArrayOf(0x1B, 0x40))                  // init
        for (raw in lines) {
            when {
                raw.startsWith("#") -> { o.write(byteArrayOf(0x1B, 0x61, 1, 0x1B, 0x45, 1, 0x1D, 0x21, 0x11)); o.write(ascii(raw.drop(1))); o.write(byteArrayOf(0x0A, 0x1D, 0x21, 0x00, 0x1B, 0x45, 0, 0x1B, 0x61, 0)) }
                raw.startsWith("!") -> { o.write(byteArrayOf(0x1B, 0x61, 1, 0x1B, 0x45, 1, 0x1D, 0x21, 0x01)); o.write(ascii(raw.drop(1))); o.write(byteArrayOf(0x0A, 0x1D, 0x21, 0x00, 0x1B, 0x45, 0, 0x1B, 0x61, 0)) }
                raw.startsWith("=") -> { o.write(byteArrayOf(0x1B, 0x61, 1)); o.write(ascii(raw.drop(1))); o.write(byteArrayOf(0x0A, 0x1B, 0x61, 0)) }
                raw == "-" -> { o.write(ascii("-".repeat(32))); o.write(0x0A) }
                else -> { o.write(ascii(raw)); o.write(0x0A) }
            }
        }
        o.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A))
        o.write(byteArrayOf(0x1D, 0x56, 0x00))            // cut (si la impresora tiene cortador; si no, lo ignora)
        o.flush()
    }
}
