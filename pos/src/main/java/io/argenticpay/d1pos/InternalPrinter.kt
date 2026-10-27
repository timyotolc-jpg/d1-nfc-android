package io.argenticpay.d1pos

import java.io.File
import java.io.FileOutputStream

/**
 * Impresora térmica interna de un POS Android (SP01 y similares): la mayoría la expone como un puerto serie o un
 * nodo de dispositivo que acepta ESC/POS crudo. Sin SDK del fabricante probamos los nodos habituales; el que
 * acepta la escritura y mueve el papel se guarda en ⚙.
 */
object InternalPrinter {
    val CANDIDATES = listOf(
        "/dev/ttyMT1", "/dev/ttyMT2", "/dev/ttyMT3", "/dev/ttyMT0",
        "/dev/ttyS1", "/dev/ttyS2", "/dev/ttyS3", "/dev/ttyS0",
        "/dev/printer", "/dev/thermal_printer", "/dev/print", "/dev/ttyPrinter",
        "/dev/usb/lp0", "/dev/lp0", "/dev/ttyUSB0", "/dev/ttyHSL1", "/dev/ttyHS1",
    )

    /** nodos que existen y se pueden escribir */
    fun available(): List<String> = CANDIDATES.filter { val f = File(it); f.exists() && f.canWrite() }

    fun print(dev: String, lines: List<String>): Result<Unit> = runCatching {
        FileOutputStream(File(dev)).use { o -> EscPos.writeTo(o, lines) }
    }

    /** imprime un ticket de prueba en cada nodo escribible y devuelve los que aceptaron la escritura */
    fun probe(): List<String> = available().filter { d ->
        print(d, listOf("#D1 POS", "=impresora interna", "=" + d, "-", "Si lees esto, es este puerto.", "=Thales D1 - demo")).isSuccess
    }
}
