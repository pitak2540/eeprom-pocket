using System.IO.Ports;
using System.Text;

namespace EepromPocket;

/// <summary>
/// Arduino running firmware/eeprom_bridge.ino, reached through any COM port (CH340, FT232R, CDC ...).
/// host->dev: A5 cmd lenL lenH payload ; dev->host: 5A status lenL lenH data
/// </summary>
public sealed class BridgeProgrammer : IProgrammer
{
    private readonly SerialPort port;

    public string Title { get; }
    public int I2cMaxRead => 32;
    public int I2cMaxWrite => 32;
    public bool I2cAckAware => true;
    public int SpiMaxTransfer => 1 << 20;

    private BridgeProgrammer(SerialPort p, string fw)
    {
        port = p;
        Title = $"Arduino Bridge ({p.PortName}, {fw})";
    }

    public static BridgeProgrammer Open(string portName)
    {
        var p = new SerialPort(portName, 1_000_000, Parity.None, 8, StopBits.One)
        {
            DtrEnable = true,
            RtsEnable = true,
            ReadTimeout = 600,
            WriteTimeout = 2000,
            ReadBufferSize = 65536,
        };
        try
        {
            p.Open();
            Thread.Sleep(2200); // Arduino auto-reset
            p.DiscardInBuffer();
            var b = new BridgeProgrammer(p, "?");
            Exception last = null;
            for (int i = 0; i < 5; i++)
            {
                try
                {
                    var (st, d) = b.Cmd(0x01, Array.Empty<byte>(), 600);
                    if (st == 0) return new BridgeProgrammer(p, Encoding.ASCII.GetString(d));
                }
                catch (Exception e) { last = e; p.DiscardInBuffer(); }
            }
            throw new IOException($"ไม่พบเฟิร์มแวร์ EEPROM Bridge บน {portName} ({last?.Message ?? "ไม่ตอบ"})");
        }
        catch
        {
            try { p.Close(); } catch { }
            throw;
        }
    }

    private byte[] ReadExact(int n, int timeoutMs)
    {
        var o = new byte[n];
        int got = 0;
        port.ReadTimeout = timeoutMs;
        try
        {
            while (got < n) got += port.Read(o, got, n - got);
        }
        catch (TimeoutException)
        {
            throw new IOException($"{port.PortName}: หมดเวลารอข้อมูล");
        }
        return o;
    }

    private (int, byte[]) Cmd(int c, byte[] payload, int timeout = 3000)
    {
        var f = new byte[4 + payload.Length];
        f[0] = 0xA5; f[1] = (byte)c; f[2] = (byte)(payload.Length & 0xFF); f[3] = (byte)(payload.Length >> 8);
        payload.CopyTo(f, 4);
        port.Write(f, 0, f.Length);
        int guard = 0;
        while (ReadExact(1, timeout)[0] != 0x5A)
            if (++guard > 2000) throw new IOException("Bridge: ข้อมูลตอบกลับผิดรูปแบบ");
        var hd = ReadExact(3, timeout);
        int len = hd[1] | (hd[2] << 8);
        var d = len > 0 ? ReadExact(len, timeout) : Array.Empty<byte>();
        return (hd[0], d);
    }

    public bool I2cWrite(int addr, byte[] data)
    {
        var p = new byte[1 + data.Length];
        p[0] = (byte)addr; data.CopyTo(p, 1);
        return Cmd(0x10, p).Item1 == 0;
    }

    public byte[] I2cWriteRead(int addr, byte[] w, int rlen)
    {
        var p = new byte[2 + w.Length];
        p[0] = (byte)addr; p[1] = (byte)rlen; w.CopyTo(p, 2);
        var (st, d) = Cmd(0x11, p);
        if (st != 0 || d.Length < rlen) throw new IOException($"I2C ไม่มี ACK ที่แอดเดรส 0x{addr:X2} (ตรวจการต่อสาย/ไฟเลี้ยง)");
        return d;
    }

    public byte[] Spi(byte[] write, int readLen)
    {
        int total = write.Length + readLen;
        var tx = new byte[total];
        write.CopyTo(tx, 0);
        for (int i = write.Length; i < total; i++) tx[i] = 0xFF;
        var rx = new byte[total];
        int off = 0;
        while (off < total)
        {
            int n = Math.Min(256, total - off);
            int flags = (off == 0 ? 2 : 0) | (off + n >= total ? 1 : 0);
            var p = new byte[n + 1];
            p[0] = (byte)flags;
            Buffer.BlockCopy(tx, off, p, 1, n);
            var (st, d) = Cmd(0x20, p);
            if (st != 0 || d.Length < n) throw new IOException("Bridge: SPI ผิดพลาด");
            Buffer.BlockCopy(d, 0, rx, off, n);
            off += n;
        }
        var r = new byte[readLen];
        Buffer.BlockCopy(rx, write.Length, r, 0, readLen);
        return r;
    }

    public byte[] MwTransfer(bool[] outBits, int readBits)
    {
        var packed = Bits.Pack(outBits.Length, i => outBits[i]);
        var p = new byte[4 + packed.Length];
        p[0] = (byte)(outBits.Length & 0xFF); p[1] = (byte)(outBits.Length >> 8);
        p[2] = (byte)(readBits & 0xFF); p[3] = (byte)(readBits >> 8);
        packed.CopyTo(p, 4);
        var (st, d) = Cmd(0x30, p);
        if (st != 0) throw new IOException("Bridge: Microwire ผิดพลาด");
        return d;
    }

    public bool MwWaitReady(int timeoutMs) =>
        Cmd(0x31, new[] { (byte)(timeoutMs & 0xFF), (byte)(timeoutMs >> 8) }, timeoutMs + 2000).Item1 == 0;

    public void Dispose()
    {
        try { port.Close(); } catch { }
    }
}
