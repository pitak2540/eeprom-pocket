package com.eepromtool.pocket.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import java.io.IOException

/** Minimal USB-serial drivers used to talk to the Arduino bridge firmware. */
abstract class SerialPort(
    protected val conn: UsbDeviceConnection,
    protected val epIn: UsbEndpoint,
    protected val epOut: UsbEndpoint,
    private val ifaces: List<UsbInterface>
) {
    abstract val name: String
    abstract fun configure(baud: Int)

    private val tmp = ByteArray(maxOf(epIn.maxPacketSize, 64) * 16)
    private var pend = ByteArray(0)
    private var pendPos = 0

    /** Converts a raw bulk-IN buffer into payload bytes (FTDI strips status bytes). */
    protected open fun strip(raw: ByteArray, n: Int): ByteArray = raw.copyOf(n)

    protected fun ctrlOut(req: Int, value: Int, index: Int, data: ByteArray? = null, type: Int = 0x40) {
        if (conn.controlTransfer(type, req, value, index, data, data?.size ?: 0, 1000) < 0)
            throw IOException("$name: control 0x%02X ล้มเหลว".format(req))
    }

    protected fun ctrlIn(req: Int, value: Int, index: Int, len: Int): ByteArray {
        val b = ByteArray(len)
        conn.controlTransfer(0xC0, req, value, index, b, len, 1000)
        return b
    }

    fun write(d: ByteArray) {
        var off = 0
        while (off < d.size) {
            val n = conn.bulkTransfer(epOut, d, off, d.size - off, 2000)
            if (n <= 0) throw IOException("$name: เขียนข้อมูลไม่สำเร็จ")
            off += n
        }
    }

    fun readExact(n: Int, timeoutMs: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (got < n) {
            if (pendPos < pend.size) {
                val k = minOf(n - got, pend.size - pendPos)
                System.arraycopy(pend, pendPos, out, got, k)
                pendPos += k; got += k
                continue
            }
            val r = conn.bulkTransfer(epIn, tmp, tmp.size, 50)
            if (r > 0) { pend = strip(tmp, r); pendPos = 0 }
            if (got < n && pendPos >= pend.size && SystemClock.uptimeMillis() > deadline)
                throw IOException("$name: หมดเวลารอข้อมูล")
        }
        return out
    }

    fun drain() {
        pend = ByteArray(0); pendPos = 0
        repeat(40) {
            val r = conn.bulkTransfer(epIn, tmp, tmp.size, 30)
            if (r <= 0 || strip(tmp, r).isEmpty()) return
        }
    }

    fun close() {
        for (i in ifaces) try { conn.releaseInterface(i) } catch (_: Exception) {}
        conn.close()
    }

    companion object {
        fun bulkPair(itf: UsbInterface): Pair<UsbEndpoint, UsbEndpoint> {
            var i: UsbEndpoint? = null
            var o: UsbEndpoint? = null
            for (k in 0 until itf.endpointCount) {
                val e = itf.getEndpoint(k)
                if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (e.direction == UsbConstants.USB_DIR_IN) i = e else o = e
            }
            if (i == null || o == null) throw IOException("ไม่พบ bulk endpoint")
            return i to o
        }
    }
}

/** WCH CH340 / CH341 in UART mode (1A86:7523, 5523). */
class Ch340Serial(conn: UsbDeviceConnection, itf: UsbInterface, eps: Pair<UsbEndpoint, UsbEndpoint>) :
    SerialPort(conn, eps.first, eps.second, listOf(itf)) {
    override val name = "CH340"

    override fun configure(baud: Int) {
        ctrlIn(0x5F, 0, 0, 2)
        ctrlOut(0xA1, 0, 0)
        setBaud(baud)
        ctrlIn(0x95, 0x2518, 0, 2)
        ctrlOut(0x9A, 0x2518, 0xC3)          // 8N1, rx+tx enable
        ctrlIn(0x95, 0x0706, 0, 2)
        ctrlOut(0xA1, 0x501F, 0xD90A)
        setBaud(baud)
        ctrlOut(0xA4, (1 shl 5 or (1 shl 6)).inv() and 0xFF, 0) // DTR + RTS on
        ctrlIn(0x95, 0x0706, 0, 2)
    }

    private fun setBaud(baud: Int) {
        var factor: Long
        var divisor: Long
        if (baud == 921600) {
            divisor = 7; factor = 0xF300
        } else {
            factor = 1532620800L / baud
            divisor = 3
            while (factor > 0xFFF0 && divisor > 0) { factor = factor shr 3; divisor-- }
            factor = 0x10000 - factor
        }
        divisor = divisor or 0x0080
        val v1 = ((factor and 0xFF00) or divisor).toInt()
        val v2 = (factor and 0xFF).toInt()
        ctrlOut(0x9A, 0x1312, v1)
        ctrlOut(0x9A, 0x0F2C, v2)
    }
}

/** FTDI FT232R / FT230X etc. as plain UART. */
class FtdiSerial(conn: UsbDeviceConnection, itf: UsbInterface, eps: Pair<UsbEndpoint, UsbEndpoint>) :
    SerialPort(conn, eps.first, eps.second, listOf(itf)) {
    override val name = "FTDI UART"
    private val mps = eps.first.maxPacketSize.coerceAtLeast(64)

    override fun configure(baud: Int) {
        ctrlOut(0, 0, 0)            // reset
        val d8 = ((3_000_000L * 8) + baud / 2) / baud
        var intPart = (d8 shr 3).toInt()
        val frac = (d8 and 7).toInt()
        val fracCode = intArrayOf(0, 3, 2, 4, 1, 5, 6, 7)[frac]
        if (intPart == 1 && frac == 0) intPart = 0 // 3 Mbaud special case
        val value = intPart or ((fracCode and 3) shl 14)
        val index = fracCode shr 2
        ctrlOut(3, value, index)
        ctrlOut(4, 8, 0)            // 8N1
        ctrlOut(2, 0, 0)            // no flow control
        ctrlOut(9, 1, 0)            // latency 1 ms
        ctrlOut(1, 0x0303, 0)       // DTR+RTS on
    }

    override fun strip(raw: ByteArray, n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(n)
        var p = 0
        while (p < n) {
            val chunk = minOf(mps, n - p)
            if (chunk > 2) out.write(raw, p + 2, chunk - 2)
            p += chunk
        }
        return out.toByteArray()
    }
}

/** USB CDC-ACM (Arduino UNO/Mega 16U2, Leonardo, RP2040, ESP32-S3, CH9102 ...). */
class CdcSerial(
    conn: UsbDeviceConnection, private val ctrlItf: UsbInterface?, dataItf: UsbInterface,
    eps: Pair<UsbEndpoint, UsbEndpoint>
) : SerialPort(conn, eps.first, eps.second, listOfNotNull(ctrlItf, dataItf)) {
    override val name = "USB CDC"

    override fun configure(baud: Int) {
        val idx = ctrlItf?.id ?: 0
        val lc = byteArrayOf(
            (baud and 0xFF).toByte(), (baud shr 8 and 0xFF).toByte(),
            (baud shr 16 and 0xFF).toByte(), (baud shr 24 and 0xFF).toByte(), 0, 0, 8
        )
        try { ctrlOut(0x20, 0, idx, lc, 0x21) } catch (_: IOException) {}
        try { ctrlOut(0x22, 0x03, idx, null, 0x21) } catch (_: IOException) {}
    }

    companion object {
        fun find(dev: UsbDevice): Pair<UsbInterface?, UsbInterface>? {
            var c: UsbInterface? = null
            var d: UsbInterface? = null
            for (k in 0 until dev.interfaceCount) {
                val i = dev.getInterface(k)
                if (i.interfaceClass == UsbConstants.USB_CLASS_COMM && c == null) c = i
                if (i.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA && d == null) d = i
            }
            return if (d != null) c to d else null
        }
    }
}
