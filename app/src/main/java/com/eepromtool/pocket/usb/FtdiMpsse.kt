package com.eepromtool.pocket.usb

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * FTDI FT232H / FT2232H / FT4232H in MPSSE mode (channel A).
 * ADBUS0 = SCK/SCL, ADBUS1 = MOSI/SDA-out/DI, ADBUS2 = MISO/SDA-in/DO, ADBUS3 = CS
 * I2C: tie AD1 + AD2 together = SDA (pull-ups 4.7k to VCC).
 */
class FtdiMpsse(private val link: UsbLink, chipName: String) : Programmer {
    override val title = "$chipName (MPSSE)"
    override val i2cMaxRead = 64
    override val i2cMaxWrite = 260
    override val i2cAckAware = true

    private val maxPkt = link.epIn.maxPacketSize.coerceAtLeast(64)
    private val rbuf = ByteArray(maxPkt * 16)
    private enum class Mode { NONE, SPI, I2C, MW }
    private var mode = Mode.NONE

    init {
        ctrl(0, 0)          // reset
        ctrl(0, 1); ctrl(0, 2) // purge rx/tx
        ctrl(9, 2)          // latency timer 2 ms
        ctrl(0x0B, 0x0000)  // bitmode reset
        ctrl(0x0B, 0x0200)  // MPSSE
        Thread.sleep(50)
        drain()
    }

    private fun ctrl(req: Int, value: Int) {
        if (link.control(req, value, 1) < 0) throw IOException("FTDI control 0x%02X ล้มเหลว".format(req))
    }

    private fun drain() {
        repeat(50) {
            val r = link.conn.bulkTransfer(link.epIn, rbuf, rbuf.size, 20)
            if (r <= 2) return
        }
    }

    private fun readExact(n: Int, timeoutMs: Long = 3000): ByteArray {
        val out = ByteArray(n)
        var got = 0
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (got < n) {
            val r = link.conn.bulkTransfer(link.epIn, rbuf, rbuf.size, 200)
            if (r > 2) {
                var p = 0
                while (p < r) {
                    val chunk = minOf(maxPkt, r - p)
                    for (k in 2 until chunk) if (got < n) out[got++] = rbuf[p + k]
                    p += chunk
                }
            }
            if (got < n && SystemClock.uptimeMillis() > deadline) throw IOException("FTDI ไม่ตอบสนอง ($got/$n)")
        }
        return out
    }

    private fun ByteArrayOutputStream.pins(value: Int, dir: Int) { write(0x80); write(value); write(dir) }
    private fun ByteArrayOutputStream.div(d: Int) { write(0x86); write(d and 0xFF); write(d shr 8) }

    private fun ensure(m: Mode) {
        if (mode == m) return
        val c = ByteArrayOutputStream()
        c.write(0x8A); c.write(0x97)
        when (m) {
            Mode.SPI -> { c.write(0x8D); c.div(29); c.write(0x85); c.pins(0x08, 0x0B) }   // 1 MHz
            Mode.I2C -> { c.write(0x8C); c.div(199); c.write(0x85); c.pins(0x03, 0x03) }  // ~100 kHz (3-phase)
            Mode.MW -> { c.write(0x8D); c.div(59); c.write(0x85); c.pins(0x00, 0x0B) }    // 500 kHz
            Mode.NONE -> {}
        }
        link.write(c.toByteArray())
        mode = m
    }

    // ---------------- SPI ----------------
    override fun spi(write: ByteArray, readLen: Int): ByteArray {
        ensure(Mode.SPI)
        val total = write.size + readLen
        val tx = ByteArray(total) { 0xFF.toByte() }
        write.copyInto(tx)
        val rx = ByteArray(total)
        var off = 0
        while (off < total) {
            val n = minOf(4096, total - off)
            val c = ByteArrayOutputStream(n + 16)
            if (off == 0) c.pins(0x00, 0x0B)
            c.write(0x31); c.write((n - 1) and 0xFF); c.write((n - 1) shr 8)
            c.write(tx, off, n)
            if (off + n >= total) c.pins(0x08, 0x0B)
            c.write(0x87)
            link.write(c.toByteArray())
            readExact(n).copyInto(rx, off)
            off += n
        }
        return rx.copyOfRange(write.size, total)
    }

    // ---------------- I2C ----------------
    private fun ByteArrayOutputStream.i2cStart() {
        pins(0x02, 0x03)
        repeat(4) { pins(0x03, 0x03) }
        repeat(4) { pins(0x01, 0x03) }
        pins(0x00, 0x03)
    }

    private fun ByteArrayOutputStream.i2cStop() {
        repeat(4) { pins(0x00, 0x03) }
        repeat(4) { pins(0x01, 0x03) }
        repeat(4) { pins(0x03, 0x03) }
    }

    private fun ByteArrayOutputStream.i2cOut(b: Int) {
        write(0x11); write(0); write(0); write(b and 0xFF)
        pins(0x00, 0x01)
        write(0x22); write(0)
        pins(0x02, 0x03)
    }

    private fun ByteArrayOutputStream.i2cIn(ack: Boolean) {
        pins(0x00, 0x01)
        write(0x20); write(0); write(0)
        pins(if (ack) 0x00 else 0x02, 0x03)
        write(0x13); write(0); write(if (ack) 0x00 else 0xFF)
        pins(0x02, 0x03)
    }

    override fun i2cWrite(addr: Int, data: ByteArray): Boolean {
        ensure(Mode.I2C)
        val c = ByteArrayOutputStream()
        c.i2cStart()
        c.i2cOut(addr shl 1)
        for (b in data) c.i2cOut(b.u)
        c.i2cStop()
        c.write(0x87)
        link.write(c.toByteArray())
        val r = readExact(1 + data.size)
        return r.all { (it.toInt() and 1) == 0 }
    }

    override fun i2cWriteRead(addr: Int, w: ByteArray, rlen: Int): ByteArray {
        ensure(Mode.I2C)
        val c = ByteArrayOutputStream()
        var acks = 0
        if (w.isNotEmpty()) {
            c.i2cStart(); c.i2cOut(addr shl 1); acks++
            for (b in w) { c.i2cOut(b.u); acks++ }
        }
        c.i2cStart(); c.i2cOut((addr shl 1) or 1); acks++
        for (i in 0 until rlen) c.i2cIn(i < rlen - 1)
        c.i2cStop()
        c.write(0x87)
        link.write(c.toByteArray())
        val r = readExact(acks + rlen)
        if ((r[0].toInt() and 1) != 0) throw IOException("I2C ไม่มี ACK ที่แอดเดรส 0x%02X (ตรวจการต่อสาย/ไฟเลี้ยง)".format(addr))
        return r.copyOfRange(acks, acks + rlen)
    }

    // ---------------- Microwire ----------------
    override fun mwTransfer(out: BooleanArray, readBits: Int): ByteArray {
        ensure(Mode.MW)
        val c = ByteArrayOutputStream()
        c.pins(0x00, 0x0B); c.pins(0x08, 0x0B)
        var i = 0
        while (i < out.size) {
            val n = minOf(8, out.size - i)
            var v = 0
            for (k in 0 until n) if (out[i + k]) v = v or (0x80 ushr k)
            c.write(0x13); c.write(n - 1); c.write(v)
            i += n
        }
        c.pins(0x08, 0x0B)
        val nBytes = readBits / 8
        val rem = readBits % 8
        var resp = 0
        if (nBytes > 0) { c.write(0x24); c.write((nBytes - 1) and 0xFF); c.write((nBytes - 1) shr 8); resp += nBytes }
        if (rem > 0) { c.write(0x26); c.write(rem - 1); resp += 1 }
        c.pins(0x00, 0x0B)
        c.write(0x87)
        link.write(c.toByteArray())
        val r = if (resp > 0) readExact(resp) else ByteArray(0)
        val o = ByteArray((readBits + 7) / 8)
        for (k in 0 until nBytes) o[k] = r[k]
        if (rem > 0) {
            val v = r[nBytes].toInt() and ((1 shl rem) - 1)
            o[nBytes] = (v shl (8 - rem)).toByte()
        }
        return o
    }

    override fun mwWaitReady(timeoutMs: Int): Boolean {
        ensure(Mode.MW)
        val c = ByteArrayOutputStream()
        c.pins(0x00, 0x0B); c.pins(0x08, 0x0B)
        link.write(c.toByteArray())
        val t0 = SystemClock.uptimeMillis()
        var ok = false
        while (SystemClock.uptimeMillis() - t0 <= timeoutMs) {
            link.write(bytes(0x81, 0x87))
            val r = readExact(1)
            if ((r[0].toInt() and 0x04) != 0) { ok = true; break }
        }
        link.write(bytes(0x80, 0x00, 0x0B))
        return ok
    }

    override fun close() {
        try { ctrl(0x0B, 0x0000) } catch (_: Exception) {}
        link.close()
    }
}
