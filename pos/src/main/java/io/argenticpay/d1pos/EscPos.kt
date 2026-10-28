package io.argenticpay.d1pos

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import java.io.OutputStream
import java.util.UUID

/** Impresora térmica Bluetooth (SPP) con comandos ESC/POS básicos — funciona con las 58 mm genéricas de ML. */
object EscPos {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** v3.2: imágenes por nombre (línea "@nombre" en el ticket) ya convertidas a raster ESC/POS (GS v 0), 384 puntos de ancho */
    val images = HashMap<String, ByteArray>()

    /** bitmap (blanco y negro o gris) → comandos GS v 0 en bloques de 96 filas; negro = bit 1; ancho fijo 384 (58 mm) */
    fun raster(bmp: android.graphics.Bitmap, width: Int = 384): ByteArray {
        val scaled = if (bmp.width == width) bmp else android.graphics.Bitmap.createScaledBitmap(bmp, width, bmp.height * width / bmp.width, true)
        val w = scaled.width; val h = scaled.height; val bpr = (w + 7) / 8
        val px = IntArray(w * h); scaled.getPixels(px, 0, w, 0, 0, w, h)
        val out = java.io.ByteArrayOutputStream()
        var y0 = 0
        while (y0 < h) {
            val rows = minOf(96, h - y0)
            out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, (bpr and 0xFF).toByte(), (bpr shr 8).toByte(), (rows and 0xFF).toByte(), (rows shr 8).toByte()))
            for (y in y0 until y0 + rows) {
                var x = 0
                while (x < w) {
                    var b = 0
                    for (bit in 0 until 8) {
                        val xx = x + bit
                        if (xx < w) {
                            val c = px[y * w + xx]
                            val lum = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                            val alpha = (c ushr 24) and 0xFF
                            if (alpha > 127 && lum < 128) b = b or (0x80 shr bit)
                        }
                    }
                    out.write(b); x += 8
                }
            }
            y0 += rows
        }
        return out.toByteArray()
    }

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
        sock.outputStream.use { o -> write(o, lines); Thread.sleep(if (lines.any { it.startsWith("@") }) 1500 else 400) }   // que el buffer BT llegue entero antes de cerrar
        sock.close()
    }

    /** mismo render ESC/POS sobre cualquier OutputStream (Bluetooth SPP o puerto serie interno) */
    fun writeTo(o: OutputStream, lines: List<String>) = write(o, lines)

    private fun write(o: OutputStream, lines: List<String>) {
        o.write(byteArrayOf(0x1B, 0x40))                  // init
        for (raw in lines) {
            when {
                raw.startsWith("@") -> { val img = images[raw.drop(1)]; if (img != null) { o.write(byteArrayOf(0x1B, 0x61, 1)); o.write(img); o.write(byteArrayOf(0x0A, 0x1B, 0x61, 0)) } }
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
