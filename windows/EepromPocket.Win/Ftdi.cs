using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace EepromPocket;

internal static class D2xx
{
    private const string DLL = "ftd2xx.dll";
    [DllImport(DLL)] public static extern uint FT_CreateDeviceInfoList(out uint num);
    [DllImport(DLL)] public static extern uint FT_GetDeviceInfoDetail(uint index, out uint flags, out uint type, out uint id, out uint locId, byte[] serial, byte[] desc, out IntPtr handle);
    [DllImport(DLL)] public static extern uint FT_Open(int index, out IntPtr handle);
    [DllImport(DLL)] public static extern uint FT_Close(IntPtr h);
    [DllImport(DLL)] public static extern uint FT_ResetDevice(IntPtr h);
    [DllImport(DLL)] public static extern uint FT_Purge(IntPtr h, uint mask);
    [DllImport(DLL)] public static extern uint FT_SetUSBParameters(IntPtr h, uint inSize, uint outSize);
    [DllImport(DLL)] public static extern uint FT_SetLatencyTimer(IntPtr h, byte t);
    [DllImport(DLL)] public static extern uint FT_SetTimeouts(IntPtr h, uint readMs, uint writeMs);
    [DllImport(DLL)] public static extern uint FT_SetBitMode(IntPtr h, byte mask, byte mode);
    [DllImport(DLL)] public static extern uint FT_Write(IntPtr h, byte[] buf, uint len, out uint written);
    [DllImport(DLL)] public static extern uint FT_Read(IntPtr h, byte[] buf, uint len, out uint read);
    [DllImport(DLL)] public static extern uint FT_GetQueueStatus(IntPtr h, out uint n);
}

/// <summary>
/// FTDI FT232H / FT2232H / FT4232H in MPSSE mode (channel A) through the FTDI D2XX driver.
/// AD0 = SCK/SCL, AD1 = MOSI/SDA-out/DI, AD2 = MISO/SDA-in/DO, AD3 = CS. I2C: tie AD1+AD2 = SDA.
/// </summary>
public sealed class FtdiMpsse : IProgrammer
{
    private readonly IntPtr h;
    private enum Mode { None, Spi, I2c, Mw }
    private Mode mode = Mode.None;

    public string Title { get; }
    public int I2cMaxRead => 64;
    public int I2cMaxWrite => 260;
    public bool I2cAckAware => true;
    public int SpiMaxTransfer => 1 << 20;

    private FtdiMpsse(IntPtr handle, string name)
    {
        h = handle;
        Title = $"{name} (MPSSE, ไดรเวอร์ FTDI D2XX)";
        Check(D2xx.FT_ResetDevice(h), "reset");
        D2xx.FT_SetUSBParameters(h, 65536, 65536);
        D2xx.FT_SetLatencyTimer(h, 2);
        D2xx.FT_SetTimeouts(h, 3000, 3000);
        Check(D2xx.FT_SetBitMode(h, 0, 0), "bitmode reset");
        Check(D2xx.FT_SetBitMode(h, 0, 2), "MPSSE");
        Thread.Sleep(50);
        D2xx.FT_Purge(h, 3);
    }

    public static FtdiMpsse Open()
    {
        try
        {
            Check(D2xx.FT_CreateDeviceInfoList(out uint n), "list");
            for (uint i = 0; i < n; i++)
            {
                var sn = new byte[16]; var desc = new byte[64];
                if (D2xx.FT_GetDeviceInfoDetail(i, out _, out uint type, out _, out _, sn, desc, out _) != 0) continue;
                string d = Encoding.ASCII.GetString(desc).TrimEnd('\0');
                string name = type switch { 6 => "FT2232H", 7 => "FT4232H", 8 => "FT232H", _ => null };
                if (name == null) continue;
                if (type != 8 && !d.EndsWith("A")) continue; // channel A only
                if (D2xx.FT_Open((int)i, out IntPtr hh) == 0) return new FtdiMpsse(hh, name);
            }
        }
        catch (DllNotFoundException)
        {
            throw new IOException("ไม่พบ ftd2xx.dll — ติดตั้งไดรเวอร์ FTDI (CDM / D2XX)");
        }
        throw new IOException("ไม่พบ FT232H / FT2232H / FT4232H");
    }

    private static void Check(uint st, string what)
    {
        if (st != 0) throw new IOException($"FTDI {what} ผิดพลาด (status {st})");
    }

    private void Send(byte[] b)
    {
        Check(D2xx.FT_Write(h, b, (uint)b.Length, out uint w), "write");
        if (w != b.Length) throw new IOException("FTDI เขียนไม่ครบ");
    }

    private void Send(List<byte> b) => Send(b.ToArray());

    private byte[] ReadExact(int n)
    {
        var o = new byte[n];
        int got = 0;
        var sw = Stopwatch.StartNew();
        while (got < n)
        {
            var tmp = new byte[n - got];
            Check(D2xx.FT_Read(h, tmp, (uint)tmp.Length, out uint r), "read");
            Buffer.BlockCopy(tmp, 0, o, got, (int)r);
            got += (int)r;
            if (got < n && sw.ElapsedMilliseconds > 3000) throw new IOException($"FTDI ไม่ตอบสนอง ({got}/{n})");
        }
        return o;
    }

    private static void P(List<byte> c, int v, int dir) { c.Add(0x80); c.Add((byte)v); c.Add((byte)dir); }
    private static void Div(List<byte> c, int d) { c.Add(0x86); c.Add((byte)(d & 0xFF)); c.Add((byte)(d >> 8)); }

    private void Ensure(Mode m)
    {
        if (mode == m) return;
        var c = new List<byte> { 0x8A, 0x97 };
        switch (m)
        {
            case Mode.Spi: c.Add(0x8D); Div(c, 29); c.Add(0x85); P(c, 0x08, 0x0B); break;
            case Mode.I2c: c.Add(0x8C); Div(c, 199); c.Add(0x85); P(c, 0x03, 0x03); break;
            case Mode.Mw: c.Add(0x8D); Div(c, 59); c.Add(0x85); P(c, 0x00, 0x0B); break;
        }
        Send(c);
        mode = m;
    }

    // ---------------- SPI ----------------
    public byte[] Spi(byte[] write, int readLen)
    {
        Ensure(Mode.Spi);
        int total = write.Length + readLen;
        var tx = new byte[total];
        write.CopyTo(tx, 0);
        for (int i = write.Length; i < total; i++) tx[i] = 0xFF;
        var rx = new byte[total];
        int off = 0;
        while (off < total)
        {
            int n = Math.Min(4096, total - off);
            var c = new List<byte>(n + 16);
            if (off == 0) P(c, 0x00, 0x0B);
            c.Add(0x31); c.Add((byte)((n - 1) & 0xFF)); c.Add((byte)((n - 1) >> 8));
            for (int k = 0; k < n; k++) c.Add(tx[off + k]);
            if (off + n >= total) P(c, 0x08, 0x0B);
            c.Add(0x87);
            Send(c);
            ReadExact(n).CopyTo(rx, off);
            off += n;
        }
        var r = new byte[readLen];
        Buffer.BlockCopy(rx, write.Length, r, 0, readLen);
        return r;
    }

    // ---------------- I2C ----------------
    private static void Start(List<byte> c)
    {
        P(c, 0x02, 0x03);
        for (int i = 0; i < 4; i++) P(c, 0x03, 0x03);
        for (int i = 0; i < 4; i++) P(c, 0x01, 0x03);
        P(c, 0x00, 0x03);
    }

    private static void Stop(List<byte> c)
    {
        for (int i = 0; i < 4; i++) P(c, 0x00, 0x03);
        for (int i = 0; i < 4; i++) P(c, 0x01, 0x03);
        for (int i = 0; i < 4; i++) P(c, 0x03, 0x03);
    }

    private static void OutByte(List<byte> c, int b)
    {
        c.Add(0x11); c.Add(0); c.Add(0); c.Add((byte)b);
        P(c, 0x00, 0x01);
        c.Add(0x22); c.Add(0);
        P(c, 0x02, 0x03);
    }

    private static void InByte(List<byte> c, bool ack)
    {
        P(c, 0x00, 0x01);
        c.Add(0x20); c.Add(0); c.Add(0);
        P(c, ack ? 0x00 : 0x02, 0x03);
        c.Add(0x13); c.Add(0); c.Add((byte)(ack ? 0x00 : 0xFF));
        P(c, 0x02, 0x03);
    }

    public bool I2cWrite(int addr, byte[] data)
    {
        Ensure(Mode.I2c);
        var c = new List<byte>();
        Start(c);
        OutByte(c, addr << 1);
        foreach (var b in data) OutByte(c, b);
        Stop(c);
        c.Add(0x87);
        Send(c);
        var r = ReadExact(1 + data.Length);
        return r.All(x => (x & 1) == 0);
    }

    public byte[] I2cWriteRead(int addr, byte[] w, int rlen)
    {
        Ensure(Mode.I2c);
        var c = new List<byte>();
        int acks = 0;
        if (w.Length > 0)
        {
            Start(c); OutByte(c, addr << 1); acks++;
            foreach (var b in w) { OutByte(c, b); acks++; }
        }
        Start(c); OutByte(c, (addr << 1) | 1); acks++;
        for (int i = 0; i < rlen; i++) InByte(c, i < rlen - 1);
        Stop(c);
        c.Add(0x87);
        Send(c);
        var r = ReadExact(acks + rlen);
        if ((r[0] & 1) != 0) throw new IOException($"I2C ไม่มี ACK ที่แอดเดรส 0x{addr:X2} (ตรวจการต่อสาย/ไฟเลี้ยง)");
        var o = new byte[rlen];
        Buffer.BlockCopy(r, acks, o, 0, rlen);
        return o;
    }

    // ---------------- Microwire ----------------
    public byte[] MwTransfer(bool[] outBits, int readBits)
    {
        Ensure(Mode.Mw);
        var c = new List<byte>();
        P(c, 0x00, 0x0B); P(c, 0x08, 0x0B);
        for (int i = 0; i < outBits.Length; i += 8)
        {
            int n = Math.Min(8, outBits.Length - i);
            int v = 0;
            for (int k = 0; k < n; k++) if (outBits[i + k]) v |= 0x80 >> k;
            c.Add(0x13); c.Add((byte)(n - 1)); c.Add((byte)v);
        }
        P(c, 0x08, 0x0B);
        int nBytes = readBits / 8, rem = readBits % 8, resp = 0;
        if (nBytes > 0) { c.Add(0x24); c.Add((byte)((nBytes - 1) & 0xFF)); c.Add((byte)((nBytes - 1) >> 8)); resp += nBytes; }
        if (rem > 0) { c.Add(0x26); c.Add((byte)(rem - 1)); resp += 1; }
        P(c, 0x00, 0x0B);
        c.Add(0x87);
        Send(c);
        var r = resp > 0 ? ReadExact(resp) : Array.Empty<byte>();
        var o = new byte[(readBits + 7) / 8];
        for (int k = 0; k < nBytes; k++) o[k] = r[k];
        if (rem > 0) o[nBytes] = (byte)((r[nBytes] & ((1 << rem) - 1)) << (8 - rem));
        return o;
    }

    public bool MwWaitReady(int timeoutMs)
    {
        Ensure(Mode.Mw);
        var c = new List<byte>();
        P(c, 0x00, 0x0B); P(c, 0x08, 0x0B);
        Send(c);
        var sw = Stopwatch.StartNew();
        bool ok = false;
        while (true)
        {
            Send(new byte[] { 0x81, 0x87 });
            if ((ReadExact(1)[0] & 0x04) != 0) { ok = true; break; }
            if (sw.ElapsedMilliseconds > timeoutMs) break;
        }
        Send(new byte[] { 0x80, 0x00, 0x0B });
        return ok;
    }

    public void Dispose()
    {
        try { D2xx.FT_SetBitMode(h, 0, 0); } catch { }
        try { D2xx.FT_Close(h); } catch { }
    }
}
