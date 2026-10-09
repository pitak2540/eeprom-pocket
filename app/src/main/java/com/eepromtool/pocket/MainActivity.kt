package com.eepromtool.pocket

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eepromtool.pocket.chip.Chip
import com.eepromtool.pocket.chip.ChipDb
import com.eepromtool.pocket.chip.ChipOps
import com.eepromtool.pocket.chip.Family
import com.eepromtool.pocket.chip.I2cEeprom
import com.eepromtool.pocket.chip.MicrowireEeprom
import com.eepromtool.pocket.chip.Progress
import com.eepromtool.pocket.chip.SpiEeprom
import com.eepromtool.pocket.chip.SpiFlash
import com.eepromtool.pocket.chip.fmtSize
import com.eepromtool.pocket.ui.HexAdapter
import com.eepromtool.pocket.usb.DeviceFactory
import com.eepromtool.pocket.usb.Programmer
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_PERM = "com.eepromtool.pocket.USB_PERMISSION"
    }

    private lateinit var usb: UsbManager
    private var programmer: Programmer? = null
    private var programmerDevice: UsbDevice? = null
    private var buffer = ByteArray(0)
    private var bufferSource = ""
    private var job: Job? = null
    private var flashSize = 0
    @Volatile private var phase = ""

    private lateinit var tvDevice: TextView
    private lateinit var btnConnect: Button
    private lateinit var spFamily: Spinner
    private lateinit var spChip: Spinner
    private lateinit var row93: LinearLayout
    private lateinit var rbX16: RadioButton
    private lateinit var cbSwap: CheckBox
    private lateinit var cbEraseFirst: CheckBox
    private lateinit var cbVerify: CheckBox
    private lateinit var tvChipInfo: TextView
    private lateinit var tvBufInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnCancel: Button
    private lateinit var rvHex: RecyclerView
    private lateinit var hex: HexAdapter
    private var bytesPerRow = 8
    private val opButtons = ArrayList<View>()
    private val logLines = ArrayDeque<String>()

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { loadFile(it) } }
    private val saveDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { saveFile(it) } }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = IntentCompat.getParcelableExtra(i, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (i.action) {
                ACTION_PERM ->
                    if (dev != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openDevice(dev)
                    else log("✖ ไม่ได้รับสิทธิ์ใช้งาน USB")
                UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    if (dev != null && dev.deviceName == programmerDevice?.deviceName) {
                        job?.cancel()
                        disconnect()
                        log("อุปกรณ์ถูกถอดออก")
                    }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        tvDevice = findViewById(R.id.tvDevice)
        btnConnect = findViewById(R.id.btnConnect)
        spFamily = findViewById(R.id.spFamily)
        spChip = findViewById(R.id.spChip)
        row93 = findViewById(R.id.row93)
        rbX16 = findViewById(R.id.rbX16)
        cbSwap = findViewById(R.id.cbSwap)
        cbEraseFirst = findViewById(R.id.cbEraseFirst)
        cbVerify = findViewById(R.id.cbVerify)
        tvChipInfo = findViewById(R.id.tvChipInfo)
        tvBufInfo = findViewById(R.id.tvBufInfo)
        tvLog = findViewById(R.id.tvLog)
        tvStatus = findViewById(R.id.tvStatus)
        progress = findViewById(R.id.progress)
        btnCancel = findViewById(R.id.btnCancel)
        rvHex = findViewById(R.id.rvHex)
        findViewById<TextView>(R.id.tvHelp).text = HelpText.TEXT

        setupTabs()
        setupChipSelectors()

        bytesPerRow = if (resources.configuration.screenWidthDp >= 600) 16 else 8
        hex = HexAdapter(bytesPerRow) { row -> editRow(row) }
        rvHex.layoutManager = LinearLayoutManager(this)
        rvHex.adapter = hex

        btnConnect.setOnClickListener { if (programmer != null) { disconnect(); log("ตัดการเชื่อมต่อแล้ว") } else scanDevices() }
        bindOp(R.id.btnDetect) { doDetect() }
        bindOp(R.id.btnRead) { doRead() }
        bindOp(R.id.btnWrite) { doWrite() }
        bindOp(R.id.btnVerify) { doVerify() }
        bindOp(R.id.btnErase) { doErase() }
        bindOp(R.id.btnBlank) { doBlank() }
        bindOp(R.id.btnOpen) { openDoc.launch(arrayOf("*/*")) }
        bindOp(R.id.btnSave) {
            if (buffer.isEmpty()) toast("บัฟเฟอร์ว่าง — อ่านชิปหรือเปิดไฟล์ก่อน")
            else saveDoc.launch(suggestName())
        }
        btnCancel.setOnClickListener { job?.cancel(); log("กำลังยกเลิก…") }
        findViewById<Button>(R.id.btnGoto).setOnClickListener { gotoOffset() }

        val f = IntentFilter().apply {
            addAction(ACTION_PERM)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)

        updateBufInfo()
        log("พร้อมใช้งาน — เสียบโปรแกรมเมอร์ผ่านสาย OTG แล้วกด 'เชื่อมต่อ'")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        job?.cancel()
        disconnect()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && programmer == null) {
            val d = IntentCompat.getParcelableExtra(i, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            if (DeviceFactory.classify(d) != null) requestAndOpen(d)
        }
    }

    // ---------------------------------------------------------------- UI setup
    private fun setupTabs() {
        val tabs = findViewById<TabLayout>(R.id.tabs)
        val panels = listOf<View>(findViewById<ScrollView>(R.id.panelControl), findViewById(R.id.panelHex), findViewById<ScrollView>(R.id.panelHelp))
        listOf("ควบคุม", "Hex", "วิธีต่อสาย").forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                panels.forEachIndexed { i, v -> v.visibility = if (i == tab.position) View.VISIBLE else View.GONE }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupChipSelectors() {
        val fams = Family.values()
        spFamily.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fams.map { it.label })
        spFamily.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val fam = fams[pos]
                val list = ChipDb.all[fam] ?: emptyList()
                spChip.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, list)
                val def = when (fam) {
                    Family.I2C24 -> list.indexOfFirst { it.name == "24C02" }
                    Family.SPI25EE -> list.indexOfFirst { it.name.startsWith("25640") }
                    Family.MW93 -> list.indexOfFirst { it.name == "93C46" }
                    else -> 0
                }
                spChip.setSelection(maxOf(def, 0))
                row93.visibility = if (fam == Family.MW93) View.VISIBLE else View.GONE
                cbEraseFirst.visibility = if (fam == Family.SPI25FLASH) View.VISIBLE else View.GONE
                updateChipInfo()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spChip.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updateChipInfo()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        findViewById<View>(R.id.rbX8).setOnClickListener { updateChipInfo() }
        rbX16.setOnClickListener { updateChipInfo() }
    }

    private fun currentChip(): Chip? = spChip.selectedItem as? Chip

    private fun updateChipInfo() {
        val c = currentChip() ?: return
        tvChipInfo.text = when (c.family) {
            Family.I2C24 -> "ขนาด ${fmtSize(c.size)} · page ${c.page} ไบต์ · แอดเดรส ${c.addrBytes} ไบต์ · ขา A0-A2 ต่อ GND, WP ต่อ GND เพื่อเขียน"
            Family.SPI25EE -> "ขนาด ${fmtSize(c.size)} · page ${c.page} ไบต์ · แอดเดรส ${c.addrBytes} ไบต์ · ขา WP/HOLD ต่อ VCC"
            Family.SPI25FLASH -> {
                val s = if (c.size == 0) flashSize else c.size
                "ขนาด ${if (s > 0) fmtSize(s) else "ยังไม่ทราบ — กด 'ตรวจ ID'"} · page 256 ไบต์ · ต้องลบก่อนเขียน"
            }
            Family.MW93 -> {
                val x16 = rbX16.isChecked
                "ขนาด ${fmtSize(c.size)} · ${if (x16) "x16 (${c.extra} address bits)" else "x8 (${c.addrBytes} address bits)"} · ขา ORG: VCC = x16, GND = x8"
            }
        }
    }

    private fun bindOp(id: Int, action: () -> Unit) {
        val v = findViewById<View>(id)
        v.setOnClickListener { action() }
        opButtons.add(v)
    }

    private fun setBusy(b: Boolean) {
        opButtons.forEach { it.isEnabled = !b }
        btnConnect.isEnabled = !b
        spFamily.isEnabled = !b
        spChip.isEnabled = !b
        btnCancel.isEnabled = b
        if (b) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun log(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logLines.addFirst("[$t] $s")
        while (logLines.size > 150) logLines.removeLast()
        tvLog.text = logLines.joinToString("\n")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- USB
    private fun scanDevices() {
        val devs = usb.deviceList.values.filter { DeviceFactory.classify(it) != null }
        when {
            devs.isEmpty() -> {
                log("✖ ไม่พบอุปกรณ์ที่รองรับ — ตรวจสาย OTG และเปิด OTG ในตั้งค่ามือถือ (บางรุ่น)")
                val all = usb.deviceList.values
                if (all.isNotEmpty()) log("อุปกรณ์ USB ที่เห็น: " + all.joinToString { "%04X:%04X".format(it.vendorId, it.productId) })
            }
            devs.size == 1 -> requestAndOpen(devs[0])
            else -> AlertDialog.Builder(this)
                .setTitle("เลือกอุปกรณ์")
                .setItems(devs.map { DeviceFactory.describe(it) }.toTypedArray()) { _, i -> requestAndOpen(devs[i]) }
                .show()
        }
    }

    private fun requestAndOpen(d: UsbDevice) {
        if (usb.hasPermission(d)) { openDevice(d); return }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), flags)
        usb.requestPermission(d, pi)
    }

    private fun openDevice(d: UsbDevice) {
        if (job?.isActive == true) return
        disconnect()
        tvDevice.text = "กำลังเชื่อมต่อ…"
        val kind = DeviceFactory.classify(d)
        if (kind != null && kind.name.startsWith("BRIDGE")) log("กำลังรอ Arduino Bridge (≈2 วินาที)…")
        setBusy(true)
        job = lifecycleScope.launch {
            try {
                val p = withContext(Dispatchers.IO) { DeviceFactory.open(usb, d) }
                programmer = p
                programmerDevice = d
                tvDevice.text = p.title
                btnConnect.text = "ตัดการเชื่อมต่อ"
                log("✔ เชื่อมต่อ ${DeviceFactory.describe(d)}")
            } catch (e: Exception) {
                tvDevice.text = "ยังไม่ได้เชื่อมต่อ"
                log("✖ เชื่อมต่อไม่ได้: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun disconnect() {
        try { programmer?.close() } catch (_: Exception) {}
        programmer = null
        programmerDevice = null
        if (::tvDevice.isInitialized) {
            tvDevice.text = "ยังไม่ได้เชื่อมต่อ"
            btnConnect.text = "เชื่อมต่อ"
        }
    }

    // ---------------------------------------------------------------- operations
    private fun makeOps(p: Programmer, c: Chip): ChipOps = when (c.family) {
        Family.I2C24 -> I2cEeprom(p, c)
        Family.SPI25EE -> SpiEeprom(p, c)
        Family.SPI25FLASH -> SpiFlash(p, if (c.size == 0) flashSize else c.size)
        Family.MW93 -> MicrowireEeprom(p, c, rbX16.isChecked, cbSwap.isChecked)
    }

    private fun runOp(name: String, block: suspend CoroutineScope.(ChipOps, Progress) -> Unit) {
        if (job?.isActive == true) return
        val p = programmer ?: run { toast("ยังไม่ได้เชื่อมต่ออุปกรณ์"); return }
        val c = currentChip() ?: return
        val ops = makeOps(p, c)
        setBusy(true)
        phase = name
        progress.progress = 0
        tvStatus.text = "$name…"
        log("▶ $name — ${c.name}")
        job = lifecycleScope.launch {
            val t0 = SystemClock.uptimeMillis()
            try {
                withContext(Dispatchers.IO) {
                    val myJob = coroutineContext.job
                    var lastUi = 0L
                    val pr = Progress { done, total ->
                        if (!myJob.isActive) throw CancellationException("ยกเลิก")
                        val now = SystemClock.uptimeMillis()
                        if (now - lastUi > 80 || done >= total) {
                            lastUi = now
                            val per = if (total > 0) (done * 1000 / total).toInt() else 0
                            val ph = phase
                            runOnUiThread {
                                progress.progress = per
                                tvStatus.text = "$ph ${per / 10}%  (${done}/${total})"
                            }
                        }
                    }
                    this.block(ops, pr)
                }
                val dt = (SystemClock.uptimeMillis() - t0) / 100 / 10.0
                tvStatus.text = "$name เสร็จ (${dt} วินาที)"
                progress.progress = 1000
            } catch (e: CancellationException) {
                tvStatus.text = "ยกเลิกแล้ว"
                log("■ ยกเลิก $name")
            } catch (e: Exception) {
                tvStatus.text = "ผิดพลาด"
                log("✖ $name ผิดพลาด: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                setBusy(false)
                if (ops is SpiFlash && ops.size > 0 && c.size == 0) { flashSize = ops.size; updateChipInfo() }
            }
        }
    }

    private suspend fun ui(f: () -> Unit) = withContext(Dispatchers.Main) { f() }

    private fun doDetect() = runOp("ตรวจ ID") { ops, _ ->
        val s = ops.detect()
        ui { log(s) }
    }

    private fun doRead() = runOp("อ่าน") { ops, pr ->
        val d = ops.read(pr)
        ui {
            setBuffer(d, "อ่านจากชิป ${currentChip()?.name}")
            log("✔ อ่านสำเร็จ ${d.size} ไบต์ · CRC32 ${crc(d)}" + if (d.all { it == 0xFF.toByte() }) " · (ชิปว่างทั้งหมด FF)" else "")
        }
    }

    private fun doVerify() {
        if (buffer.isEmpty()) { toast("บัฟเฟอร์ว่าง — เปิดไฟล์ก่อน"); return }
        runOp("ตรวจสอบ") { ops, pr -> verify(ops, pr) }
    }

    private suspend fun verify(ops: ChipOps, pr: Progress) {
        val r = ops.read(pr)
        val ref = fit(buffer, r.size)
        var bad = 0
        var first = -1
        for (i in r.indices) if (r[i] != ref[i]) { if (first < 0) first = i; bad++ }
        ui {
            if (bad == 0) log("✔ ตรวจสอบผ่าน ข้อมูลตรงกันทั้งหมด (${r.size} ไบต์)")
            else log("✖ ข้อมูลไม่ตรง $bad ไบต์ — ตำแหน่งแรก 0x%X: ชิป=%02X ไฟล์=%02X".format(first, r[first], ref[first]))
        }
        if (bad > 0) throw java.io.IOException("Verify ไม่ผ่าน")
    }

    private fun doBlank() = runOp("ตรวจว่าง") { ops, pr ->
        val r = ops.read(pr)
        val n = r.count { it != 0xFF.toByte() }
        ui { log(if (n == 0) "✔ ชิปว่าง (FF ทั้งหมด)" else "ชิปไม่ว่าง: มีข้อมูล $n ไบต์ (ตำแหน่งแรก 0x%X)".format(r.indexOfFirst { it != 0xFF.toByte() })) }
    }

    private fun doErase() {
        confirm("ลบชิป", "ข้อมูลทั้งหมดในชิปจะถูกลบเป็น FF ต้องการดำเนินการต่อ?") {
            runOp("ลบ") { ops, pr -> ops.erase(pr); ui { log("✔ ลบเสร็จ") } }
        }
    }

    private fun doWrite() {
        if (buffer.isEmpty()) { toast("บัฟเฟอร์ว่าง — เปิดไฟล์ .bin หรืออ่านชิปก่อน"); return }
        val c = currentChip() ?: return
        val eraseFirst = cbEraseFirst.isChecked
        val verifyAfter = cbVerify.isChecked
        confirm("เขียนชิป", "เขียนข้อมูล ${buffer.size} ไบต์ ลงชิป ${c.name}?\nข้อมูลเดิมในชิปจะถูกทับ") {
            runOp("เขียน") { ops, pr ->
                if (ops is SpiFlash) {
                    ops.detectSizeIfNeeded()
                    if (eraseFirst) { phase = "ลบ"; ops.erase(pr) }
                }
                if (buffer.size != ops.size) ui { log("⚠ ขนาดไฟล์ ${buffer.size} ≠ ชิป ${ops.size} ไบต์ (${if (buffer.size < ops.size) "เติม FF" else "ตัดส่วนเกิน"})") }
                phase = "เขียน"
                ops.write(fit(buffer, ops.size), pr)
                ui { log("✔ เขียนเสร็จ") }
                if (verifyAfter) { phase = "ตรวจสอบ"; verify(ops, pr) }
            }
        }
    }

    private fun confirm(title: String, msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("ตกลง") { _, _ -> ok() }
            .setNegativeButton("ยกเลิก", null).show()
    }

    // ---------------------------------------------------------------- buffer & files
    private fun fit(b: ByteArray, size: Int): ByteArray =
        if (b.size == size) b else ByteArray(size) { if (it < b.size) b[it] else 0xFF.toByte() }

    private fun crc(d: ByteArray) = CRC32().run { update(d); "%08X".format(value) }

    private fun setBuffer(d: ByteArray, src: String) {
        buffer = d
        bufferSource = src
        hex.data = d
        updateBufInfo()
    }

    private fun updateBufInfo() {
        if (buffer.isEmpty()) { tvBufInfo.text = "บัฟเฟอร์ว่าง"; return }
        var sum = 0L
        for (b in buffer) sum += (b.toInt() and 0xFF)
        tvBufInfo.text = "ขนาด: ${buffer.size} ไบต์ (${fmtSize(buffer.size)})\nCRC32: ${crc(buffer)}  SUM: ${"%04X".format(sum and 0xFFFF)}\nที่มา: $bufferSource"
    }

    private fun suggestName(): String {
        val chip = (currentChip()?.name ?: "eeprom").substringBefore(" ").replace("/", "_")
        val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return "${chip}_$ts.bin"
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "ไฟล์"
        }
        return uri.lastPathSegment ?: "ไฟล์"
    }

    private fun loadFile(uri: Uri) {
        try {
            val d = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("เปิดไฟล์ไม่ได้")
            if (d.size > 64 * 1024 * 1024) throw Exception("ไฟล์ใหญ่เกิน 64MB")
            val name = displayName(uri)
            setBuffer(d, name)
            log("เปิดไฟล์ $name (${d.size} ไบต์)")
            currentChip()?.let { c ->
                val s = if (c.family == Family.SPI25FLASH && c.size == 0) flashSize else c.size
                if (s > 0 && s != d.size) log("⚠ ขนาดไฟล์ไม่เท่ากับชิปที่เลือก (${fmtSize(s)})")
            }
        } catch (e: Exception) {
            log("✖ เปิดไฟล์ไม่ได้: ${e.message}")
        }
    }

    private fun saveFile(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(buffer) } ?: throw Exception("เขียนไฟล์ไม่ได้")
            log("✔ บันทึก ${displayName(uri)} (${buffer.size} ไบต์)")
        } catch (e: Exception) {
            log("✖ บันทึกไม่ได้: ${e.message}")
        }
    }

    private fun gotoOffset() {
        val s = findViewById<EditText>(R.id.etGoto).text.toString().trim().removePrefix("0x").removePrefix("0X")
        val off = s.toIntOrNull(16)
        if (off == null || off < 0 || off >= buffer.size) { toast("offset ไม่ถูกต้อง"); return }
        (rvHex.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(off / bytesPerRow, 0)
    }

    private fun editRow(row: Int) {
        if (row < 0 || job?.isActive == true) return
        val start = row * bytesPerRow
        val end = minOf(start + bytesPerRow, buffer.size)
        if (start >= end) return
        val et = EditText(this)
        et.typeface = android.graphics.Typeface.MONOSPACE
        et.setText((start until end).joinToString(" ") { "%02X".format(buffer[it]) })
        AlertDialog.Builder(this)
            .setTitle("แก้ไข 0x%06X".format(start))
            .setView(et)
            .setPositiveButton("บันทึก") { _, _ ->
                val hexStr = et.text.toString().replace(Regex("[^0-9A-Fa-f]"), "")
                if (hexStr.length != (end - start) * 2) { toast("ต้องใส่ ${end - start} ไบต์ (hex)"); return@setPositiveButton }
                for (i in 0 until end - start) buffer[start + i] = hexStr.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                hex.notifyItemChanged(row)
                bufferSource = if (bufferSource.endsWith("(แก้ไข)")) bufferSource else "$bufferSource (แก้ไข)"
                updateBufInfo()
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }
}
