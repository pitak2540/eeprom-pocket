package com.eepromtool.pocket.usb

import android.hardware.usb.UsbRequest
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * CH341A in programmer (EPP/I2C/SPI) mode, VID 1A86 PID 5512.
 * I2C  : native I2C stream
 * SPI  : native SPI stream (LSB-first on the wire -> bytes are bit-reversed), CS = D0
 * 93xx : bit-banged through the UIO stream on the 25xx socket pins
 *        CS = D0 (pin1), SK = D3 (pin6), DI = D5 (pin5), DO = D7 (pin2)
 */
class Ch341a(private val link: UsbLink) : Programmer {
    override val title = "CH341A (โหมด Programmer)"
    override val i2cMaxRead = 16
    override val i2cMaxWrite = 64
    override val i2cAckAware = false

    private class Pkt(val data: ByteArray, val inLen: Int)

    companion object {
        const val PKT = 32
        const val SPI_STREAM = 0xA8
        const val I2C_STREAM = 0xAA
        const val UIO_STREAM = 0xAB
        const val I2C_STA = 0x74
        const val I2C_STO = 0x75
        const val I2C_OUT = 0x80
        const val I2C_IN = 0xC0
        const val I2C_SET = 0x60
        const val I2C_END = 0x00
        const val UIO_IN = 0x00
        const val UIO_DIR = 0x40
        const val UIO_OUT = 0x80
        const val UIO_US = 0xC0
        const val UIO_END = 0x20

        private val REV = ByteArray(256) { i ->
            var x = i; var r = 0
            repeat(8) { r = (r shl 1) or (x and 1); x = x shr 1 }
            r.toByte()
        }
        fun rev(b: Byte): Byte = REV[b.u]
    }

    init {
        run(listOf(Pkt(bytes(I2C_STREAM, I2C_SET or 1, I2C_END), 0)))            // I2C 100 kHz, SPI single
        run(listOf(Pkt(bytes(UIO_STREAM, UIO_OUT or 0x37, UIO_DIR or 0x3F, UIO_END), 0)))
    }

    /** Send packets, collecting responses. IN requests are queued before the OUT data (pipelined). */
    private fun run(pkts: List<Pkt>): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < pkts.size) {
            val group = pkts.subList(i, minOf(i + 32, pkts.size))
            val reqs = ArrayList<Pair<UsbRequest, ByteBuffer>>()
            try {
                for (p in group) if (p.inLen > 0) {
                    val r = UsbRequest()
                    if (!r.initialize(link.conn, link.epIn)) throw IOException("UsbRequest init ล้มเหลว")
                    val b = ByteBuffer.allocate(PKT)
                    r.clientData = reqs.size
                    reqs.add(r to b)
                    if (!r.queue(b)) throw IOException("UsbRequest queue ล้มเหลว")
                }
                for (p in group) link.write(p.data)
                var remaining = reqs.size
                val done = BooleanArray(reqs.size)
                while (remaining > 0) {
                    val r = link.conn.requestWait(3000) ?: throw IOException("CH341A ไม่ตอบสนอง")
                    val idx = r.clientData as? Int ?: continue
                    if (idx in done.indices && !done[idx]) { done[idx] = true; remaining-- }
                }
                for ((_, b) in reqs) out.write(b.array(), 0, b.position())
            } catch (e: Exception) {
                for ((r, _) in reqs) try { r.cancel() } catch (_: Exception) {}
                throw if (e is IOException) e else IOException("CH341A: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                for ((r, _) in reqs) try { r.close() } catch (_: Exception) {}
            }
            i += group.size
        }
        return out.toByteArray()
    }

    // ---------------- I2C ----------------
    override fun i2cWrite(addr: Int, data: ByteArray): Boolean {
        val all = ByteArray(data.size + 1)
        all[0] = (addr shl 1).toByte()
        data.copyInto(all, 1)
        val pkts = ArrayList<Pkt>()
        var off = 0
        var first = true
        while (true) {
            val p = ByteArrayOutputStream()
            p.write(I2C_STREAM)
            if (first) p.write(I2C_STA)
            val room = PKT - p.size() - 1 - 2
            val n = minOf(room, all.size - off)
            if (n > 0) { p.write(I2C_OUT or n); p.write(all, off, n); off += n }
            first = false
            if (off >= all.size) {
                p.write(I2C_STO); p.write(I2C_END)
                pkts.add(Pkt(p.toByteArray(), 0))
                break
            }
            pkts.add(Pkt(p.toByteArray(), 0))
        }
        run(pkts)
        return true
    }

    override fun i2cWriteRead(addr: Int, w: ByteArray, rlen: Int): ByteArray {
        require(rlen in 1..i2cMaxRead)
        val p = ByteArrayOutputStream()
        p.write(I2C_STREAM); p.write(I2C_STA)
        if (w.isNotEmpty()) {
            p.write(I2C_OUT or (w.size + 1)); p.write(addr shl 1); p.write(w, 0, w.size)
            p.write(I2C_STA)
        }
        p.write(I2C_OUT or 1); p.write((addr shl 1) or 1)
        if (rlen > 1) p.write(I2C_IN or (rlen - 1))
        p.write(I2C_IN)
        p.write(I2C_STO); p.write(I2C_END)
        val r = run(listOf(Pkt(p.toByteArray(), rlen)))
        if (r.size < rlen) throw IOException("CH341A: อ่าน I2C ได้ ${r.size}/$rlen ไบต์")
        return r.copyOf(rlen)
    }

    // ---------------- SPI ----------------
    override fun spi(write: ByteArray, readLen: Int): ByteArray {
        val total = write.size + readLen
        val tx = ByteArray(total) { 0xFF.toByte() }
        write.copyInto(tx)
        val pkts = ArrayList<Pkt>(total / 31 + 3)
        pkts.add(Pkt(bytes(UIO_STREAM, UIO_OUT or 0x36, UIO_DIR or 0x3F, UIO_END), 0)) // CS low
        var off = 0
        while (off < total) {
            val n = minOf(PKT - 1, total - off)
            val d = ByteArray(n + 1)
            d[0] = SPI_STREAM.toByte()
            for (k in 0 until n) d[k + 1] = rev(tx[off + k])
            pkts.add(Pkt(d, n))
            off += n
        }
        pkts.add(Pkt(bytes(UIO_STREAM, UIO_OUT or 0x37, UIO_END), 0)) // CS high
        val rx = run(pkts)
        if (rx.size < total) throw IOException("CH341A: SPI ได้ ${rx.size}/$total ไบต์")
        return ByteArray(readLen) { rev(rx[write.size + it]) }
    }

    // ---------------- Microwire (bit-bang) ----------------
    // Same pin map as IMSProg: CS = D0, SK = D3, DI = D5, DO = D7 (input). Other outputs low.
    private fun mw(cs: Int, clk: Int, di: Int) = UIO_OUT or cs or (clk shl 3) or (di shl 5)
    private val mwDelay = UIO_US or 2   // >= 1 us per clock phase (93xx at 2.5V needs 1 us)

    private fun uio(ops: IntArray): ByteArray {
        val pkts = ArrayList<Pkt>()
        var i = 0
        while (i < ops.size) {
            val n = minOf(PKT - 2, ops.size - i)
            val d = ByteArray(n + 2)
            d[0] = UIO_STREAM.toByte()
            var ins = 0
            for (k in 0 until n) {
                val o = ops[i + k]
                d[k + 1] = o.toByte()
                if (o == UIO_IN) ins++
            }
            d[n + 1] = UIO_END.toByte()
            pkts.add(Pkt(d, ins))
            i += n
        }
        val r = run(pkts)
        val expected = ops.count { it == UIO_IN }
        if (r.size < expected) throw IOException("CH341A: อ่านขา DO ได้ ${r.size}/$expected")
        return r
    }

    /**
     * CS low -> CS high, clock out [out] (data set while SK low, latched on SK rising edge),
     * then clock in [readBits]: SK high (chip shifts next bit out), SK low, sample DO. CS low at end.
     */
    override fun mwTransfer(out: BooleanArray, readBits: Int): ByteArray {
        val ops = ArrayList<Int>(out.size * 4 + readBits * 5 + 12)
        ops += UIO_DIR or 0x3F
        ops += mw(0, 0, 0); ops += mwDelay
        ops += mw(1, 0, 0); ops += mwDelay
        var last = 0
        for (b in out) {
            val v = if (b) 1 else 0
            ops += mw(1, 0, v); ops += mwDelay
            ops += mw(1, 1, v); ops += mwDelay
            last = v
        }
        ops += mw(1, 0, last); ops += mwDelay
        ops += mw(1, 0, 0)
        repeat(readBits) {
            ops += mw(1, 1, 0); ops += mwDelay
            ops += mw(1, 0, 0); ops += mwDelay
            ops += UIO_IN
        }
        ops += mw(0, 0, 0)     // CS low: starts the self-timed write/erase cycle
        ops += mwDelay
        val r = uio(ops.toIntArray())
        return packBits(readBits) { (r[it].toInt() and 0x80) != 0 }
    }

    /** After WRITE/ERASE/ERAL: CS low -> high, chip drives DO low while busy, high when ready. */
    override fun mwWaitReady(timeoutMs: Int): Boolean {
        uio(intArrayOf(UIO_DIR or 0x3F, mw(0, 0, 0), UIO_US or 5, mw(1, 0, 0), UIO_US or 5))
        val t0 = SystemClock.uptimeMillis()
        var ok = false
        while (true) {
            val r = uio(intArrayOf(UIO_IN))
            if ((r[0].toInt() and 0x80) != 0) { ok = true; break }
            if (SystemClock.uptimeMillis() - t0 > timeoutMs) break
        }
        uio(intArrayOf(mw(0, 0, 0), mwDelay))
        return ok
    }

    override fun close() = link.close()
}
