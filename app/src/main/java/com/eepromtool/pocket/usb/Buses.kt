package com.eepromtool.pocket.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException

/** I2C bus (24xx). addr = 7-bit address. */
interface I2cBus {
    val i2cMaxRead: Int
    val i2cMaxWrite: Int
    /** true = the adapter can report ACK/NACK (used for ACK polling after a page write) */
    val i2cAckAware: Boolean
    fun i2cWrite(addr: Int, data: ByteArray): Boolean
    fun i2cWriteRead(addr: Int, w: ByteArray, rlen: Int): ByteArray
}

/** SPI bus (25xx). One call = CS low, clock out [write], then clock in [readLen] bytes, CS high. */
interface SpiBus {
    fun spi(write: ByteArray, readLen: Int): ByteArray
}

/** Microwire bus (93xx). CS is active HIGH. */
interface MicrowireBus {
    /** CS high, clock out bits, clock in readBits (MSB first, packed), CS low. */
    fun mwTransfer(out: BooleanArray, readBits: Int): ByteArray
    /** CS low->high, wait until DO goes high (write cycle done), CS low. */
    fun mwWaitReady(timeoutMs: Int): Boolean
}

interface Programmer : I2cBus, SpiBus, MicrowireBus {
    val title: String
    fun close()
}

class UsbLink(
    val conn: UsbDeviceConnection,
    val iface: UsbInterface,
    val epOut: UsbEndpoint,
    val epIn: UsbEndpoint
) {
    fun write(data: ByteArray, timeout: Int = 3000) {
        var off = 0
        while (off < data.size) {
            val n = conn.bulkTransfer(epOut, data, off, data.size - off, timeout)
            if (n < 0) throw IOException("USB write ล้มเหลว")
            off += n
            if (data.isEmpty()) break
        }
    }

    fun control(req: Int, value: Int, index: Int): Int =
        conn.controlTransfer(0x40, req, value, index, null, 0, 1000)

    fun close() {
        try { conn.releaseInterface(iface) } catch (_: Exception) {}
        conn.close()
    }
}

fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
val Byte.u: Int get() = toInt() and 0xFF

fun packBits(n: Int, bit: (Int) -> Boolean): ByteArray {
    val out = ByteArray((n + 7) / 8)
    for (i in 0 until n) if (bit(i)) out[i shr 3] = (out[i shr 3].toInt() or (0x80 ushr (i and 7))).toByte()
    return out
}

fun bitsOf(value: Long, count: Int): BooleanArray =
    BooleanArray(count) { ((value ushr (count - 1 - it)) and 1L) == 1L }
