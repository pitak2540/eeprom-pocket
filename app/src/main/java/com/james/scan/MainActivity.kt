package com.james.scan

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var busy: ProgressBar
    private lateinit var docCount: TextView
    private val adapter = DocAdapter()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        busy.visibility = View.VISIBLE
        lifecycleScope.launch {
            val doc = withContext(Dispatchers.IO) {
                val d = DocStore.create(this@MainActivity)
                val def = DocStore.defaultFilter(this@MainActivity)
                for (u in uris) {
                    try {
                        val bmp = Scanner.loadUri(this@MainActivity, u, CameraActivity.MAX_SIDE)
                        val f = DocStore.newImageFile(this@MainActivity, d)
                        Scanner.saveJpeg(bmp, f)
                        d.pages.add(Page(f.name, Scanner.detect(bmp), def))
                        bmp.recycle()
                    } catch (_: Throwable) { }
                }
                DocStore.save(this@MainActivity, d)
                d
            }
            busy.visibility = View.GONE
            if (doc.pages.isEmpty()) {
                DocStore.delete(this@MainActivity, doc.id)
                Toast.makeText(this@MainActivity, "นำเข้าภาพไม่สำเร็จ", Toast.LENGTH_LONG).show()
            } else openDoc(doc)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        busy = findViewById(R.id.busy)
        docCount = findViewById(R.id.docCount)
        list.layoutManager = GridLayoutManager(this, 2)
        list.adapter = adapter
        findViewById<View>(R.id.btnScan).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java).putExtra(CameraActivity.EXTRA_AI, CameraActivity.aiEnabled(this)))
        }
        findViewById<View>(R.id.btnCamera).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }
        findViewById<View>(R.id.btnImport).setOnClickListener { importLauncher.launch("image/*") }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val docs = withContext(Dispatchers.IO) {
                DocStore.cleanup(this@MainActivity)
                DocStore.list(this@MainActivity)
            }
            adapter.submit(docs)
            empty.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
            docCount.text = if (docs.isEmpty()) "" else "${docs.size} เอกสาร"
        }
    }

    private fun openDoc(d: Doc) {
        startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_DOC, d.id))
    }

    private fun docMenu(d: Doc) {
        val items = arrayOf("เปิด", "เปลี่ยนชื่อ", "บันทึกเป็น PDF ลงเครื่อง", "แชร์ PDF", "ลบ")
        MaterialAlertDialogBuilder(this)
            .setTitle(d.title)
            .setItems(items) { _, w ->
                when (w) {
                    0 -> openDoc(d)
                    1 -> rename(d)
                    2 -> work({ "บันทึก PDF แล้ว: " + Exporter.savePdf(this, d) }) { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                    3 -> work({ Exporter.pdfShareIntent(this, d) }) { startActivity(it) }
                    4 -> MaterialAlertDialogBuilder(this)
                        .setTitle("ลบ \"${d.title}\"?")
                        .setNegativeButton("ยกเลิก", null)
                        .setPositiveButton("ลบ") { _, _ -> DocStore.delete(this, d.id); refresh() }
                        .show()
                }
            }.show()
    }

    private fun <T> work(block: () -> T, done: (T) -> Unit) {
        busy.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.Default) { block() }
                done(r)
            } catch (e: Throwable) {
                Toast.makeText(this@MainActivity, "ไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
            }
            busy.visibility = View.GONE
        }
    }

    private fun rename(d: Doc) {
        val et = EditText(this).apply { setText(d.title); setSelectAllOnFocus(true) }
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
                if (t.isNotEmpty()) { d.title = t; DocStore.save(this, d); refresh() }
            }.show()
    }

    // ------------------------------------------------------------------ list

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    private inner class DocAdapter : RecyclerView.Adapter<Holder>() {
        private var docs: List<Doc> = emptyList()
        private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)

        @Suppress("NotifyDataSetChanged")
        fun submit(d: List<Doc>) { docs = d; notifyDataSetChanged() }

        override fun getItemCount() = docs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_doc, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val d = docs[position]
            h.name.text = d.title
            h.meta.text = "${fmt.format(Date(d.created))} · ${d.pages.size} หน้า"
            h.thumb.setImageDrawable(null)
            h.thumb.tag = d.id
            h.itemView.setOnClickListener { openDoc(d) }
            h.itemView.setOnLongClickListener { docMenu(d); true }
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.Default) {
                    try {
                        val dir = DocStore.dir(this@MainActivity, d.id)
                        val tf = File(dir, "thumb.jpg")
                        if (tf.exists()) BitmapFactory.decodeFile(tf.absolutePath)
                        else Scanner.render(d.pages[0], dir, 500).also { Scanner.saveJpeg(it, tf, 85) }
                    } catch (_: Throwable) { null }
                }
                if (h.thumb.tag == d.id && bmp != null) h.thumb.setImageBitmap(bmp)
            }
        }
    }
}
