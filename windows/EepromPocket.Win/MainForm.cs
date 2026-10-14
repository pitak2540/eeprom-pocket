using System.Diagnostics;
using System.IO.Ports;
using System.IO.Hashing;

namespace EepromPocket;

public sealed class MainForm : Form
{
    // ---- state
    private IProgrammer programmer;
    private byte[] buffer = Array.Empty<byte>();
    private string bufferSource = "";
    private int flashSize;
    private CancellationTokenSource cts;
    private volatile string phase = "";
    private const int BPR = 16;

    // ---- controls
    private readonly ComboBox cbDevType = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 300 };
    private readonly ComboBox cbPort = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 200 };
    private readonly Button btnRefreshPorts = new() { Text = "รีเฟรช", Width = 90 };
    private readonly Button btnConnect = new() { Text = "เชื่อมต่อ", Width = 300, Height = 34 };
    private readonly Label lblDevice = new() { Text = "ยังไม่ได้เชื่อมต่อ", AutoSize = false, Width = 300, Height = 36 };
    private readonly ComboBox cbFamily = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 300 };
    private readonly ComboBox cbChip = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 300 };
    private readonly RadioButton rbX8 = new() { Text = "x8", AutoSize = true };
    private readonly RadioButton rbX16 = new() { Text = "x16", AutoSize = true, Checked = true };
    private readonly CheckBox chkHiFirst = new() { Text = "ไบต์สูงก่อน (สลับไบต์)", AutoSize = true };
    private readonly CheckBox chkEraseFirst = new() { Text = "ลบชิปก่อนเขียน (Flash)", AutoSize = true, Checked = true };
    private readonly CheckBox chkVerify = new() { Text = "ตรวจสอบหลังเขียน (Verify)", AutoSize = true, Checked = true };
    private readonly Label lblChipInfo = new() { AutoSize = false, Width = 300, Height = 52 };
    private readonly Label lblBuf = new() { AutoSize = false, Width = 300, Height = 64, Text = "บัฟเฟอร์ว่าง" };
    private readonly Panel row93 = new() { Width = 300, Height = 28 };
    private readonly List<Control> opControls = new();
    private readonly ListView hexView = new()
    {
        View = View.Details, VirtualMode = true, FullRowSelect = true, Dock = DockStyle.Fill,
        Font = new Font("Consolas", 10f), HeaderStyle = ColumnHeaderStyle.Nonclickable, GridLines = false,
    };
    private readonly TextBox txtGoto = new() { Width = 120, PlaceholderText = "offset hex" };
    private readonly TextBox txtLog = new() { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill, Font = new Font("Consolas", 9.5f) };
    private readonly ToolStripProgressBar progress = new() { Maximum = 1000, Width = 300 };
    private readonly ToolStripStatusLabel lblStatus = new() { Text = "พร้อม", Spring = true, TextAlign = ContentAlignment.MiddleLeft };
    private readonly ToolStripButton btnCancel = new() { Text = "ยกเลิก", Enabled = false };

    public MainForm()
    {
        Text = "EEPROM Pocket for Windows";
        Width = 1180; Height = 780;
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Tahoma", 9.5f);
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

        BuildLayout();
        InitData();
        Log("พร้อมใช้งาน — เลือกชนิดอุปกรณ์แล้วกด 'เชื่อมต่อ'");
    }

    // ===================================================================== layout
    private void BuildLayout()
    {
        var left = new FlowLayoutPanel
        {
            Dock = DockStyle.Left, Width = 335, FlowDirection = FlowDirection.TopDown, WrapContents = false,
            AutoScroll = true, Padding = new Padding(10, 8, 6, 8),
        };

        left.Controls.Add(Title("อุปกรณ์"));
        left.Controls.Add(cbDevType);
        var portRow = new FlowLayoutPanel { Width = 310, Height = 32, FlowDirection = FlowDirection.LeftToRight, Margin = Padding.Empty };
        portRow.Controls.Add(cbPort); portRow.Controls.Add(btnRefreshPorts);
        left.Controls.Add(portRow);
        left.Controls.Add(btnConnect);
        left.Controls.Add(lblDevice);

        left.Controls.Add(Title("ชนิดชิป"));
        left.Controls.Add(cbFamily);
        left.Controls.Add(cbChip);
        rbX8.Location = new Point(0, 4); rbX16.Location = new Point(50, 4); chkHiFirst.Location = new Point(110, 4);
        row93.Controls.AddRange(new Control[] { rbX8, rbX16, chkHiFirst });
        left.Controls.Add(row93);
        left.Controls.Add(chkEraseFirst);
        left.Controls.Add(chkVerify);
        left.Controls.Add(lblChipInfo);

        left.Controls.Add(Title("คำสั่ง"));
        var grid = new TableLayoutPanel { ColumnCount = 3, RowCount = 3, Width = 310, Height = 126 };
        for (int i = 0; i < 3; i++) grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.3f));
        grid.Controls.Add(OpBtn("ตรวจ ID", (_, _) => DoDetect()));
        grid.Controls.Add(OpBtn("อ่าน", (_, _) => DoRead(), true));
        grid.Controls.Add(OpBtn("เขียน", (_, _) => DoWrite(), true));
        grid.Controls.Add(OpBtn("ตรวจสอบ", (_, _) => DoVerify()));
        grid.Controls.Add(OpBtn("ลบ", (_, _) => DoErase()));
        grid.Controls.Add(OpBtn("ตรวจว่าง", (_, _) => DoBlank()));
        grid.Controls.Add(OpBtn("เปิดไฟล์…", (_, _) => OpenFile()));
        grid.Controls.Add(OpBtn("บันทึกไฟล์…", (_, _) => SaveFile()));
        left.Controls.Add(grid);
        left.Controls.Add(Title("บัฟเฟอร์"));
        left.Controls.Add(lblBuf);

        // right: tabs
        var tabs = new TabControl { Dock = DockStyle.Fill };
        var tHex = new TabPage("Hex");
        var hexTop = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 34, Padding = new Padding(4) };
        var btnGoto = new Button { Text = "ไปที่", Width = 70 };
        btnGoto.Click += (_, _) => GotoOffset();
        txtGoto.KeyDown += (_, e) => { if (e.KeyCode == Keys.Enter) { GotoOffset(); e.SuppressKeyPress = true; } };
        hexTop.Controls.Add(new Label { Text = "Offset:", AutoSize = true, Padding = new Padding(0, 6, 0, 0) });
        hexTop.Controls.Add(txtGoto); hexTop.Controls.Add(btnGoto);
        hexTop.Controls.Add(new Label { Text = "   ดับเบิลคลิกที่บรรทัดเพื่อแก้ไขค่า", AutoSize = true, Padding = new Padding(0, 6, 0, 0), ForeColor = Color.DimGray });
        hexView.Columns.Add("Offset", 90);
        hexView.Columns.Add("00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F", 450);
        hexView.Columns.Add("ASCII", 170);
        hexView.RetrieveVirtualItem += HexRetrieve;
        hexView.DoubleClick += (_, _) => { if (hexView.SelectedIndices.Count > 0) EditRow(hexView.SelectedIndices[0]); };
        tHex.Controls.Add(hexView); tHex.Controls.Add(hexTop);

        var tLog = new TabPage("บันทึกการทำงาน");
        tLog.Controls.Add(txtLog);

        var tHelp = new TabPage("วิธีต่อสาย / ไดรเวอร์");
        tHelp.Controls.Add(new TextBox
        {
            Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill,
            Text = HelpText.Text.Replace("\n", "\r\n"), Font = new Font("Tahoma", 10f), BackColor = SystemColors.Window,
        });
        tabs.TabPages.AddRange(new[] { tHex, tLog, tHelp });

        var status = new StatusStrip();
        btnCancel.Click += (_, _) => { cts?.Cancel(); Log("กำลังยกเลิก…"); };
        status.Items.AddRange(new ToolStripItem[] { progress, lblStatus, btnCancel });

        Controls.Add(tabs);
        Controls.Add(left);
        Controls.Add(status);

        btnConnect.Click += (_, _) => ToggleConnect();
        btnRefreshPorts.Click += (_, _) => RefreshPorts();
        cbDevType.SelectedIndexChanged += (_, _) => { cbPort.Enabled = btnRefreshPorts.Enabled = cbDevType.SelectedIndex == 2; };
        cbFamily.SelectedIndexChanged += (_, _) => FamilyChanged();
        cbChip.SelectedIndexChanged += (_, _) => UpdateChipInfo();
        rbX8.CheckedChanged += (_, _) => UpdateChipInfo();
        FormClosing += (_, _) => { cts?.Cancel(); Disconnect(); };
    }

    private static Label Title(string t) => new()
    {
        Text = t, AutoSize = true, Font = new Font("Tahoma", 10.5f, FontStyle.Bold),
        ForeColor = Color.FromArgb(14, 124, 102), Margin = new Padding(0, 10, 0, 4),
    };

    private Button OpBtn(string text, EventHandler h, bool primary = false)
    {
        var b = new Button { Text = text, Dock = DockStyle.Fill, Height = 36, Margin = new Padding(2) };
        if (primary) { b.BackColor = Color.FromArgb(14, 124, 102); b.ForeColor = Color.White; b.FlatStyle = FlatStyle.Flat; }
        b.Click += h;
        opControls.Add(b);
        return b;
    }

    private void InitData()
    {
        cbDevType.Items.AddRange(new object[]
        {
            "CH341A Programmer (ไดรเวอร์ CH341PAR)",
            "FTDI FT232H / FT2232H / FT4232H (MPSSE)",
            "CH340 / Arduino Bridge (พอร์ต COM)",
        });
        cbDevType.SelectedIndex = 0;
        RefreshPorts();
        foreach (var f in ChipDb.Families) cbFamily.Items.Add(f.Label);
        cbFamily.SelectedIndex = 0;
    }

    private void RefreshPorts()
    {
        cbPort.Items.Clear();
        foreach (var p in SerialPort.GetPortNames().OrderBy(x => x)) cbPort.Items.Add(p);
        if (cbPort.Items.Count > 0) cbPort.SelectedIndex = cbPort.Items.Count - 1;
    }

    private Family CurrentFamily => ChipDb.Families[Math.Max(cbFamily.SelectedIndex, 0)].Family;
    private Chip CurrentChip => cbChip.SelectedItem as Chip;

    private void FamilyChanged()
    {
        var fam = CurrentFamily;
        cbChip.Items.Clear();
        cbChip.Items.AddRange(ChipDb.All[fam]);
        string def = fam switch { Family.I2C24 => "24C02", Family.SPI25EE => "25640", Family.MW93 => "93C46", _ => null };
        int idx = def == null ? 0 : Array.FindIndex(ChipDb.All[fam], c => c.Name.StartsWith(def));
        cbChip.SelectedIndex = Math.Max(idx, 0);
        row93.Visible = fam == Family.MW93;
        chkEraseFirst.Visible = fam == Family.SPI25FLASH;
        UpdateChipInfo();
    }

    private void UpdateChipInfo()
    {
        var c = CurrentChip;
        if (c == null) return;
        lblChipInfo.Text = c.Family switch
        {
            Family.I2C24 => $"ขนาด {Bits.FmtSize(c.Size)} · page {c.Page} ไบต์ · แอดเดรส {c.AddrBytes} ไบต์\nขา A0-A2 และ WP ต่อ GND",
            Family.SPI25EE => $"ขนาด {Bits.FmtSize(c.Size)} · page {c.Page} ไบต์ · แอดเดรส {c.AddrBytes} ไบต์\nขา WP / HOLD ต่อ VCC",
            Family.SPI25FLASH => $"ขนาด {((c.Size == 0 ? flashSize : c.Size) is int s && s > 0 ? Bits.FmtSize(s) : "ยังไม่ทราบ — กด 'ตรวจ ID'")} · page 256\nต้องลบก่อนเขียน",
            Family.MW93 => $"ขนาด {Bits.FmtSize(c.Size)} · {(rbX16.Checked ? $"x16 ({c.Extra} address bits)" : $"x8 ({c.AddrBytes} address bits)")}\nขา ORG: VCC = x16, GND = x8",
            _ => "",
        };
    }

    // ===================================================================== log / ui helpers
    private void Log(string s)
    {
        if (InvokeRequired) { BeginInvoke(() => Log(s)); return; }
        txtLog.AppendText($"[{DateTime.Now:HH:mm:ss}] {s}\r\n");
        lblStatus.Text = s.Length > 120 ? s[..120] + "…" : s;
    }

    private void SetBusy(bool b)
    {
        foreach (var c in opControls) c.Enabled = !b;
        btnConnect.Enabled = !b;
        cbFamily.Enabled = cbChip.Enabled = cbDevType.Enabled = !b;
        btnCancel.Enabled = b;
        UseWaitCursor = b;
    }

    private void Ui(Action a) => Invoke(a);

    // ===================================================================== device
    private async void ToggleConnect()
    {
        if (programmer != null) { Disconnect(); Log("ตัดการเชื่อมต่อแล้ว"); return; }
        int type = cbDevType.SelectedIndex;
        string port = cbPort.SelectedItem as string;
        if (type == 2 && port == null) { MessageBox.Show(this, "ไม่พบพอร์ต COM — เสียบบอร์ดแล้วกด 'รีเฟรช'", Text); return; }
        SetBusy(true);
        lblDevice.Text = "กำลังเชื่อมต่อ…";
        if (type == 2) Log($"กำลังรอ Arduino Bridge บน {port} (≈2 วินาที)…");
        try
        {
            programmer = await Task.Run<IProgrammer>(() => type switch
            {
                0 => Ch341Programmer.Open(),
                1 => FtdiMpsse.Open(),
                _ => BridgeProgrammer.Open(port),
            });
            lblDevice.Text = programmer.Title;
            btnConnect.Text = "ตัดการเชื่อมต่อ";
            Log("✔ เชื่อมต่อ " + programmer.Title);
        }
        catch (Exception e)
        {
            lblDevice.Text = "ยังไม่ได้เชื่อมต่อ";
            Log("✖ เชื่อมต่อไม่ได้: " + e.Message);
        }
        finally { SetBusy(false); }
    }

    private void Disconnect()
    {
        try { programmer?.Dispose(); } catch { }
        programmer = null;
        lblDevice.Text = "ยังไม่ได้เชื่อมต่อ";
        btnConnect.Text = "เชื่อมต่อ";
    }

    // ===================================================================== operations
    private IChipOps MakeOps(Chip c) => c.Family switch
    {
        Family.I2C24 => new I2cEeprom(programmer, c),
        Family.SPI25EE => new SpiEeprom(programmer, c),
        Family.SPI25FLASH => new SpiFlash(programmer, c.Size == 0 ? flashSize : c.Size),
        _ => new MicrowireEeprom(programmer, c, rbX16.Checked, chkHiFirst.Checked),
    };

    private async void RunOp(string name, Action<IChipOps, Progress> body)
    {
        if (cts != null) return;
        if (programmer == null) { MessageBox.Show(this, "ยังไม่ได้เชื่อมต่ออุปกรณ์", Text); return; }
        var c = CurrentChip;
        if (c == null) return;
        var ops = MakeOps(c);
        cts = new CancellationTokenSource();
        var token = cts.Token;
        SetBusy(true);
        phase = name;
        progress.Value = 0;
        Log($"▶ {name} — {c.Name}");
        var sw = Stopwatch.StartNew();
        long lastUi = 0;
        Progress pr = (done, total) =>
        {
            token.ThrowIfCancellationRequested();
            long now = sw.ElapsedMilliseconds;
            if (now - lastUi > 80 || done >= total)
            {
                lastUi = now;
                int per = total > 0 ? (int)(done * 1000 / total) : 0;
                string ph = phase;
                BeginInvoke(() => { progress.Value = Math.Clamp(per, 0, 1000); lblStatus.Text = $"{ph} {per / 10}%  ({done}/{total})"; });
            }
        };
        try
        {
            await Task.Run(() => body(ops, pr), token);
            progress.Value = 1000;
            lblStatus.Text = $"{name} เสร็จ ({sw.Elapsed.TotalSeconds:0.0} วินาที)";
        }
        catch (OperationCanceledException) { Log("■ ยกเลิก " + name); lblStatus.Text = "ยกเลิกแล้ว"; }
        catch (Exception e) { Log($"✖ {name} ผิดพลาด: {e.Message}"); lblStatus.Text = "ผิดพลาด"; }
        finally
        {
            cts.Dispose(); cts = null;
            SetBusy(false);
            if (ops is SpiFlash f && f.Size > 0 && c.Size == 0) { flashSize = f.Size; UpdateChipInfo(); }
        }
    }

    private static byte[] Fit(byte[] b, int size)
    {
        if (b.Length == size) return b;
        var o = new byte[size];
        for (int i = 0; i < size; i++) o[i] = i < b.Length ? b[i] : (byte)0xFF;
        return o;
    }

    private void DoDetect() => RunOp("ตรวจ ID", (ops, _) => Log(ops.Detect()));

    private void DoRead() => RunOp("อ่าน", (ops, pr) =>
    {
        var d = ops.Read(pr);
        Ui(() => SetBuffer(d, "อ่านจากชิป " + CurrentChip?.Name));
        Log($"✔ อ่านสำเร็จ {d.Length} ไบต์ · CRC32 {Crc(d)}" + (d.All(b => b == 0xFF) ? " · (ชิปว่างทั้งหมด FF)" : ""));
    });

    private void Verify(IChipOps ops, Progress pr)
    {
        var r = ops.Read(pr);
        var refd = Fit(buffer, r.Length);
        int bad = 0, first = -1;
        for (int i = 0; i < r.Length; i++) if (r[i] != refd[i]) { if (first < 0) first = i; bad++; }
        if (bad == 0) Log($"✔ ตรวจสอบผ่าน ข้อมูลตรงกันทั้งหมด ({r.Length} ไบต์)");
        else
        {
            Log($"✖ ข้อมูลไม่ตรง {bad} ไบต์ — ตำแหน่งแรก 0x{first:X}: ชิป={r[first]:X2} ไฟล์={refd[first]:X2}");
            throw new IOException("Verify ไม่ผ่าน");
        }
    }

    private void DoVerify()
    {
        if (buffer.Length == 0) { MessageBox.Show(this, "บัฟเฟอร์ว่าง — เปิดไฟล์ก่อน", Text); return; }
        RunOp("ตรวจสอบ", Verify);
    }

    private void DoBlank() => RunOp("ตรวจว่าง", (ops, pr) =>
    {
        var r = ops.Read(pr);
        int n = r.Count(b => b != 0xFF);
        Log(n == 0 ? "✔ ชิปว่าง (FF ทั้งหมด)" : $"ชิปไม่ว่าง: มีข้อมูล {n} ไบต์ (ตำแหน่งแรก 0x{Array.FindIndex(r, b => b != 0xFF):X})");
    });

    private void DoErase()
    {
        if (MessageBox.Show(this, "ข้อมูลทั้งหมดในชิปจะถูกลบเป็น FF ต้องการดำเนินการต่อ?", "ลบชิป", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning) != DialogResult.OK) return;
        RunOp("ลบ", (ops, pr) => { ops.Erase(pr); Log("✔ ลบเสร็จ"); });
    }

    private void DoWrite()
    {
        if (buffer.Length == 0) { MessageBox.Show(this, "บัฟเฟอร์ว่าง — เปิดไฟล์ .bin หรืออ่านชิปก่อน", Text); return; }
        var c = CurrentChip;
        if (MessageBox.Show(this, $"เขียนข้อมูล {buffer.Length} ไบต์ ลงชิป {c?.Name}?\nข้อมูลเดิมในชิปจะถูกทับ", "เขียนชิป", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning) != DialogResult.OK) return;
        bool eraseFirst = chkEraseFirst.Checked, verify = chkVerify.Checked;
        RunOp("เขียน", (ops, pr) =>
        {
            if (ops is SpiFlash f)
            {
                f.DetectSizeIfNeeded();
                if (eraseFirst) { phase = "ลบ"; f.Erase(pr); }
            }
            if (buffer.Length != ops.Size)
                Log($"⚠ ขนาดไฟล์ {buffer.Length} ≠ ชิป {ops.Size} ไบต์ ({(buffer.Length < ops.Size ? "เติม FF" : "ตัดส่วนเกิน")})");
            phase = "เขียน";
            ops.Write(Fit(buffer, ops.Size), pr);
            if (ops is MicrowireEeprom m)
                Log($"✔ เขียนเสร็จ — เขียนใหม่ {m.LastWritten} เวิร์ด, ค่าเดิมตรงแล้ว {m.LastSkipped} เวิร์ด (ตรวจทีละเวิร์ดแล้ว)");
            else Log("✔ เขียนเสร็จ");
            if (verify) { phase = "ตรวจสอบ"; Verify(ops, pr); }
        });
    }

    // ===================================================================== buffer / files / hex
    private static string Crc(byte[] d) => Crc32.HashToUInt32(d).ToString("X8");

    private void SetBuffer(byte[] d, string src)
    {
        buffer = d;
        bufferSource = src;
        hexView.VirtualListSize = (d.Length + BPR - 1) / BPR;
        hexView.Invalidate();
        UpdateBufInfo();
    }

    private void UpdateBufInfo()
    {
        if (buffer.Length == 0) { lblBuf.Text = "บัฟเฟอร์ว่าง"; return; }
        long sum = 0;
        foreach (var b in buffer) sum += b;
        lblBuf.Text = $"ขนาด: {buffer.Length} ไบต์ ({Bits.FmtSize(buffer.Length)})\nCRC32: {Crc(buffer)}   SUM: {sum & 0xFFFF:X4}\nที่มา: {bufferSource}";
    }

    private void OpenFile()
    {
        using var dlg = new OpenFileDialog { Filter = "Binary (*.bin)|*.bin|ทุกไฟล์ (*.*)|*.*" };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            var fi = new FileInfo(dlg.FileName);
            if (fi.Length > 64L * 1024 * 1024) throw new IOException("ไฟล์ใหญ่เกิน 64MB");
            var d = File.ReadAllBytes(dlg.FileName);
            SetBuffer(d, fi.Name);
            Log($"เปิดไฟล์ {fi.Name} ({d.Length} ไบต์)");
            var c = CurrentChip;
            int s = c == null ? 0 : (c.Family == Family.SPI25FLASH && c.Size == 0 ? flashSize : c.Size);
            if (s > 0 && s != d.Length) Log($"⚠ ขนาดไฟล์ไม่เท่ากับชิปที่เลือก ({Bits.FmtSize(s)})");
        }
        catch (Exception e) { Log("✖ เปิดไฟล์ไม่ได้: " + e.Message); }
    }

    private void SaveFile()
    {
        if (buffer.Length == 0) { MessageBox.Show(this, "บัฟเฟอร์ว่าง — อ่านชิปหรือเปิดไฟล์ก่อน", Text); return; }
        string chip = (CurrentChip?.Name ?? "eeprom").Split(' ')[0].Replace("/", "_");
        using var dlg = new SaveFileDialog { Filter = "Binary (*.bin)|*.bin", FileName = $"{chip}_{DateTime.Now:yyyyMMdd_HHmm}.bin" };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            File.WriteAllBytes(dlg.FileName, buffer);
            Log($"✔ บันทึก {Path.GetFileName(dlg.FileName)} ({buffer.Length} ไบต์)");
        }
        catch (Exception e) { Log("✖ บันทึกไม่ได้: " + e.Message); }
    }

    private void HexRetrieve(object sender, RetrieveVirtualItemEventArgs e)
    {
        int start = e.ItemIndex * BPR;
        int end = Math.Min(start + BPR, buffer.Length);
        var hex = new System.Text.StringBuilder(BPR * 3 + 2);
        var asc = new System.Text.StringBuilder(BPR);
        for (int i = start; i < start + BPR; i++)
        {
            if (i == start + 8) hex.Append(' ');
            if (i < end) { hex.Append(buffer[i].ToString("X2")).Append(' '); asc.Append(buffer[i] is >= 0x20 and <= 0x7E ? (char)buffer[i] : '.'); }
            else hex.Append("   ");
        }
        var item = new ListViewItem(start.ToString("X6"));
        item.SubItems.Add(hex.ToString());
        item.SubItems.Add(asc.ToString());
        e.Item = item;
    }

    private void GotoOffset()
    {
        string s = txtGoto.Text.Trim();
        if (s.StartsWith("0x", StringComparison.OrdinalIgnoreCase)) s = s[2..];
        if (!int.TryParse(s, System.Globalization.NumberStyles.HexNumber, null, out int off) || off < 0 || off >= buffer.Length)
        { MessageBox.Show(this, "offset ไม่ถูกต้อง", Text); return; }
        int row = off / BPR;
        hexView.EnsureVisible(row);
        hexView.SelectedIndices.Clear();
        hexView.SelectedIndices.Add(row);
        hexView.Focus();
    }

    private void EditRow(int row)
    {
        if (cts != null) return;
        int start = row * BPR, end = Math.Min(start + BPR, buffer.Length);
        if (start >= end) return;
        string cur = string.Join(" ", Enumerable.Range(start, end - start).Select(i => buffer[i].ToString("X2")));
        using var f = new Form
        {
            Text = $"แก้ไข 0x{start:X6}", Width = 560, Height = 150, FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = FormStartPosition.CenterParent, MinimizeBox = false, MaximizeBox = false,
        };
        var tb = new TextBox { Text = cur, Font = new Font("Consolas", 11f), Left = 12, Top = 14, Width = 520 };
        var ok = new Button { Text = "บันทึก", DialogResult = DialogResult.OK, Left = 352, Top = 56, Width = 85 };
        var cancel = new Button { Text = "ยกเลิก", DialogResult = DialogResult.Cancel, Left = 447, Top = 56, Width = 85 };
        f.Controls.AddRange(new Control[] { tb, ok, cancel });
        f.AcceptButton = ok; f.CancelButton = cancel;
        if (f.ShowDialog(this) != DialogResult.OK) return;
        var hex = new string(tb.Text.Where(Uri.IsHexDigit).ToArray());
        if (hex.Length != (end - start) * 2) { MessageBox.Show(this, $"ต้องใส่ {end - start} ไบต์ (hex)", Text); return; }
        for (int i = 0; i < end - start; i++) buffer[start + i] = Convert.ToByte(hex.Substring(i * 2, 2), 16);
        if (!bufferSource.EndsWith("(แก้ไข)")) bufferSource += " (แก้ไข)";
        hexView.RedrawItems(row, row, false);
        UpdateBufInfo();
    }
}
