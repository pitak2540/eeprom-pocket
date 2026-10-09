package com.eepromtool.pocket.chip

import android.os.SystemClock
import com.eepromtool.pocket.usb.I2cBus
import com.eepromtool.pocket.usb.MicrowireBus
import com.eepromtool.pocket.usb.SpiBus
import com.eepromtool.pocket.usb.bitsOf
import com.eepromtool.pocket.usb.u
import java.io.IOException

enum class Family(val label: String) {
    I2C24("24xx  I2C EEPROM"),
    SPI25EE("25xx / 95xx  SPI EEPROM"),
    SPI25FLASH("25xx  SPI Flash (BIOS)"),
    MW93("93xx  Microwire EEPROM")
}

/**
 * @param addrBytes  24/25: address bytes. 93: address bits in x8 mode.
 * @param extra      24: block bits inside the device address. 93: address bits in x16 mode.
 */
data class Chip(val family: Family, val name: String, val size: Int, val page: Int, val addrBytes: Int, val extra: Int = 0) {
    override fun toString() = "$name  (${fmtSize(size)})"
}

fun fmtSize(n: Int): String = when {
    n >= 1024 * 1024 -> "${n / (1024 * 1024)} MB"
    n >= 1024 -> "${n / 1024} KB"
    else -> "$n B"
}

object ChipDb {
    private const val K = 1024
    val all: Map<Family, List<Chip>> = mapOf(
        Family.I2C24 to listOf(
            Chip(Family.I2C24, "24C01", 128, 8, 1),
            Chip(Family.I2C24, "24C02", 256, 8, 1),
            Chip(Family.I2C24, "24C04", 512, 16, 1, 1),
            Chip(Family.I2C24, "24C08", 1 * K, 16, 1, 2),
            Chip(Family.I2C24, "24C16", 2 * K, 16, 1, 3),
            Chip(Family.I2C24, "24C32", 4 * K, 32, 2),
            Chip(Family.I2C24, "24C64", 8 * K, 32, 2),
            Chip(Family.I2C24, "24C128", 16 * K, 64, 2),
            Chip(Family.I2C24, "24C256", 32 * K, 64, 2),
            Chip(Family.I2C24, "24C512", 64 * K, 128, 2),
            Chip(Family.I2C24, "24C1024 / 24M01", 128 * K, 256, 2, 1),
            Chip(Family.I2C24, "24M02", 256 * K, 256, 2, 2),
        ),
        Family.SPI25EE to listOf(
            Chip(Family.SPI25EE, "25010 / 95010", 128, 16, 1),
            Chip(Family.SPI25EE, "25020 / 95020", 256, 16, 1),
            Chip(Family.SPI25EE, "25040 / 95040", 512, 16, 1),
            Chip(Family.SPI25EE, "25080 / 95080", 1 * K, 16, 2),
            Chip(Family.SPI25EE, "25160 / 95160", 2 * K, 16, 2),
            Chip(Family.SPI25EE, "25320 / 95320", 4 * K, 32, 2),
            Chip(Family.SPI25EE, "25640 / 95640", 8 * K, 32, 2),
            Chip(Family.SPI25EE, "25128 / 95128", 16 * K, 64, 2),
            Chip(Family.SPI25EE, "25256 / 95256", 32 * K, 64, 2),
            Chip(Family.SPI25EE, "25512 / 95512", 64 * K, 128, 2),
            Chip(Family.SPI25EE, "25M01 / 95M01", 128 * K, 256, 3),
            Chip(Family.SPI25EE, "25M02 / 95M02", 256 * K, 256, 3),
        ),
        Family.SPI25FLASH to listOf(
            Chip(Family.SPI25FLASH, "Auto (อ่าน JEDEC ID)", 0, 256, 3),
            Chip(Family.SPI25FLASH, "25x05 (512Kbit)", 64 * K, 256, 3),
            Chip(Family.SPI25FLASH, "25x10 (1Mbit)", 128 * K, 256, 3),
            Chip(Family.SPI25FLASH, "25x20 (2Mbit)", 256 * K, 256, 3),
            Chip(Family.SPI25FLASH, "25x40 (4Mbit)", 512 * K, 256, 3),
            Chip(Family.SPI25FLASH, "25x80 (8Mbit)", 1024 * K, 256, 3),
            Chip(Family.SPI25FLASH, "25x16 (16Mbit)", 2 * K * K, 256, 3),
            Chip(Family.SPI25FLASH, "25x32 (32Mbit)", 4 * K * K, 256, 3),
            Chip(Family.SPI25FLASH, "25x64 (64Mbit)", 8 * K * K, 256, 3),
            Chip(Family.SPI25FLASH, "25x128 (128Mbit)", 16 * K * K, 256, 3),
            Chip(Family.SPI25FLASH, "25x256 (256Mbit)", 32 * K * K, 256, 4),
        ),
        Family.MW93 to listOf(
            Chip(Family.MW93, "93C46", 128, 0, 7, 6),
            Chip(Family.MW93, "93C56", 256, 0, 9, 8),
            Chip(Family.MW93, "93C66", 512, 0, 9, 8),
            Chip(Family.MW93, "93C76", 1 * K, 0, 11, 10),
            Chip(Family.MW93, "93C86", 2 * K, 0, 11, 10),
        )
    )
}

/** Progress callback; throws to cancel. */
fun interface Progress {
    fun update(done: Long, total: Long)
}

interface ChipOps {
    val size: Int
    fun detect(): String
    fun read(p: Progress): ByteArray
    fun write(data: ByteArray, p: Progress)
    fun erase(p: Progress)
}

private fun sleepMs(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

// ======================= 24xx =======================
class I2cEeprom(private val bus: I2cBus, private val chip: Chip) : ChipOps {
    override val size = chip.size
    private val block = if (chip.addrBytes == 1) 256 else 65536
    private val mask = (1 shl chip.extra) - 1

    private fun dev(off: Int) = 0x50 or ((off / block) and mask)
    private fun waddr(off: Int) =
        if (chip.addrBytes == 1) byteArrayOf(off.toByte())
        else byteArrayOf((off shr 8).toByte(), off.toByte())

    override fun detect(): String {
        if (!bus.i2cAckAware) {
            val d = bus.i2cWriteRead(0x50, waddr(0), 8)
            return "อ่าน 8 ไบต์แรก: " + d.joinToString(" ") { "%02X".format(it) } +
                if (d.all { it.u == 0xFF }) "  (อาจไม่มีชิป/ชิปว่าง)" else ""
        }
        val found = (0x50..0x57).filter { a -> try { bus.i2cWrite(a, ByteArray(0)) } catch (_: Exception) { false } }
        return if (found.isEmpty()) "ไม่พบอุปกรณ์ I2C ที่ 0x50-0x57"
        else "พบ I2C ที่แอดเดรส: " + found.joinToString { "0x%02X".format(it) }
    }

    override fun read(p: Progress): ByteArray {
        val out = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = minOf(bus.i2cMaxRead, block - off % block, size - off)
            bus.i2cWriteRead(dev(off), waddr(off), n).copyInto(out, off, 0, n)
            off += n
            p.update(off.toLong(), size.toLong())
        }
        return out
    }

    override fun write(data: ByteArray, p: Progress) {
        var off = 0
        while (off < size) {
            val n = minOf(chip.page - off % chip.page, bus.i2cMaxWrite - chip.addrBytes, size - off)
            val a = waddr(off)
            val buf = ByteArray(a.size + n)
            a.copyInto(buf); System.arraycopy(data, off, buf, a.size, n)
            if (!bus.i2cWrite(dev(off), buf)) throw IOException("เขียนที่ 0x%X ไม่สำเร็จ (NACK) — ตรวจขา WP".format(off))
            waitDone(dev(off))
            off += n
            p.update(off.toLong(), size.toLong())
        }
    }

    private fun waitDone(addr: Int) {
        if (!bus.i2cAckAware) { sleepMs(10); return }
        val t0 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t0 < 30) {
            try { if (bus.i2cWrite(addr, ByteArray(0))) return } catch (_: Exception) {}
        }
    }

    override fun erase(p: Progress) = write(ByteArray(size) { 0xFF.toByte() }, p)
}

// ======================= 25xx EEPROM =======================
class SpiEeprom(private val bus: SpiBus, private val chip: Chip) : ChipOps {
    override val size = chip.size

    private fun cmd(op: Int, off: Int): ByteArray {
        val a8 = if (chip.addrBytes == 1 && (off and 0x100) != 0) 0x08 else 0
        return when (chip.addrBytes) {
            1 -> byteArrayOf((op or a8).toByte(), off.toByte())
            2 -> byteArrayOf(op.toByte(), (off shr 8).toByte(), off.toByte())
            else -> byteArrayOf(op.toByte(), (off shr 16).toByte(), (off shr 8).toByte(), off.toByte())
        }
    }

    private fun rdsr() = bus.spi(byteArrayOf(0x05), 1)[0].u

    override fun detect(): String {
        val sr = rdsr()
        return "Status register = 0x%02X".format(sr) +
            if (sr == 0xFF || sr == 0x00) "  (ถ้าเป็น FF ตลอดอาจไม่มีชิปหรือต่อสายผิด)" else ""
    }

    override fun read(p: Progress): ByteArray {
        val out = ByteArray(size)
        val chunk = if (chip.addrBytes == 1) 256 else 4096
        var off = 0
        while (off < size) {
            val n = minOf(chunk - off % chunk, size - off)
            bus.spi(cmd(0x03, off), n).copyInto(out, off)
            off += n
            p.update(off.toLong(), size.toLong())
        }
        return out
    }

    private fun waitWip(timeoutMs: Long) {
        val t0 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t0 < timeoutMs) if (rdsr() and 1 == 0) return
        throw IOException("ชิปไม่ตอบ (WIP ค้าง)")
    }

    override fun write(data: ByteArray, p: Progress) {
        bus.spi(byteArrayOf(0x06), 0)
        bus.spi(byteArrayOf(0x01, 0x00), 0)  // clear block protect
        waitWip(50)
        var off = 0
        while (off < size) {
            val n = minOf(chip.page - off % chip.page, size - off)
            bus.spi(byteArrayOf(0x06), 0)
            bus.spi(cmd(0x02, off) + data.copyOfRange(off, off + n), 0)
            waitWip(50)
            off += n
            p.update(off.toLong(), size.toLong())
        }
        bus.spi(byteArrayOf(0x04), 0)
    }

    override fun erase(p: Progress) = write(ByteArray(size) { 0xFF.toByte() }, p)
}

// ======================= 25xx SPI Flash =======================
class SpiFlash(private val bus: SpiBus, sizeHint: Int) : ChipOps {
    override var size = sizeHint
        private set
    private val addr4 get() = size > 16 * 1024 * 1024

    companion object {
        val VENDORS = mapOf(
            0xEF to "Winbond", 0xC2 to "Macronix", 0xC8 to "GigaDevice", 0x1C to "EON",
            0x20 to "Micron/XMC", 0x68 to "Boya", 0x85 to "Puya", 0x9D to "ISSI", 0xBF to "SST",
            0x1F to "Atmel/Adesto", 0x5E to "Zbit", 0x0B to "XTX", 0xA1 to "Fudan", 0x37 to "AMIC",
            0x8C to "ESMT", 0xBA to "Zetta", 0x46 to "XMC", 0x01 to "Spansion/Cypress", 0x62 to "ON Semi",
            0x7F to "PMC"
        )

        fun sizeFromId(id: ByteArray): Int {
            val c = id[2].u
            return if (c in 0x10..0x19) 1 shl c else 0
        }
    }

    fun jedec(): ByteArray = bus.spi(byteArrayOf(0x9F.toByte()), 3)

    override fun detect(): String {
        val id = jedec()
        val hex = id.joinToString(" ") { "%02X".format(it) }
        if (id.all { it.u == 0xFF } || id.all { it.u == 0x00 }) return "JEDEC ID = $hex  → ไม่พบชิป (ตรวจการต่อสาย/ไฟ)"
        val vend = VENDORS[id[0].u] ?: "ไม่ทราบผู้ผลิต"
        val s = sizeFromId(id)
        if (s > 0) size = s
        return "JEDEC ID = $hex  → $vend, ขนาด ${if (s > 0) fmtSize(s) else "ไม่ทราบ (เลือกเอง)"}"
    }

    fun detectSizeIfNeeded() {
        if (size <= 0) {
            val s = sizeFromId(jedec())
            if (s <= 0) throw IOException("ไม่ทราบขนาดชิป — กด 'ตรวจ ID' หรือเลือกรุ่นเอง")
            size = s
        }
    }

    private fun addrCmd(op: Int, off: Int): ByteArray =
        if (addr4) byteArrayOf(op.toByte(), (off ushr 24).toByte(), (off shr 16).toByte(), (off shr 8).toByte(), off.toByte())
        else byteArrayOf(op.toByte(), (off shr 16).toByte(), (off shr 8).toByte(), off.toByte())

    private fun rdsr() = bus.spi(byteArrayOf(0x05), 1)[0].u
    private fun wren() = bus.spi(byteArrayOf(0x06), 0)

    private fun waitWip(timeoutMs: Long, tick: ((Long) -> Unit)? = null) {
        val t0 = SystemClock.uptimeMillis()
        while (true) {
            if (rdsr() and 1 == 0) return
            val el = SystemClock.uptimeMillis() - t0
            tick?.invoke(el)
            if (el > timeoutMs) throw IOException("ชิปไม่ตอบ (WIP ค้าง)")
            if (tick != null) sleepMs(50)
        }
    }

    override fun read(p: Progress): ByteArray {
        detectSizeIfNeeded()
        val out = ByteArray(size)
        val chunk = 64 * 1024
        var off = 0
        while (off < size) {
            val n = minOf(chunk, size - off)
            bus.spi(addrCmd(if (addr4) 0x13 else 0x03, off), n).copyInto(out, off)
            off += n
            p.update(off.toLong(), size.toLong())
        }
        return out
    }

    private fun unprotect() {
        wren()
        bus.spi(byteArrayOf(0x01, 0x00), 0)
        waitWip(500)
    }

    override fun erase(p: Progress) {
        detectSizeIfNeeded()
        unprotect()
        wren()
        bus.spi(byteArrayOf(0xC7.toByte()), 0)
        val est = maxOf(5_000L, size / 1024L * 40) // rough estimate for progress
        waitWip(400_000) { el -> p.update(minOf(el, est - 1), est) }
        p.update(est, est)
    }

    override fun write(data: ByteArray, p: Progress) {
        detectSizeIfNeeded()
        unprotect()
        var off = 0
        while (off < size) {
            val n = minOf(256 - off % 256, size - off)
            var blank = true
            for (k in off until off + n) if (data[k].u != 0xFF) { blank = false; break }
            if (!blank) {
                wren()
                bus.spi(addrCmd(if (addr4) 0x12 else 0x02, off) + data.copyOfRange(off, off + n), 0)
                waitWip(100)
            }
            off += n
            p.update(off.toLong(), size.toLong())
        }
    }
}

// ======================= 93xx =======================
class MicrowireEeprom(
    private val bus: MicrowireBus, chip: Chip, private val x16: Boolean, private val swap: Boolean
) : ChipOps {
    override val size = chip.size
    private val abits = if (x16) chip.extra else chip.addrBytes
    private val wordBytes = if (x16) 2 else 1
    private val words = size / wordBytes

    private fun cmd(op: Int, addr: Int): BooleanArray =
        bitsOf(((0b100 or op).toLong() shl abits) or addr.toLong(), 3 + abits)

    private fun ewen() = bus.mwTransfer(cmd(0b00, 0b11 shl (abits - 2)), 0)
    private fun ewds() = bus.mwTransfer(cmd(0b00, 0), 0)

    override fun detect(): String {
        val d = bus.mwTransfer(cmd(0b10, 0), wordBytes * 8)
        return "อ่านเวิร์ดแรก: " + d.joinToString(" ") { "%02X".format(it) } +
            "  (โหมด ${if (x16) "x16" else "x8"}, ${abits} address bits)"
    }

    override fun read(p: Progress): ByteArray {
        val out = ByteArray(size)
        for (w in 0 until words) {
            val d = bus.mwTransfer(cmd(0b10, w), wordBytes * 8)
            if (x16) {
                val hi = d[0]; val lo = d[1]
                if (swap) { out[w * 2] = lo; out[w * 2 + 1] = hi } else { out[w * 2] = hi; out[w * 2 + 1] = lo }
            } else out[w] = d[0]
            if (w and 7 == 7 || w == words - 1) p.update(((w + 1) * wordBytes).toLong(), size.toLong())
        }
        return out
    }

    override fun write(data: ByteArray, p: Progress) {
        ewen()
        try {
            for (w in 0 until words) {
                val value = if (x16) {
                    val a = data[w * 2].u; val b = data[w * 2 + 1].u
                    if (swap) (b shl 8) or a else (a shl 8) or b
                } else data[w].u
                val c = cmd(0b01, w) + bitsOf(value.toLong(), wordBytes * 8)
                bus.mwTransfer(c, 0)
                if (!bus.mwWaitReady(20)) throw IOException("ชิปไม่ตอบหลังเขียนเวิร์ด $w (ตรวจขา DO/ORG)")
                p.update(((w + 1) * wordBytes).toLong(), size.toLong())
            }
        } finally { ewds() }
    }

    override fun erase(p: Progress) {
        ewen()
        try {
            bus.mwTransfer(cmd(0b00, 0b10 shl (abits - 2)), 0)
            if (!bus.mwWaitReady(100)) throw IOException("ERAL ไม่ตอบ")
        } finally { ewds() }
        p.update(1, 1)
    }
}
