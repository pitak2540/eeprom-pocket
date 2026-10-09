package com.eepromtool.pocket.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * Talks to firmware/eeprom_bridge.ino running on an Arduino (Nano CH340, UNO, FT232 boards ...).
 * Frame host->dev : A5 cmd lenL lenH payload
 * Frame dev->host : 5A status lenL lenH data
 */
class BridgeProgrammer(private val port: SerialPort) : Programmer {
    override val title get() = "Arduino Bridge ผ่าน ${port.name}"
    override val i2cMaxRead = 32
    override val i2cMaxWrite = 32
    override val i2cAckAware = true

    fun handshake(): String {
        Thread.sleep(2200) // Arduino auto-reset after DTR
        port.drain()
        var last: Exception? = null
        repeat(5) {
            try {
                val (st, d) = cmd(0x01, ByteArray(0), 600)
                if (st == 0) return String(d)
            } catch (e: Exception) { last = e; port.drain() }
        }
        throw IOException("ไม่พบเฟิร์มแวร์ EEPROM Bridge บนบอร์ด (${last?.message ?: "ไม่มีการตอบกลับ"})")
    }

    private fun cmd(c: Int, payload: ByteArray, timeout: Int = 3000): Pair<Int, ByteArray> {
        val f = ByteArray(4 + payload.size)
        f[0] = 0xA5.toByte(); f[1] = c.toByte()
        f[2] = (payload.size and 0xFF).toByte(); f[3] = (payload.size shr 8).toByte()
        payload.copyInto(f, 4)
        port.write(f)
        var guard = 0
        while (port.readExact(1, timeout)[0].u != 0x5A) {
            if (++guard > 2000) throw IOException("Bridge: ข้อมูลตอบกลับผิดรูปแบบ")
        }
        val h = port.readExact(3, timeout)
        val len = h[1].u or (h[2].u shl 8)
        val d = if (len > 0) port.readExact(len, timeout) else ByteArray(0)
        return h[0].u to d
    }

    override fun i2cWrite(addr: Int, data: ByteArray): Boolean {
        val p = ByteArray(1 + data.size); p[0] = addr.toByte(); data.copyInto(p, 1)
        return cmd(0x10, p).first == 0
    }

    override fun i2cWriteRead(addr: Int, w: ByteArray, rlen: Int): ByteArray {
        val p = ByteArray(2 + w.size); p[0] = addr.toByte(); p[1] = rlen.toByte(); w.copyInto(p, 2)
        val (st, d) = cmd(0x11, p)
        if (st != 0 || d.size < rlen) throw IOException("I2C ไม่มี ACK ที่แอดเดรส 0x%02X (ตรวจการต่อสาย/ไฟเลี้ยง)".format(addr))
        return d
    }

    override fun spi(write: ByteArray, readLen: Int): ByteArray {
        val total = write.size + readLen
        val tx = ByteArray(total) { 0xFF.toByte() }
        write.copyInto(tx)
        val rx = ByteArray(total)
        var off = 0
        while (off < total) {
            val n = minOf(256, total - off)
            val flags = (if (off == 0) 2 else 0) or (if (off + n >= total) 1 else 0)
            val p = ByteArray(n + 1); p[0] = flags.toByte(); System.arraycopy(tx, off, p, 1, n)
            val (st, d) = cmd(0x20, p)
            if (st != 0 || d.size < n) throw IOException("Bridge: SPI ผิดพลาด")
            d.copyInto(rx, off, 0, n)
            off += n
        }
        return rx.copyOfRange(write.size, total)
    }

    override fun mwTransfer(out: BooleanArray, readBits: Int): ByteArray {
        val packed = packBits(out.size) { out[it] }
        val p = ByteArray(4 + packed.size)
        p[0] = (out.size and 0xFF).toByte(); p[1] = (out.size shr 8).toByte()
        p[2] = (readBits and 0xFF).toByte(); p[3] = (readBits shr 8).toByte()
        packed.copyInto(p, 4)
        val (st, d) = cmd(0x30, p)
        if (st != 0) throw IOException("Bridge: Microwire ผิดพลาด")
        return d
    }

    override fun mwWaitReady(timeoutMs: Int): Boolean =
        cmd(0x31, bytes(timeoutMs and 0xFF, timeoutMs shr 8), timeoutMs + 2000).first == 0

    override fun close() = port.close()
}

object DeviceFactory {
    enum class Kind(val label: String) {
        CH341A("CH341A Programmer"),
        FTDI_MPSSE("FTDI FT232H/FT2232H/FT4232H (MPSSE)"),
        BRIDGE_CH340("CH340/CH341 Serial → Arduino Bridge"),
        BRIDGE_FTDI("FTDI FT232R/FT-X Serial → Arduino Bridge"),
        BRIDGE_CDC("USB CDC Serial → Arduino Bridge")
    }

    const val BRIDGE_BAUD = 1_000_000

    fun classify(d: UsbDevice): Kind? {
        val v = d.vendorId
        val p = d.productId
        return when {
            v == 0x1A86 && p == 0x5512 -> Kind.CH341A
            v == 0x1A86 && p in setOf(0x7523, 0x5523, 0x7522) -> Kind.BRIDGE_CH340
            v == 0x0403 && p in setOf(0x6010, 0x6011, 0x6014) -> Kind.FTDI_MPSSE
            v == 0x0403 && p in setOf(0x6001, 0x6015) -> Kind.BRIDGE_FTDI
            CdcSerial.find(d) != null -> Kind.BRIDGE_CDC
            else -> null
        }
    }

    private fun ftdiName(p: Int) = when (p) {
        0x6010 -> "FT2232H"; 0x6011 -> "FT4232H"; 0x6014 -> "FT232H"; else -> "FTDI"
    }

    /** Blocking — call from a background thread. */
    fun open(mgr: UsbManager, d: UsbDevice): Programmer {
        val kind = classify(d) ?: throw IOException("อุปกรณ์นี้ไม่รองรับ")
        val conn = mgr.openDevice(d) ?: throw IOException("เปิดอุปกรณ์ไม่ได้ (ไม่ได้รับสิทธิ์?)")
        try {
            return when (kind) {
                Kind.CH341A, Kind.FTDI_MPSSE -> {
                    val itf = d.getInterface(0)
                    if (!conn.claimInterface(itf, true)) throw IOException("claim interface ไม่ได้")
                    val (i, o) = SerialPort.bulkPair(itf)
                    val link = UsbLink(conn, itf, o, i)
                    if (kind == Kind.CH341A) Ch341a(link) else FtdiMpsse(link, ftdiName(d.productId))
                }
                Kind.BRIDGE_CH340, Kind.BRIDGE_FTDI -> {
                    val itf = d.getInterface(0)
                    if (!conn.claimInterface(itf, true)) throw IOException("claim interface ไม่ได้")
                    val eps = SerialPort.bulkPair(itf)
                    val port = if (kind == Kind.BRIDGE_CH340) Ch340Serial(conn, itf, eps) else FtdiSerial(conn, itf, eps)
                    port.configure(BRIDGE_BAUD)
                    BridgeProgrammer(port).also { it.handshake() }
                }
                Kind.BRIDGE_CDC -> {
                    val (c, data) = CdcSerial.find(d)!!
                    if (c != null) conn.claimInterface(c, true)
                    if (!conn.claimInterface(data, true)) throw IOException("claim interface ไม่ได้")
                    val port = CdcSerial(conn, c, data, SerialPort.bulkPair(data))
                    port.configure(BRIDGE_BAUD)
                    BridgeProgrammer(port).also { it.handshake() }
                }
            }
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) {}
            throw e
        }
    }

    fun describe(d: UsbDevice): String =
        "%s  [%04X:%04X]".format(classify(d)?.label ?: "?", d.vendorId, d.productId)

    @Suppress("unused")
    private fun isBulk(t: Int) = t == UsbConstants.USB_ENDPOINT_XFER_BULK
}
