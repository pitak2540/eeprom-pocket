package com.james.scan

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.min

object Exporter {
    private const val EXPORT_SIDE = 2400
    private const val FOLDER = "JAMES Scan"

    fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "scan" }

    fun writePdf(ctx: Context, doc: Doc, out: OutputStream) {
        val dir = DocStore.dir(ctx, doc.id)
        val pdf = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        doc.pages.forEachIndexed { i, p ->
            val bmp = Scanner.render(p, dir, EXPORT_SIDE)
            // A4 in points, orientation follows the page
            val landscape = bmp.width > bmp.height
            val pw = if (landscape) 842 else 595
            val ph = if (landscape) 595 else 842
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
            val sc = min(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height)
            val w = bmp.width * sc; val h = bmp.height * sc
            val r = RectF((pw - w) / 2f, (ph - h) / 2f, (pw + w) / 2f, (ph + h) / 2f)
            page.canvas.drawColor(android.graphics.Color.WHITE)
            page.canvas.drawBitmap(bmp, null, r, paint)
            pdf.finishPage(page)
            bmp.recycle()
        }
        pdf.writeTo(out)
        pdf.close()
    }

    /** Save PDF into Download/JAMES Scan. Returns a human readable location. */
    fun savePdf(ctx: Context, doc: Doc): String {
        val name = safeName(doc.title) + ".pdf"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("สร้างไฟล์ไม่ได้")
            ctx.contentResolver.openOutputStream(uri)?.use { writePdf(ctx, doc, it) }
            return "Download/$FOLDER/$name"
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
        val f = File(dir, name)
        FileOutputStream(f).use { writePdf(ctx, doc, it) }
        return f.absolutePath
    }

    /** Save every page as JPG into Pictures/JAMES Scan. */
    fun saveJpgs(ctx: Context, doc: Doc): Int {
        val dir = DocStore.dir(ctx, doc.id)
        val base = safeName(doc.title)
        var n = 0
        doc.pages.forEachIndexed { i, p ->
            val bmp = Scanner.render(p, dir, EXPORT_SIDE)
            val name = if (doc.pages.size == 1) "$base.jpg" else "${base}_${i + 1}.jpg"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
                }
                ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    n++
                }
            } else {
                @Suppress("DEPRECATION")
                val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER).apply { mkdirs() }
                FileOutputStream(File(d, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                n++
            }
            bmp.recycle()
        }
        return n
    }

    private fun shareDir(ctx: Context) = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }

    private fun uriFor(ctx: Context, f: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    fun pdfShareIntent(ctx: Context, doc: Doc): Intent {
        val f = File(shareDir(ctx), safeName(doc.title) + ".pdf")
        FileOutputStream(f).use { writePdf(ctx, doc, it) }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uriFor(ctx, f))
            putExtra(Intent.EXTRA_SUBJECT, doc.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "แชร์ PDF")
    }

    fun imagesShareIntent(ctx: Context, doc: Doc): Intent {
        val sd = shareDir(ctx)
        val dir = DocStore.dir(ctx, doc.id)
        val base = safeName(doc.title)
        val uris = ArrayList<Uri>()
        doc.pages.forEachIndexed { i, p ->
            val bmp = Scanner.render(p, dir, EXPORT_SIDE)
            val f = File(sd, "${base}_${i + 1}.jpg")
            Scanner.saveJpeg(bmp, f, 90)
            bmp.recycle()
            uris.add(uriFor(ctx, f))
        }
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uris[0])
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = "image/jpeg"
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "แชร์รูปภาพ")
    }
}
