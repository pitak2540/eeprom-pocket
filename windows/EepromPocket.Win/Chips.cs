using System.Diagnostics;

namespace EepromPocket;

public enum Family { I2C24, SPI25EE, SPI25FLASH, MW93 }

/// <param name="AddrBytes">24/25: address bytes. 93: address bits in x8 mode.</param>
/// <param name="Extra">24: block bits in the device address. 93: address bits in x16 mode.</param>
public record Chip(Family Family, string Name, int Size, int Page, int AddrBytes, int Extra = 0)
{
    public override string ToString() => Size > 0 ? $"{Name}  ({Bits.FmtSize(Size)})" : Name;
}

public static class ChipDb
{
    private const int K = 1024;

    public static readonly (Family Family, string Label)[] Families =
    {
        (Family.I2C24, "24xx  I2C EEPROM"),
        (Family.SPI25EE, "25xx / 95xx  SPI EEPROM"),
        (Family.SPI25FLASH, "25xx  SPI Flash (BIOS)"),
        (Family.MW93, "93xx  Microwire EEPROM"),
    };

    public static readonly Dictionary<Family, Chip[]> All = new()
    {
        [Family.I2C24] = new[]
        {
            new Chip(Family.I2C24, "24C01", 128, 8, 1),
            new Chip(Family.I2C24, "24C02", 256, 8, 1),
            new Chip(Family.I2C24, "24C04", 512, 16, 1, 1),
            new Chip(Family.I2C24, "24C08", 1 * K, 16, 1, 2),
            new Chip(Family.I2C24, "24C16", 2 * K, 16, 1, 3),
            new Chip(Family.I2C24, "24C32", 4 * K, 32, 2),
            new Chip(Family.I2C24, "24C64", 8 * K, 32, 2),
            new Chip(Family.I2C24, "24C128", 16 * K, 64, 2),
            new Chip(Family.I2C24, "24C256", 32 * K, 64, 2),
            new Chip(Family.I2C24, "24C512", 64 * K, 128, 2),
            new Chip(Family.I2C24, "24C1024 / 24M01", 128 * K, 256, 2, 1),
            new Chip(Family.I2C24, "24M02", 256 * K, 256, 2, 2),
        },
        [Family.SPI25EE] = new[]
        {
            new Chip(Family.SPI25EE, "25010 / 95010", 128, 16, 1),
            new Chip(Family.SPI25EE, "25020 / 95020", 256, 16, 1),
            new Chip(Family.SPI25EE, "25040 / 95040", 512, 16, 1),
            new Chip(Family.SPI25EE, "25080 / 95080", 1 * K, 16, 2),
            new Chip(Family.SPI25EE, "25160 / 95160", 2 * K, 16, 2),
            new Chip(Family.SPI25EE, "25320 / 95320", 4 * K, 32, 2),
            new Chip(Family.SPI25EE, "25640 / 95640", 8 * K, 32, 2),
            new Chip(Family.SPI25EE, "25128 / 95128", 16 * K, 64, 2),
            new Chip(Family.SPI25EE, "25256 / 95256", 32 * K, 64, 2),
            new Chip(Family.SPI25EE, "25512 / 95512", 64 * K, 128, 2),
            new Chip(Family.SPI25EE, "25M01 / 95M01", 128 * K, 256, 3),
            new Chip(Family.SPI25EE, "25M02 / 95M02", 256 * K, 256, 3),
        },
        [Family.SPI25FLASH] = new[]
        {
            new Chip(Family.SPI25FLASH, "Auto (อ่าน JEDEC ID)", 0, 256, 3),
            new Chip(Family.SPI25FLASH, "25x05 (512Kbit)", 64 * K, 256, 3),
            new Chip(Family.SPI25FLASH, "25x10 (1Mbit)", 128 * K, 256, 3),
            new Chip(Family.SPI25FLASH, "25x20 (2Mbit)", 256 * K, 256, 3),
            new Chip(Family.SPI25FLASH, "25x40 (4Mbit)", 512 * K, 256, 3),
            new Chip(Family.SPI25FLASH, "25x80 (8Mbit)", 1024 * K, 256, 3),
            new Chip(Family.SPI25FLASH, "25x16 (16Mbit)", 2 * K * K, 256, 3),
            new Chip(Family.SPI25FLASH, "25x32 (32Mbit)", 4 * K * K, 256, 3),
            new Chip(Family.SPI25FLASH, "25x64 (64Mbit)", 8 * K * K, 256, 3),
            new Chip(Family.SPI25FLASH, "25x128 (128Mbit)", 16 * K * K, 256, 3),
            new Chip(Family.SPI25FLASH, "25x256 (256Mbit)", 32 * K * K, 256, 4),
        },
        [Family.MW93] = new[]
        {
            new Chip(Family.MW93, "93C46", 128, 0, 7, 6),
            new Chip(Family.MW93, "93C56", 256, 0, 9, 8),
            new Chip(Family.MW93, "93C66", 512, 0, 9, 8),
            new Chip(Family.MW93, "93C76", 1 * K, 0, 11, 10),
            new Chip(Family.MW93, "93C86", 2 * K, 0, 11, 10),
        },
    };
}

public interface IChipOps
{
    int Size { get; }
    string Detect();
    byte[] Read(Progress p);
    void Write(byte[] data, Progress p);
    void Erase(Progress p);
}

// ======================= 24xx =======================
public sealed class I2cEeprom : IChipOps
{
    private readonly II2cBus bus;
    private readonly Chip chip;
    private readonly int block, mask;
    public int Size => chip.Size;

    public I2cEeprom(II2cBus bus, Chip chip)
    {
        this.bus = bus; this.chip = chip;
        block = chip.AddrBytes == 1 ? 256 : 65536;
        mask = (1 << chip.Extra) - 1;
    }

    private int Dev(int off) => 0x50 | ((off / block) & mask);
    private byte[] WAddr(int off) => chip.AddrBytes == 1 ? new[] { (byte)off } : new[] { (byte)(off >> 8), (byte)off };

    public string Detect()
    {
        if (!bus.I2cAckAware)
        {
            var d = bus.I2cWriteRead(0x50, WAddr(0), 8);
            return "อ่าน 8 ไบต์แรก: " + BitConverter.ToString(d).Replace("-", " ") +
                   (d.All(b => b == 0xFF) ? "  (อาจไม่มีชิป/ชิปว่าง)" : "");
        }
        var found = Enumerable.Range(0x50, 8).Where(a => { try { return bus.I2cWrite(a, Array.Empty<byte>()); } catch { return false; } }).ToList();
        return found.Count == 0 ? "ไม่พบอุปกรณ์ I2C ที่ 0x50-0x57" : "พบ I2C ที่แอดเดรส: " + string.Join(", ", found.Select(a => $"0x{a:X2}"));
    }

    public byte[] Read(Progress p)
    {
        var o = new byte[Size];
        int off = 0;
        while (off < Size)
        {
            int n = Math.Min(Math.Min(bus.I2cMaxRead, block - off % block), Size - off);
            Buffer.BlockCopy(bus.I2cWriteRead(Dev(off), WAddr(off), n), 0, o, off, n);
            off += n;
            p(off, Size);
        }
        return o;
    }

    public void Write(byte[] data, Progress p)
    {
        int off = 0;
        while (off < Size)
        {
            int n = Math.Min(Math.Min(chip.Page - off % chip.Page, bus.I2cMaxWrite - chip.AddrBytes), Size - off);
            var a = WAddr(off);
            var buf = new byte[a.Length + n];
            a.CopyTo(buf, 0);
            Buffer.BlockCopy(data, off, buf, a.Length, n);
            if (!bus.I2cWrite(Dev(off), buf)) throw new IOException($"เขียนที่ 0x{off:X} ไม่สำเร็จ (NACK) — ตรวจขา WP");
            WaitDone(Dev(off));
            off += n;
            p(off, Size);
        }
    }

    private void WaitDone(int addr)
    {
        if (!bus.I2cAckAware) { Thread.Sleep(10); return; }
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < 30)
            try { if (bus.I2cWrite(addr, Array.Empty<byte>())) return; } catch { }
    }

    public void Erase(Progress p) => Write(Enumerable.Repeat((byte)0xFF, Size).ToArray(), p);
}

// ======================= 25xx EEPROM =======================
public sealed class SpiEeprom : IChipOps
{
    private readonly ISpiBus bus;
    private readonly Chip chip;
    public int Size => chip.Size;

    public SpiEeprom(ISpiBus bus, Chip chip) { this.bus = bus; this.chip = chip; }

    private byte[] Cmd(int op, int off)
    {
        int a8 = chip.AddrBytes == 1 && (off & 0x100) != 0 ? 0x08 : 0;
        return chip.AddrBytes switch
        {
            1 => new[] { (byte)(op | a8), (byte)off },
            2 => new[] { (byte)op, (byte)(off >> 8), (byte)off },
            _ => new[] { (byte)op, (byte)(off >> 16), (byte)(off >> 8), (byte)off },
        };
    }

    private int Rdsr() => bus.Spi(new byte[] { 0x05 }, 1)[0];

    public string Detect()
    {
        int sr = Rdsr();
        return $"Status register = 0x{sr:X2}" + (sr == 0xFF || sr == 0 ? "  (ถ้าเป็น FF ตลอดอาจไม่มีชิปหรือต่อสายผิด)" : "");
    }

    public byte[] Read(Progress p)
    {
        var o = new byte[Size];
        int chunk = chip.AddrBytes == 1 ? 256 : Math.Min(2048, bus.SpiMaxTransfer - 8);
        int off = 0;
        while (off < Size)
        {
            int n = Math.Min(chunk - off % chunk, Size - off);
            Buffer.BlockCopy(bus.Spi(Cmd(0x03, off), n), 0, o, off, n);
            off += n;
            p(off, Size);
        }
        return o;
    }

    private void WaitWip(int ms)
    {
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < ms) if ((Rdsr() & 1) == 0) return;
        throw new IOException("ชิปไม่ตอบ (WIP ค้าง)");
    }

    public void Write(byte[] data, Progress p)
    {
        bus.Spi(new byte[] { 0x06 }, 0);
        bus.Spi(new byte[] { 0x01, 0x00 }, 0);
        WaitWip(50);
        int off = 0;
        while (off < Size)
        {
            int n = Math.Min(chip.Page - off % chip.Page, Size - off);
            bus.Spi(new byte[] { 0x06 }, 0);
            var seg = new byte[n];
            Buffer.BlockCopy(data, off, seg, 0, n);
            bus.Spi(Bits.Concat(Cmd(0x02, off), seg), 0);
            WaitWip(50);
            off += n;
            p(off, Size);
        }
        bus.Spi(new byte[] { 0x04 }, 0);
    }

    public void Erase(Progress p) => Write(Enumerable.Repeat((byte)0xFF, Size).ToArray(), p);
}

// ======================= 25xx SPI Flash =======================
public sealed class SpiFlash : IChipOps
{
    private readonly ISpiBus bus;
    public int Size { get; private set; }
    private bool Addr4 => Size > 16 * 1024 * 1024;

    public static readonly Dictionary<int, string> Vendors = new()
    {
        [0xEF] = "Winbond", [0xC2] = "Macronix", [0xC8] = "GigaDevice", [0x1C] = "EON",
        [0x20] = "Micron/XMC", [0x68] = "Boya", [0x85] = "Puya", [0x9D] = "ISSI", [0xBF] = "SST",
        [0x1F] = "Atmel/Adesto", [0x5E] = "Zbit", [0x0B] = "XTX", [0xA1] = "Fudan", [0x37] = "AMIC",
        [0x8C] = "ESMT", [0xBA] = "Zetta", [0x46] = "XMC", [0x01] = "Spansion/Cypress", [0x62] = "ON Semi",
        [0x7F] = "PMC",
    };

    public SpiFlash(ISpiBus bus, int sizeHint) { this.bus = bus; Size = sizeHint; }

    public static int SizeFromId(byte[] id) => id[2] >= 0x10 && id[2] <= 0x19 ? 1 << id[2] : 0;

    public byte[] Jedec() => bus.Spi(new byte[] { 0x9F }, 3);

    public string Detect()
    {
        var id = Jedec();
        string hex = BitConverter.ToString(id).Replace("-", " ");
        if (id.All(b => b == 0xFF) || id.All(b => b == 0)) return $"JEDEC ID = {hex}  → ไม่พบชิป (ตรวจการต่อสาย/ไฟ)";
        string vend = Vendors.TryGetValue(id[0], out var v) ? v : "ไม่ทราบผู้ผลิต";
        int s = SizeFromId(id);
        if (s > 0) Size = s;
        return $"JEDEC ID = {hex}  → {vend}, ขนาด {(s > 0 ? Bits.FmtSize(s) : "ไม่ทราบ (เลือกเอง)")}";
    }

    public void DetectSizeIfNeeded()
    {
        if (Size > 0) return;
        int s = SizeFromId(Jedec());
        if (s <= 0) throw new IOException("ไม่ทราบขนาดชิป — กด 'ตรวจ ID' หรือเลือกรุ่นเอง");
        Size = s;
    }

    private byte[] AddrCmd(int op, int off) => Addr4
        ? new[] { (byte)op, (byte)(off >> 24), (byte)(off >> 16), (byte)(off >> 8), (byte)off }
        : new[] { (byte)op, (byte)(off >> 16), (byte)(off >> 8), (byte)off };

    private int Rdsr() => bus.Spi(new byte[] { 0x05 }, 1)[0];
    private void Wren() => bus.Spi(new byte[] { 0x06 }, 0);

    private void WaitWip(long timeoutMs, Action<long> tick = null)
    {
        var sw = Stopwatch.StartNew();
        while (true)
        {
            if ((Rdsr() & 1) == 0) return;
            long el = sw.ElapsedMilliseconds;
            tick?.Invoke(el);
            if (el > timeoutMs) throw new IOException("ชิปไม่ตอบ (WIP ค้าง)");
            if (tick != null) Thread.Sleep(50);
        }
    }

    public byte[] Read(Progress p)
    {
        DetectSizeIfNeeded();
        var o = new byte[Size];
        int chunk = Math.Min(64 * 1024, bus.SpiMaxTransfer - 8) & ~0xFF;
        int off = 0;
        while (off < Size)
        {
            int n = Math.Min(chunk, Size - off);
            Buffer.BlockCopy(bus.Spi(AddrCmd(Addr4 ? 0x13 : 0x03, off), n), 0, o, off, n);
            off += n;
            p(off, Size);
        }
        return o;
    }

    private void Unprotect()
    {
        Wren();
        bus.Spi(new byte[] { 0x01, 0x00 }, 0);
        WaitWip(500);
    }

    public void Erase(Progress p)
    {
        DetectSizeIfNeeded();
        Unprotect();
        Wren();
        bus.Spi(new byte[] { 0xC7 }, 0);
        long est = Math.Max(5000L, Size / 1024L * 40);
        WaitWip(400_000, el => p(Math.Min(el, est - 1), est));
        p(est, est);
    }

    public void Write(byte[] data, Progress p)
    {
        DetectSizeIfNeeded();
        Unprotect();
        int off = 0;
        while (off < Size)
        {
            int n = Math.Min(256 - off % 256, Size - off);
            bool blank = true;
            for (int k = off; k < off + n; k++) if (data[k] != 0xFF) { blank = false; break; }
            if (!blank)
            {
                Wren();
                var seg = new byte[n];
                Buffer.BlockCopy(data, off, seg, 0, n);
                bus.Spi(Bits.Concat(AddrCmd(Addr4 ? 0x12 : 0x02, off), seg), 0);
                WaitWip(100);
            }
            off += n;
            p(off, Size);
        }
    }
}

// ======================= 93xx =======================
/// <summary>x16 byte order in the .bin: default low byte first (IMSProg / CH341A software); hiFirst = big-endian.</summary>
public sealed class MicrowireEeprom : IChipOps
{
    private readonly IMicrowireBus bus;
    private readonly bool x16, hiFirst;
    private readonly int abits, wordBytes, words, dataBits;
    public int Size { get; }
    public int LastWritten { get; private set; }
    public int LastSkipped { get; private set; }

    public MicrowireEeprom(IMicrowireBus bus, Chip chip, bool x16, bool hiFirst)
    {
        this.bus = bus; this.x16 = x16; this.hiFirst = hiFirst;
        Size = chip.Size;
        abits = x16 ? chip.Extra : chip.AddrBytes;
        wordBytes = x16 ? 2 : 1;
        words = Size / wordBytes;
        dataBits = wordBytes * 8;
    }

    private bool[] Cmd(int op, int addr) => Bits.Of(((long)(0b100 | op) << abits) | (uint)addr, 3 + abits);
    private void Ewen() => bus.MwTransfer(Cmd(0b00, 0b11 << (abits - 2)), 0);
    private void Ewds() => bus.MwTransfer(Cmd(0b00, 0), 0);

    private int ReadWord(int w)
    {
        var d = bus.MwTransfer(Cmd(0b10, w), dataBits);
        return x16 ? (d[0] << 8) | d[1] : d[0];
    }

    private int WordFromFile(byte[] data, int w)
    {
        if (!x16) return data[w];
        int a = data[w * 2], b = data[w * 2 + 1];
        return hiFirst ? (a << 8) | b : (b << 8) | a;
    }

    private void PutWord(byte[] o, int w, int v)
    {
        if (!x16) { o[w] = (byte)v; return; }
        byte hi = (byte)(v >> 8), lo = (byte)v;
        if (hiFirst) { o[w * 2] = hi; o[w * 2 + 1] = lo; } else { o[w * 2] = lo; o[w * 2 + 1] = hi; }
    }

    private string F(int v) => x16 ? v.ToString("X4") : v.ToString("X2");

    public string Detect()
    {
        int w0 = ReadWord(0), w1 = ReadWord(1);
        int mask = (1 << dataBits) - 1;
        string hint = w0 == w1 && (w0 == 0 || w0 == mask) ? "  (ค่าเหมือนกัน — อาจไม่มีชิป, ต่อสายผิด หรือชิปว่าง)" : "";
        return $"เวิร์ด 0 = {F(w0)}, เวิร์ด 1 = {F(w1)}  (โหมด {(x16 ? "x16" : "x8")}, {abits} address bits){hint}";
    }

    public byte[] Read(Progress p)
    {
        var o = new byte[Size];
        for (int w = 0; w < words; w++)
        {
            PutWord(o, w, ReadWord(w));
            if ((w & 7) == 7 || w == words - 1) p((w + 1) * wordBytes, Size);
        }
        return o;
    }

    private void WriteWord(int w, int value)
    {
        bus.MwTransfer(Bits.Concat(Cmd(0b01, w), Bits.Of(value, dataBits)), 0);
        if (!bus.MwWaitReady(30))
            throw new IOException($"ชิปไม่ตอบหลังเขียนเวิร์ด {w} (DO ไม่ขึ้น HIGH) — ตรวจสาย DO และขา ORG");
    }

    /// <summary>Per word: read, skip if equal, else WRITE + wait + read back (up to 3 tries).</summary>
    public void Write(byte[] data, Progress p)
    {
        LastWritten = 0; LastSkipped = 0;
        Ewen();
        try
        {
            for (int w = 0; w < words; w++)
            {
                int value = WordFromFile(data, w);
                int cur = ReadWord(w);
                if (cur == value) LastSkipped++;
                else
                {
                    int tries = 0;
                    while (cur != value)
                    {
                        if (++tries > 3)
                            throw new IOException($"เขียนเวิร์ด {w} (ไบต์ 0x{w * wordBytes:X}) ไม่ติด: ต้องการ {F(value)} อ่านได้ {F(cur)} — ตรวจขา ORG ให้ตรง x8/x16 และไฟเลี้ยง");
                        if (tries > 1) Ewen();
                        WriteWord(w, value);
                        cur = ReadWord(w);
                    }
                    LastWritten++;
                }
                p((w + 1) * wordBytes, Size);
            }
        }
        finally
        {
            try { Ewds(); } catch { }
        }
    }

    public void Erase(Progress p)
    {
        Ewen();
        try
        {
            bus.MwTransfer(Cmd(0b00, 0b10 << (abits - 2)), 0);
            if (!bus.MwWaitReady(100)) throw new IOException("ลบทั้งชิป (ERAL) แล้วชิปไม่ตอบ");
        }
        finally
        {
            try { Ewds(); } catch { }
        }
        p(1, 1);
    }
}
