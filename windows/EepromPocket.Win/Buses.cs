namespace EepromPocket;

/// <summary>I2C bus (24xx). addr = 7-bit address.</summary>
public interface II2cBus
{
    int I2cMaxRead { get; }
    int I2cMaxWrite { get; }
    /// <summary>true = adapter reports ACK/NACK (used for ACK polling after a page write)</summary>
    bool I2cAckAware { get; }
    bool I2cWrite(int addr, byte[] data);
    byte[] I2cWriteRead(int addr, byte[] w, int rlen);
}

/// <summary>SPI bus (25xx). One call = CS low, clock out write, clock in readLen bytes, CS high.</summary>
public interface ISpiBus
{
    /// <summary>Maximum write+read bytes in one CS cycle.</summary>
    int SpiMaxTransfer { get; }
    byte[] Spi(byte[] write, int readLen);
}

/// <summary>Microwire bus (93xx). CS is active HIGH.</summary>
public interface IMicrowireBus
{
    /// <summary>CS low->high, clock out bits, clock in readBits (MSB first, packed), CS low.</summary>
    byte[] MwTransfer(bool[] outBits, int readBits);
    /// <summary>CS low->high, wait until DO goes high (write cycle done), CS low.</summary>
    bool MwWaitReady(int timeoutMs);
}

public interface IProgrammer : II2cBus, ISpiBus, IMicrowireBus, IDisposable
{
    string Title { get; }
}

/// <summary>Progress callback; throws OperationCanceledException to cancel.</summary>
public delegate void Progress(long done, long total);

public static class Bits
{
    public static byte[] Pack(int n, Func<int, bool> bit)
    {
        var o = new byte[(n + 7) / 8];
        for (int i = 0; i < n; i++)
            if (bit(i)) o[i >> 3] |= (byte)(0x80 >> (i & 7));
        return o;
    }

    public static bool[] Of(long value, int count)
    {
        var r = new bool[count];
        for (int i = 0; i < count; i++) r[i] = ((value >> (count - 1 - i)) & 1) == 1;
        return r;
    }

    public static bool[] Concat(bool[] a, bool[] b)
    {
        var r = new bool[a.Length + b.Length];
        a.CopyTo(r, 0);
        b.CopyTo(r, a.Length);
        return r;
    }

    public static byte[] Concat(byte[] a, byte[] b)
    {
        var r = new byte[a.Length + b.Length];
        Buffer.BlockCopy(a, 0, r, 0, a.Length);
        Buffer.BlockCopy(b, 0, r, a.Length, b.Length);
        return r;
    }

    public static string FmtSize(int n) =>
        n >= 1024 * 1024 ? $"{n / (1024 * 1024)} MB" : n >= 1024 ? $"{n / 1024} KB" : $"{n} B";
}
