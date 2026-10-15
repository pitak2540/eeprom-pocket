package com.james.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DOC = "doc"
        const val EXTRA_REPLACE = "replace"
        const val EXTRA_ADD = "add"
        const val EXTRA_AI = "ai"

        /** AI (Google ML Kit) edge detection is the default way to scan. */
        fun aiEnabled(ctx: android.content.Context) = ctx.getSharedPreferences("prefs", 0).getBoolean("use_ai", true)
        const val MAX_SIDE = 3000
    }

    private lateinit var preview: PreviewView
    private lateinit var overlay: QuadOverlayView
    private lateinit var shutter: ImageButton
    private lateinit var busy: ProgressBar
    private lateinit var hint: TextView
    private lateinit var modeSingle: TextView
    private lateinit var modeMulti: TextView
    private lateinit var thumbBox: View
    private lateinit var thumb: ImageView
    private lateinit var count: TextView
    private lateinit var btnDone: TextView
    private lateinit var btnFlash: ImageButton
    private lateinit var btnAuto: ImageButton

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var captureExecutor: ExecutorService

    private var doc: Doc? = null
    private var replaceIndex = -1
    private var addMode = false
    private var multi = false
    private var flashOn = false
    private var autoOn = false
    private var capturing = false
    private var lastAutoShot = 0L
    private var addedThisSession = 0
    private var aiMode = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            Toast.makeText(this, "ต้องอนุญาตกล้องก่อนจึงจะสแกนได้", Toast.LENGTH_LONG).show()
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) importImages(uris, preCropped = false)
    }

    private val aiLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val r = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            val uris = r?.pages?.map { it.imageUri } ?: emptyList()
            if (uris.isNotEmpty()) { importImages(uris, preCropped = true); return@registerForActivityResult }
        }
        if (aiMode) finishScan(cancel = true)
    }

    /** Google ML Kit document scanner: ML edge detection, best for cluttered desks. */
    private fun startAiScan() {
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(if (replaceIndex >= 0) 1 else 50)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(this)
            .addOnSuccessListener { sender -> aiLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e ->
                Toast.makeText(this, "AI สแกนใช้ไม่ได้ในเครื่องนี้ ใช้กล้องของแอพแทน (${e.message})", Toast.LENGTH_LONG).show()
                if (aiMode) { aiMode = false; startOwnCamera() }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        shutter = findViewById(R.id.shutter)
        busy = findViewById(R.id.busy)
        hint = findViewById(R.id.hint)
        modeSingle = findViewById(R.id.modeSingle)
        modeMulti = findViewById(R.id.modeMulti)
        thumbBox = findViewById(R.id.thumbBox)
        thumb = findViewById(R.id.thumb)
        count = findViewById(R.id.count)
        btnDone = findViewById(R.id.btnDone)
        btnFlash = findViewById(R.id.btnFlash)
        btnAuto = findViewById(R.id.btnAuto)
        preview.scaleType = PreviewView.ScaleType.FIT_CENTER

        analysisExecutor = Executors.newSingleThreadExecutor()
        captureExecutor = Executors.newSingleThreadExecutor()

        replaceIndex = intent.getIntExtra(EXTRA_REPLACE, -1)
        addMode = intent.getBooleanExtra(EXTRA_ADD, false)
        intent.getStringExtra(EXTRA_DOC)?.let { doc = DocStore.load(this, it) }
        multi = addMode
        if (replaceIndex >= 0) {
            modeSingle.visibility = View.GONE
            modeMulti.visibility = View.GONE
        }
        autoOn = getSharedPreferences("prefs", 0).getBoolean("auto", true)

        findViewById<View>(R.id.btnClose).setOnClickListener { finishScan(cancel = true) }
        shutter.setOnClickListener { capture() }
        modeSingle.setOnClickListener { multi = false; updateUi() }
        modeMulti.setOnClickListener { multi = true; updateUi() }
        btnDone.setOnClickListener { finishScan(cancel = false) }
        thumbBox.setOnClickListener { finishScan(cancel = false) }
        findViewById<View>(R.id.btnAi).setOnClickListener { startAiScan() }
        findViewById<View>(R.id.btnImport).setOnClickListener { importLauncher.launch("image/*") }
        btnFlash.setOnClickListener {
            flashOn = !flashOn
            imageCapture?.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            camera?.cameraControl?.enableTorch(false)
            updateUi()
        }
        btnAuto.setOnClickListener {
            autoOn = !autoOn
            getSharedPreferences("prefs", 0).edit().putBoolean("auto", autoOn).apply()
            Toast.makeText(this, if (autoOn) "ถ่ายอัตโนมัติ: เปิด" else "ถ่ายอัตโนมัติ: ปิด", Toast.LENGTH_SHORT).show()
            updateUi()
        }
        updateUi()

        aiMode = intent.getBooleanExtra(EXTRA_AI, false)
        if (aiMode && savedInstanceState == null) startAiScan()
        else if (!aiMode) startOwnCamera()
    }

    private fun startOwnCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishScan(cancel = true)
    }

    private fun updateUi() {
        modeSingle.background = if (!multi) ContextCompat.getDrawable(this, R.drawable.pill_sel) else null
        modeMulti.background = if (multi) ContextCompat.getDrawable(this, R.drawable.pill_sel) else null
        btnFlash.setImageResource(if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        btnAuto.alpha = if (autoOn) 1f else 0.4f
        val n = addedThisSession
        thumbBox.visibility = if (multi && n > 0) View.VISIBLE else View.INVISIBLE
        btnDone.visibility = if (multi && n > 0) View.VISIBLE else View.GONE
        count.text = n.toString()
    }

    private fun ensureDoc(): Doc = doc ?: DocStore.create(this).also { doc = it }

    // ------------------------------------------------------------------ camera

    @Suppress("DEPRECATION")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val pv = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
            pv.setSurfaceProvider(preview.surfaceProvider)
            val ic = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .build()
            val an = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            an.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, ic, an)
                imageCapture = ic
            } catch (e: Exception) {
                Toast.makeText(this, "เปิดกล้องไม่ได้: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val rot = proxy.imageInfo.rotationDegrees
            var bmp: Bitmap = proxy.toBitmap()
            proxy.close()
            bmp = Scanner.rotate(bmp, rot)
            val q = Scanner.detect(bmp)
            val w = bmp.width; val h = bmp.height
            bmp.recycle()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                overlay.setQuad(q, w, h)
                val has = overlay.current() != null
                hint.text = when {
                    capturing -> "กำลังถ่าย…"
                    !has -> "วางเอกสารให้อยู่ในกรอบ"
                    autoOn -> "ถือนิ่ง ๆ กำลังจะถ่ายอัตโนมัติ"
                    else -> "พบเอกสารแล้ว กดถ่ายได้เลย"
                }
                if (autoOn && has && !capturing && overlay.stable >= 8 &&
                    System.currentTimeMillis() - lastAutoShot > 2500) {
                    lastAutoShot = System.currentTimeMillis()
                    capture()
                }
            }
        } catch (e: Throwable) {
            try { proxy.close() } catch (_: Throwable) {}
        }
    }

    private fun capture() {
        val ic = imageCapture ?: return
        if (capturing) return
        capturing = true
        busy.visibility = View.VISIBLE
        shutter.isEnabled = false
        val liveQuad = overlay.current()
        findViewById<View>(R.id.flashFx).apply { alpha = 0.8f; animate().alpha(0f).setDuration(250).start() }
        ic.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rot = image.imageInfo.rotationDegrees
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    image.close()
                    var bmp = Scanner.decodeJpeg(bytes, MAX_SIDE)
                    bmp = Scanner.rotate(bmp, rot)
                    val d = ensureDoc()
                    val file = DocStore.newImageFile(this@CameraActivity, d)
                    Scanner.saveJpeg(bmp, file)
                    val quad = Scanner.detect(bmp) ?: liveQuad
                    val small = Scanner.limit(Scanner.warp(bmp, quad), 300)
                    bmp.recycle()
                    runOnUiThread { onCaptured(Page(file.name, quad, DocStore.defaultFilter(this@CameraActivity)), small) }
                } catch (e: Throwable) {
                    runOnUiThread { captureFailed(e.message) }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread { captureFailed(exception.message) }
            }
        })
    }

    private fun captureFailed(msg: String?) {
        capturing = false
        busy.visibility = View.GONE
        shutter.isEnabled = true
        Toast.makeText(this, "ถ่ายไม่สำเร็จ: $msg", Toast.LENGTH_LONG).show()
    }

    private fun onCaptured(page: Page, small: Bitmap?) {
        capturing = false
        busy.visibility = View.GONE
        shutter.isEnabled = true
        val d = ensureDoc()
        if (replaceIndex >= 0 && replaceIndex < d.pages.size) {
            page.filter = d.pages[replaceIndex].filter
            page.savedUri = d.pages[replaceIndex].savedUri  // overwrite the old gallery JPG
            d.pages[replaceIndex] = page
            DocStore.save(this, d)
            setResult(RESULT_OK)
            finish()
            return
        }
        d.pages.add(page)
        DocStore.save(this, d)
        addedThisSession++
        if (!multi) {
            if (addMode) {
                setResult(RESULT_OK); finish()
            } else {
                // single page: auto-cropped, straight to the editor
                startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_DOC, d.id))
                finish()
            }
            return
        }
        if (small != null) thumb.setImageBitmap(small)
        updateUi()
    }

    private fun importImages(uris: List<Uri>, preCropped: Boolean) {
        busy.visibility = View.VISIBLE
        shutter.isEnabled = false
        lifecycleScope.launch {
            val d = ensureDoc()
            val def = DocStore.defaultFilter(this@CameraActivity)
            val pages = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    try {
                        val bmp = Scanner.loadUri(this@CameraActivity, uri, MAX_SIDE)
                        val f = DocStore.newImageFile(this@CameraActivity, d)
                        Scanner.saveJpeg(bmp, f)
                        val q = if (preCropped) null else Scanner.detect(bmp)
                        bmp.recycle()
                        Page(f.name, q, def)
                    } catch (e: Throwable) { null }
                }
            }
            busy.visibility = View.GONE
            shutter.isEnabled = true
            if (pages.isEmpty()) {
                Toast.makeText(this@CameraActivity, "นำเข้าภาพไม่สำเร็จ", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (replaceIndex >= 0 && replaceIndex < d.pages.size) {
                d.pages[replaceIndex] = pages[0].also {
                    it.filter = d.pages[replaceIndex].filter
                    it.savedUri = d.pages[replaceIndex].savedUri
                }
            } else d.pages.addAll(pages)
            DocStore.save(this@CameraActivity, d)
            addedThisSession += pages.size
            finishScan(cancel = false)
        }
    }

    private fun finishScan(cancel: Boolean) {
        val d = doc
        if (addMode || replaceIndex >= 0) {
            setResult(if (addedThisSession > 0) RESULT_OK else RESULT_CANCELED)
            finish(); return
        }
        if (d == null || d.pages.isEmpty()) { d?.let { DocStore.delete(this, it.id) }; finish(); return }
        if (cancel && addedThisSession == 0) { finish(); return }
        startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_DOC, d.id))
        finish()
    }
}
