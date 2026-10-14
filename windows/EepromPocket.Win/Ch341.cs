using System.Runtime.InteropServices;

namespace EepromPocket;

/// <summary>WCH CH341DLL.DLL (installed by the CH341PAR driver). 32-bit, stdcall.</summary>
internal static class Ch341Native
{
    private const string DLL = "CH341DLL.DLL";

    [DllImport(DLL)] public static extern IntPtr CH341OpenDevice(uint iIndex);
    [DllImport(DLL)] public static extern void CH341CloseDevice(uint iIndex);
    [DllImport(DLL)] public static extern uint CH341GetVersion();
    [DllImport(DLL)] public static extern uint CH341GetVerIC(uint iIndex);
    [DllImport(DLL)] public static extern bool CH341SetStream(uint iIndex, uint iMode);
    [DllImport(DLL)] public static extern bool CH341StreamI2C(uint iIndex, uint iWriteLength, byte[] iWriteBuffer, uint iReadLength, byte[] oReadBuffer);
    [DllImport(DLL)] public static extern bool CH341StreamSPI4(uint iIndex, uint iChipSelect, uint iLength, byte[] ioBuffer);
    [DllImport(DLL)] public static extern bool CH341BitStreamSPI(uint iIndex, uint iLength, byte[] ioBuffer);
    [DllImport(DLL)] public static extern bool CH341Set_D5_D0(uint iIndex, uint iSetDirOut, uint iSetDataOut);
    [DllImport(DLL)] public static extern bool CH341GetInput(uint iIndex, out uint iStatus);
}

/// <summary>
/// CH341A programmer through the official WCH driver (same method as AsProgrammer).
/// I2C : CH341StreamI2C
/// SPI : CH341StreamSPI4, CS = D0
/// 93xx: CH341BitStreamSPI on the 25xx socket — CS = D0 (pin1), SK = D3 (pin6), DI = D5 (pin5), DO = D7 (pin2)
/// </summary>
public sealed class Ch341Programmer : IProgrammer
{
    private readonly uint idx;
    private bool mwMode;
    private const uint MW_DIR = 0x29; // D0, D3, D5 outputs

    public string Title { get; }
    public int I2cMaxRead => 64;
    public int I2cMaxWrite => 130;
    public bool I2cAckAware => false;
    public int SpiMaxTransfer => 4096;

    private Ch341Programmer(uint index)
    {
        idx = index;
        uint ic = 0;
        try { ic = Ch341Native.CH341GetVerIC(idx); } catch { }
        Title = $"CH341{(ic >= 0x20 ? "A" : "")} #{idx} (ไดรเวอร์ CH341PAR)";
        if (!Ch341Native.CH341SetStream(idx, 0x81)) // I2C 100 kHz, SPI MSB first
            throw new IOException("CH341SetStream ล้มเหลว");
    }

    public static Ch341Programmer Open()
    {
        try
        {
            for (uint i = 0; i < 16; i++)
            {
                var h = Ch341Native.CH341OpenDevice(i);
                if (h != new IntPtr(-1) && h != IntPtr.Zero) return new Ch341Programmer(i);
            }
        }
        catch (DllNotFoundException)
        {
            throw new IOException("ไม่พบ CH341DLL.DLL — ติดตั้งไดรเวอร์ CH341PAR (ดูแท็บ 'วิธีต่อสาย')");
        }
        catch (EntryPointNotFoundException)
        {
            throw new IOException("CH341DLL.DLL เวอร์ชันไม่รองรับ — ติดตั้งไดรเวอร์ CH341PAR รุ่นใหม่");
        }
        throw new IOException("ไม่พบ CH341A — ตรวจว่าจัมเปอร์อยู่โหมด Programmer และติดตั้งไดรเวอร์ CH341PAR แล้ว (Device Manager ต้องขึ้น 'USB-EPP/I2C... CH341A')");
    }

    private void LeaveMw()
    {
        if (!mwMode) return;
        Ch341Native.CH341Set_D5_D0(idx, 0, 0);
        mwMode = false;
    }

    // ---------------- I2C ----------------
    public bool I2cWrite(int addr, byte[] data)
    {
        LeaveMw();
        var buf = new byte[data.Length + 1];
        buf[0] = (byte)(addr << 1);
        data.CopyTo(buf, 1);
        if (!Ch341Native.CH341StreamI2C(idx, (uint)buf.Length, buf, 0, null))
            throw new IOException("CH341 I2C write ล้มเหลว");
        return true;
    }

    public byte[] I2cWriteRead(int addr, byte[] w, int rlen)
    {
        LeaveMw();
        var buf = new byte[w.Length + 1];
        buf[0] = (byte)(addr << 1);
        w.CopyTo(buf, 1);
        var r = new byte[rlen];
        if (!Ch341Native.CH341StreamI2C(idx, (uint)buf.Length, buf, (uint)rlen, r))
            throw new IOException("CH341 I2C read ล้มเหลว");
        return r;
    }

    // ---------------- SPI ----------------
    public byte[] Spi(byte[] write, int readLen)
    {
        LeaveMw();
        int total = write.Length + readLen;
        if (total > SpiMaxTransfer) throw new ArgumentException("SPI transfer too long");
        var buf = new byte[total];
        write.CopyTo(buf, 0);
        for (int i = write.Length; i < total; i++) buf[i] = 0xFF;
        if (!Ch341Native.CH341StreamSPI4(idx, 0x80, (uint)total, buf))
            throw new IOException("CH341 SPI ล้มเหลว");
        var r = new byte[readLen];
        Buffer.BlockCopy(buf, write.Length, r, 0, readLen);
        return r;
    }

    // ---------------- Microwire ----------------
    private void Pins(uint d) { if (!Ch341Native.CH341Set_D5_D0(idx, MW_DIR, d)) throw new IOException("CH341 Set_D5_D0 ล้มเหลว"); }

    private void BitStream(byte[] buf, int n)
    {
        if (!Ch341Native.CH341BitStreamSPI(idx, (uint)n, buf)) throw new IOException("CH341 BitStreamSPI ล้มเหลว");
    }

    public byte[] MwTransfer(bool[] outBits, int readBits)
    {
        mwMode = true;
        Pins(0x00);  // CS low
        Pins(0x01);  // CS high
        const int CHUNK = 512;
        for (int i = 0; i < outBits.Length; i += CHUNK)
        {
            int n = Math.Min(CHUNK, outBits.Length - i);
            var b = new byte[n];
            for (int k = 0; k < n; k++) b[k] = (byte)(0x01 | (outBits[i + k] ? 0x20 : 0));
            BitStream(b, n);
        }
        var res = new bool[readBits];
        for (int i = 0; i < readBits; i += CHUNK)
        {
            int n = Math.Min(CHUNK, readBits - i);
            var b = new byte[n];
            for (int k = 0; k < n; k++) b[k] = 0x01;
            BitStream(b, n);
            for (int k = 0; k < n; k++) res[i + k] = (b[k] & 0x80) != 0;
        }
        Pins(0x00);  // CS low -> starts self-timed write/erase
        return Bits.Pack(readBits, i => res[i]);
    }

    public bool MwWaitReady(int timeoutMs)
    {
        mwMode = true;
        Pins(0x00);
        Pins(0x01);
        var sw = System.Diagnostics.Stopwatch.StartNew();
        bool ok = false;
        while (true)
        {
            if (Ch341Native.CH341GetInput(idx, out uint st) && (st & 0x80) != 0) { ok = true; break; }
            if (sw.ElapsedMilliseconds > timeoutMs) break;
        }
        Pins(0x00);
        return ok;
    }

    public void Dispose()
    {
        try { Ch341Native.CH341Set_D5_D0(idx, 0, 0); } catch { }
        try { Ch341Native.CH341CloseDevice(idx); } catch { }
    }
}
