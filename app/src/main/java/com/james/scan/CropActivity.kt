package com.james.scan

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CropActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DOC = "doc"
        const val EXTRA_INDEX = "index"
        const val EXTRA_THEN_EDIT = "then_edit"
    }

    private lateinit var crop: CropView
    private lateinit var doc: Doc
    private var index = 0
    private var bmp: Bitmap? = null
    private var autoQuad: FloatArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        crop = findViewById(R.id.crop)
        val busy = findViewById<ProgressBar>(R.id.busy)
        val d = intent.getStringExtra(EXTRA_DOC)?.let { DocStore.load(this, it) }
        index = intent.getIntExtra(EXTRA_INDEX, 0)
        if (d == null || index !in d.pages.indices) { finish(); return }
        doc = d
        val page = d.pages[index]

        findViewById<View>(R.id.btnBack).setOnClickListener { leave(save = false) }
        findViewById<TextView>(R.id.btnFull).setOnClickListener { crop.setQuad(CropView.fullQuad()) }
        findViewById<TextView>(R.id.btnAuto).setOnClickListener {
            val q = autoQuad
            if (q != null) crop.setQuad(q) else Toast.makeText(this, "ตรวจไม่พบขอบเอกสาร ลองลากจุดเอง", Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.btnOk).apply {
            if (!intent.getBooleanExtra(EXTRA_THEN_EDIT, false)) text = "ตกลง"
            setOnClickListener { leave(save = true) }
        }

        lifecycleScope.launch {
            val (b, q) = withContext(Dispatchers.Default) {
                val b = Scanner.loadFile(File(DocStore.dir(this@CropActivity, d.id), page.src), 1600)
                b to Scanner.detect(b)
            }
            bmp = b
            autoQuad = q
            busy.visibility = View.GONE
            crop.setImage(b, page.quad ?: q)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { leave(save = false) }

    private fun leave(save: Boolean) {
        if (save && bmp != null) {
            val q = crop.quad()
            val full = q.contentEquals(CropView.fullQuad())
            doc.pages[index].quad = if (full) null else q
            DocStore.save(this, doc)
            setResult(RESULT_OK)
        }
        if (intent.getBooleanExtra(EXTRA_THEN_EDIT, false)) {
            startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_DOC, doc.id))
        }
        finish()
    }
}
