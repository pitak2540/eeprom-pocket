package com.james.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object Filters {
    const val ORIGINAL = "original"
    const val ENHANCE = "enhance"
    const val MAGIC = "magic"
    const val NOSHADOW = "noshadow"
    const val GRAY = "gray"
    const val BW = "bw"

    val ALL = listOf(ORIGINAL, ENHANCE, MAGIC, NOSHADOW, GRAY, BW)

    fun label(f: String) = when (f) {
        ORIGINAL -> "ต้นฉบับ"
        ENHANCE -> "ปรับปรุง"
        MAGIC -> "เมจิกโปร"
        NOSHADOW -> "ลบเงา"
        GRAY -> "สีเทา"
        BW -> "ขาวดำ"
        else -> f
    }
}

object Scanner {

    // ---------------------------------------------------------------- bitmap IO

    fun loadFile(file: File, maxSide: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val o2 = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(opts.outWidth, opts.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, o2)
            ?: throw IllegalStateException("อ่านรูปไม่ได้")
        return limit(bmp, maxSide)
    }

    fun loadUri(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
        val cr = ctx.contentResolver
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val o2 = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(opts.outWidth, opts.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            ?: throw IllegalStateException("อ่านรูปไม่ได้")
        val deg = try {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) { 0 }
        bmp = rotate(bmp, deg)
        return limit(bmp, maxSide)
    }

    fun decodeJpeg(bytes: ByteArray, maxSide: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val o2 = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(opts.outWidth, opts.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return limit(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2), maxSide)
    }

    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }

    fun limit(bmp: Bitmap, maxSide: Int): Bitmap {
        val m = max(bmp.width, bmp.height)
        if (m <= maxSide) return bmp
        val sc = maxSide.toFloat() / m
        val out = Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true)
        if (out != bmp) bmp.recycle()
        return out
    }

    fun rotate(bmp: Bitmap, deg: Int): Bitmap {
        val d = ((deg % 360) + 360) % 360
        if (d == 0) return bmp
        val m = Matrix().apply { postRotate(d.toFloat()) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out != bmp) bmp.recycle()
        return out
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 92) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    // ---------------------------------------------------------------- full pipeline

    /** Render one page: load source, crop/warp, rotate, apply filter. */
    fun render(page: Page, dir: File, maxSide: Int): Bitmap {
        val src = loadFile(File(dir, page.src), maxSide)
        val warped = warp(src, page.quad)
        if (warped != src) src.recycle()
        val rotated = rotate(warped, page.rotation)
        val out = applyFilter(rotated, page.filter)
        if (out != rotated) rotated.recycle()
        return out
    }

    // ---------------------------------------------------------------- detection

    /** Detect a document quad. Returns 8 normalized floats tl,tr,br,bl (x,y) or null. */
    fun detect(bmp: Bitmap): FloatArray? {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val r = try { detectMat(rgba) } catch (e: Throwable) { null }
        rgba.release()
        return r
    }

    private fun detectMat(rgba: Mat): FloatArray? {
        val w = rgba.cols().toDouble()
        val h = rgba.rows().toDouble()
        val scale = min(1.0, 500.0 / max(w, h))
        val small = Mat()
        Imgproc.resize(rgba, small, Size(w * scale, h * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val sw = small.cols().toDouble()
        val sh = small.rows().toDouble()
        val area = sw * sh
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))

        var found: List<Point>? = null

        // pass 1 + 2: edges with two sensitivities
        for ((lo, hi) in listOf(50.0 to 150.0, 20.0 to 70.0)) {
            val edges = Mat()
            Imgproc.Canny(gray, edges, lo, hi)
            Imgproc.dilate(edges, edges, kernel, Point(-1.0, -1.0), 2)
            found = findQuad(edges, area)
            edges.release()
            if (found != null) break
        }
        // pass 3: Otsu threshold (bright paper on darker desk)
        if (found == null) {
            val th = Mat()
            Imgproc.threshold(gray, th, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(th, th, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 3)
            found = findQuad(th, area)
            th.release()
        }
        small.release(); gray.release(); kernel.release()
        val q = found ?: return null
        val o = order(q)
        return floatArrayOf(
            (o[0].x / sw).toFloat(), (o[0].y / sh).toFloat(),
            (o[1].x / sw).toFloat(), (o[1].y / sh).toFloat(),
            (o[2].x / sw).toFloat(), (o[2].y / sh).toFloat(),
            (o[3].x / sw).toFloat(), (o[3].y / sh).toFloat()
        ).map { it.coerceIn(0f, 1f) }.toFloatArray()
    }

    private fun findQuad(bin: Mat, imgArea: Double): List<Point>? {
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        Imgproc.findContours(bin, contours, hier, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hier.release()
        val sorted = contours.map { it to Imgproc.contourArea(it) }
            .filter { it.second > imgArea * 0.12 }
            .sortedByDescending { it.second }
            .take(8)
        var result: List<Point>? = null
        for ((c, _) in sorted) {
            // convex hull makes it robust against pens / fingers crossing the edge
            val hullIdx = MatOfInt()
            Imgproc.convexHull(c, hullIdx)
            val pts = c.toArray()
            val hullPts = hullIdx.toArray().map { pts[it] }
            hullIdx.release()
            if (hullPts.size < 4) continue
            val hull2f = MatOfPoint2f(*hullPts.toTypedArray())
            val peri = Imgproc.arcLength(hull2f, true)
            for (eps in listOf(0.02, 0.03, 0.04, 0.06, 0.08)) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(hull2f, approx, eps * peri, true)
                val ap = approx.toArray()
                approx.release()
                if (ap.size == 4) {
                    val mp = MatOfPoint(*ap)
                    val a = Imgproc.contourArea(mp)
                    val convex = Imgproc.isContourConvex(mp)
                    mp.release()
                    if (convex && a > imgArea * 0.12 && sane(order(ap.toList()))) {
                        result = ap.toList()
                    }
                    break
                }
                if (ap.size < 4) break
            }
            hull2f.release()
            if (result != null) break
        }
        contours.forEach { it.release() }
        return result
    }

    /** reject very skewed quads (angles too sharp) */
    private fun sane(p: List<Point>): Boolean {
        for (i in 0 until 4) {
            val a = p[(i + 3) % 4]; val b = p[i]; val c = p[(i + 1) % 4]
            val v1x = a.x - b.x; val v1y = a.y - b.y
            val v2x = c.x - b.x; val v2y = c.y - b.y
            val cos = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y) + 1e-6)
            if (abs(cos) > 0.6) return false // outside ~53°..127°
        }
        return true
    }

    /** order as tl, tr, br, bl */
    fun order(p: List<Point>): List<Point> {
        val tl = p.minByOrNull { it.x + it.y }!!
        val br = p.maxByOrNull { it.x + it.y }!!
        val tr = p.maxByOrNull { it.x - it.y }!!
        val bl = p.minByOrNull { it.x - it.y }!!
        return listOf(tl, tr, br, bl)
    }

    // ---------------------------------------------------------------- warp

    fun warp(src: Bitmap, q: FloatArray?): Bitmap {
        if (q == null || q.size != 8) return src
        val W = src.width.toDouble(); val H = src.height.toDouble()
        val p = (0 until 4).map { Point(q[it * 2] * W, q[it * 2 + 1] * H) }
        val wTop = hypot(p[1].x - p[0].x, p[1].y - p[0].y)
        val wBot = hypot(p[2].x - p[3].x, p[2].y - p[3].y)
        val hL = hypot(p[3].x - p[0].x, p[3].y - p[0].y)
        val hR = hypot(p[2].x - p[1].x, p[2].y - p[1].y)
        val outW = max(wTop, wBot).toInt()
        val outH = max(hL, hR).toInt()
        if (outW < 16 || outH < 16) return src
        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)
        val from = MatOfPoint2f(p[0], p[1], p[2], p[3])
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW.toDouble(), 0.0),
            Point(outW.toDouble(), outH.toDouble()), Point(0.0, outH.toDouble())
        )
        val m = Imgproc.getPerspectiveTransform(from, to)
        val dst = Mat()
        Imgproc.warpPerspective(srcMat, dst, m, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, out)
        srcMat.release(); dst.release(); m.release(); from.release(); to.release()
        return out
    }

    // ---------------------------------------------------------------- filters

    fun applyFilter(bmp: Bitmap, filter: String): Bitmap {
        if (filter == Filters.ORIGINAL) return bmp
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        val out: Mat = when (filter) {
            Filters.ENHANCE -> enhance(rgb)
            Filters.MAGIC -> magic(rgb)
            Filters.NOSHADOW -> removeShadow(rgb)
            Filters.GRAY -> gray(rgb)
            Filters.BW -> blackWhite(rgb)
            else -> rgb.clone()
        }
        rgb.release()
        val res = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, res)
        out.release()
        return res
    }

    /** estimate paper background of one 8-bit channel (shadows, lighting) */
    private fun background(ch: Mat): Mat {
        val small = Mat()
        val f = 0.25
        Imgproc.resize(ch, small, Size(max(8.0, ch.cols() * f), max(8.0, ch.rows() * f)), 0.0, 0.0, Imgproc.INTER_AREA)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        Imgproc.dilate(small, small, k)
        Imgproc.medianBlur(small, small, 21)
        val bg = Mat()
        Imgproc.resize(small, bg, ch.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release(); k.release()
        return bg
    }

    private fun divideByBackground(ch: Mat): Mat {
        val bg = background(ch)
        val out = Mat()
        Core.divide(ch, bg, out, 255.0)
        bg.release()
        return out
    }

    private fun removeShadow(rgb: Mat): Mat {
        val chans = ArrayList<Mat>()
        Core.split(rgb, chans)
        val res = chans.map { c -> divideByBackground(c).also { c.release() } }
        val out = Mat()
        Core.merge(res, out)
        res.forEach { it.release() }
        return out
    }

    private fun sharpen(src: Mat, amount: Double, sigma: Double): Mat {
        val blur = Mat()
        Imgproc.GaussianBlur(src, blur, Size(0.0, 0.0), sigma)
        val out = Mat()
        Core.addWeighted(src, 1.0 + amount, blur, -amount, 0.0, out)
        blur.release()
        return out
    }

    private fun magic(rgb: Mat): Mat {
        val ns = removeShadow(rgb)
        // stretch: values <= 60 -> 0, >= 235 -> 255
        val lo = 60.0; val hi = 235.0
        val a = 255.0 / (hi - lo)
        val st = Mat()
        ns.convertTo(st, -1, a, -lo * a)
        ns.release()
        // boost colour so stamps / ink stay vivid
        val hsv = Mat()
        Imgproc.cvtColor(st, hsv, Imgproc.COLOR_RGB2HSV)
        st.release()
        val hc = ArrayList<Mat>()
        Core.split(hsv, hc)
        hc[1].convertTo(hc[1], -1, 1.35, 0.0)
        Core.merge(hc, hsv)
        hc.forEach { it.release() }
        val col = Mat()
        Imgproc.cvtColor(hsv, col, Imgproc.COLOR_HSV2RGB)
        hsv.release()
        val out = sharpen(col, 0.6, 1.2)
        col.release()
        return out
    }

    private fun enhance(rgb: Mat): Mat {
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(ch[0], ch[0])
        ch[0].convertTo(ch[0], -1, 1.08, 12.0)
        Core.merge(ch, lab)
        ch.forEach { it.release() }
        val col = Mat()
        Imgproc.cvtColor(lab, col, Imgproc.COLOR_Lab2RGB)
        lab.release()
        val out = sharpen(col, 0.5, 1.5)
        col.release()
        return out
    }

    private fun gray(rgb: Mat): Mat {
        val g = Mat()
        Imgproc.cvtColor(rgb, g, Imgproc.COLOR_RGB2GRAY)
        val ns = divideByBackground(g)
        g.release()
        val st = Mat()
        ns.convertTo(st, -1, 1.15, -20.0)
        ns.release()
        val out = Mat()
        Imgproc.cvtColor(st, out, Imgproc.COLOR_GRAY2RGB)
        st.release()
        return out
    }

    private fun blackWhite(rgb: Mat): Mat {
        val g = Mat()
        Imgproc.cvtColor(rgb, g, Imgproc.COLOR_RGB2GRAY)
        val ns = divideByBackground(g)
        g.release()
        val block = (max(ns.cols(), ns.rows()) / 60).let { if (it % 2 == 0) it + 1 else it }.coerceIn(15, 61)
        val th = Mat()
        Imgproc.adaptiveThreshold(ns, th, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 12.0)
        ns.release()
        val out = Mat()
        Imgproc.cvtColor(th, out, Imgproc.COLOR_GRAY2RGB)
        th.release()
        return out
    }

    @Suppress("unused")
    private fun type8u3() = CvType.CV_8UC3
}
