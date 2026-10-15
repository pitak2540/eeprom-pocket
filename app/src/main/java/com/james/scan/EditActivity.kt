package com.james.scan

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class EditActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DOC = "doc"
        private const val PREVIEW_SIDE = 1600
    }

    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val baseCache = LruCache<String, Bitmap>(3)

    private lateinit var doc: Doc
    private var index = 0
    private var renderJob: Job? = null
    private var thumbJob: Job? = null
    private var thumbKey: String? = null

    private lateinit var pageView: ImageView
    private lateinit var busy: ProgressBar
    private lateinit var pageNo: TextView
    private lateinit var titleView: TextView
    private lateinit var filterRow: LinearLayout
    private val filterViews = HashMap<String, View>()

    private val reload = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        DocStore.load(this, doc.id)?.let { doc = it }
        if (doc.pages.isEmpty()) { finish(); return@registerForActivityResult }
        index = index.coerceIn(0, doc.pages.size - 1)
        showPage()
        autoSaveJpg(null)
    }
    private val addPages = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val before = doc.pages.size
        DocStore.load(this, doc.id)?.let { doc = it }
        if (doc.pages.size > before) index = before
        showPage()
        autoSaveJpg(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        val d = intent.getStringExtra(EXTRA_DOC)?.let { DocStore.load(this, it) }
        if (d == null || d.pages.isEmpty()) { finish(); return }
        doc = d
        index = savedInstanceState?.getInt("index") ?: 0

        pageView = findViewById(R.id.page)
        busy = findViewById(R.id.busy)
        pageNo = findViewById(R.id.pageNo)
        titleView = findViewById(R.id.title)
        filterRow = findViewById(R.id.filters)

        titleView.text = doc.title
        titleView.setOnClickListener { rename() }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAdd).setOnClickListener {
            addPages.launch(Intent(this, CameraActivity::class.java)
                .putExtra(CameraActivity.EXTRA_DOC, doc.id).putExtra(CameraActivity.EXTRA_ADD, true)
                .putExtra(CameraActivity.EXTRA_AI, CameraActivity.aiEnabled(this)))
        }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { if (index > 0) { index--; showPage() } }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { if (index < doc.pages.size - 1) { index++; showPage() } }
        findViewById<View>(R.id.btnRetake).setOnClickListener {
            reload.launch(Intent(this, CameraActivity::class.java)
                .putExtra(CameraActivity.EXTRA_DOC, doc.id).putExtra(CameraActivity.EXTRA_REPLACE, index)
                .putExtra(CameraActivity.EXTRA_AI, CameraActivity.aiEnabled(this)))
        }
        findViewById<View>(R.id.btnRotate).setOnClickListener {
            val p = doc.pages[index]
            p.rotation = (p.rotation + 270) % 360
            DocStore.save(this, doc)
            showPage()
        }
        findViewById<View>(R.id.btnCrop).setOnClickListener {
            reload.launch(Intent(this, CropActivity::class.java)
                .putExtra(CropActivity.EXTRA_DOC, doc.id).putExtra(CropActivity.EXTRA_INDEX, index))
        }
        findViewById<View>(R.id.btnDelete).setOnClickListener { deletePage() }
        findViewById<View>(R.id.btnOk).setOnClickListener {
            DocStore.save(this, doc)
            autoSaveJpg { showExportMenu(finishAfter = true) }
        }

        buildFilterRow()
        showPage()
        // a fresh scan lands here: save its JPGs right away
        if (savedInstanceState == null) autoSaveJpg(null)
    }

    /** Save new / edited pages to the gallery as JPG (no duplicates), then run [after]. */
    private fun autoSaveJpg(after: (() -> Unit)?) {
        if (doc.pages.all { it.savedUri != null && it.savedKey == it.renderKey() }) { after?.invoke(); return }
        lifecycleScope.launch {
            val n = try {
                withContext(worker) {
                    val written = Exporter.syncJpgs(this@EditActivity, doc)
                    DocStore.save(this@EditActivity, doc)
                    written
                }
            } catch (e: Throwable) {
                Toast.makeText(this@EditActivity, "บันทึก JPG อัตโนมัติไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
                -1
            }
            if (n > 0) Toast.makeText(this@EditActivity, "บันทึก JPG ลงแกลเลอรีแล้ว $n รูป (Pictures/JAMES Scan)", Toast.LENGTH_SHORT).show()
            after?.invoke()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.close()
    }

    // ------------------------------------------------------------------ UI

    private fun buildFilterRow() {
        val inf = LayoutInflater.from(this)
        filterRow.removeAllViews()
        for (f in Filters.ALL) {
            val v = inf.inflate(R.layout.item_filter, filterRow, false) as FrameLayout
            v.findViewById<TextView>(R.id.label).text = Filters.label(f)
            v.setOnClickListener { selectFilter(f) }
            v.setOnLongClickListener {
                doc.pages.forEach { it.filter = f }
                DocStore.save(this, doc)
                DocStore.setDefaultFilter(this, f)
                Toast.makeText(this, "ใช้ \"${Filters.label(f)}\" กับทุกหน้าแล้ว", Toast.LENGTH_SHORT).show()
                showPage()
                true
            }
            filterRow.addView(v)
            filterViews[f] = v
        }
    }

    private fun selectFilter(f: String) {
        val p = doc.pages[index]
        if (p.filter == f) return
        p.filter = f
        DocStore.save(this, doc)
        DocStore.setDefaultFilter(this, f)
        showPage()
    }

    private fun markFilter(f: String) {
        for ((k, v) in filterViews) {
            val on = k == f
            v.findViewById<View>(R.id.sel).visibility = if (on) View.VISIBLE else View.GONE
            v.findViewById<TextView>(R.id.label).setBackgroundColor(if (on) 0xFF19BC9C.toInt() else 0xB3000000.toInt())
        }
    }

    private suspend fun base(page: Page): Bitmap = withContext(worker) {
        val k = page.key()
        baseCache.get(k) ?: run {
            val src = Scanner.loadFile(java.io.File(DocStore.dir(this@EditActivity, doc.id), page.src), PREVIEW_SIDE)
            val w = Scanner.warp(src, page.quad)
            if (w != src) src.recycle()
            // rotate on a copy so the cached one stays valid
            val r = Scanner.rotate(w, page.rotation)
            baseCache.put(k, r)
            r
        }
    }

    private fun showPage() {
        if (doc.pages.isEmpty()) return
        index = index.coerceIn(0, doc.pages.size - 1)
        val page = doc.pages[index]
        pageNo.text = "${index + 1}/${doc.pages.size}"
        titleView.text = doc.title
        markFilter(page.filter)
        renderJob?.cancel()
        busy.visibility = View.VISIBLE
        renderJob = lifecycleScope.launch {
            try {
                val b = base(page)
                val out = withContext(worker) { Scanner.applyFilter(b, page.filter) }
                pageView.setImageBitmap(out)
            } catch (e: Throwable) {
                Toast.makeText(this@EditActivity, "แสดงภาพไม่ได้: ${e.message}", Toast.LENGTH_LONG).show()
            }
            busy.visibility = View.GONE
            updateThumbs(page)
        }
    }

    private fun updateThumbs(page: Page) {
        val k = page.key()
        if (thumbKey == k) return
        thumbKey = k
        thumbJob?.cancel()
        thumbJob = lifecycleScope.launch {
            val small = withContext(worker) { Scanner.limit(base(page).copy(Bitmap.Config.ARGB_8888, false), 220) }
            for (f in Filters.ALL) {
                val t = withContext(worker) { Scanner.applyFilter(small, f) }
                filterViews[f]?.findViewById<ImageView>(R.id.img)?.setImageBitmap(t)
            }
        }
    }

    private fun deletePage() {
        if (doc.pages.size == 1) {
            MaterialAlertDialogBuilder(this)
                .setTitle("ลบเอกสารนี้?")
                .setMessage("มีหน้าเดียว ถ้าลบจะลบทั้งเอกสาร")
                .setNegativeButton("ยกเลิก", null)
                .setPositiveButton("ลบ") { _, _ -> DocStore.delete(this, doc.id); finish() }
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("ลบหน้า ${index + 1}?")
            .setNegativeButton("ยกเลิก", null)
            .setPositiveButton("ลบ") { _, _ ->
                doc.pages.removeAt(index)
                DocStore.save(this, doc)
                thumbKey = null
                showPage()
            }.show()
    }

    private fun rename() {
        val et = EditText(this).apply {
            setText(doc.title); setSelectAllOnFocus(true); inputType = InputType.TYPE_CLASS_TEXT
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0); addView(et)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("ตั้งชื่อเอกสาร")
            .setView(box)
            .setNegativeButton("ยกเลิก", null)
            .setPositiveButton("ตกลง") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) { doc.title = t; DocStore.save(this, doc); titleView.text = t }
            }.show()
    }

    private fun showExportMenu(finishAfter: Boolean) {
        val items = arrayOf("บันทึกเป็น PDF ลงเครื่อง", "แชร์ PDF", "แชร์รูปภาพ", "เสร็จสิ้น")
        MaterialAlertDialogBuilder(this)
            .setTitle("บันทึก JPG แล้ว · ${doc.pages.size} หน้า")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> runExport { "บันทึก PDF แล้ว: " + Exporter.savePdf(this, doc) }
                    1 -> runShare { Exporter.pdfShareIntent(this, doc) }
                    2 -> runShare { Exporter.imagesShareIntent(this, doc) }
                    3 -> if (finishAfter) finish()
                }
            }.show()
    }

    private fun runExport(block: () -> String) {
        busy.visibility = View.VISIBLE
        lifecycleScope.launch {
            val msg = try { withContext(worker) { block() } } catch (e: Throwable) { "บันทึกไม่สำเร็จ: ${e.message}" }
            busy.visibility = View.GONE
            Toast.makeText(this@EditActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun runShare(block: () -> Intent) {
        busy.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val i = withContext(worker) { block() }
                startActivity(i)
            } catch (e: Throwable) {
                Toast.makeText(this@EditActivity, "แชร์ไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
            }
            busy.visibility = View.GONE
        }
    }
}
