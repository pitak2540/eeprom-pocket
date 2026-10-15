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

    /**
     * Candidate quads come from two sources: closed outlines (contours) and
     * combinations of straight lines (Hough). Each candidate is scored by how
     * consistently one side of each edge is brighter/darker than the other
     * (real paper edges are consistent, keyboard keys / text lines are not).
     */
    private fun detectMat(rgba: Mat): FloatArray? {
        val w = rgba.cols().toDouble()
        val h = rgba.rows().toDouble()
        val scale = min(1.0, 400.0 / max(w, h))
        val small = Mat()
        Imgproc.resize(rgba, small, Size(w * scale, h * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        small.release()
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val sw = gray.cols(); val sh = gray.rows()
        val px = ByteArray(sw * sh)
        gray.get(0, 0, px)
        val img = GrayImg(px, sw, sh)
        val area = sw.toDouble() * sh
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))

        val cands = ArrayList<List<Point>>()
        val edges = Mat()
        for ((lo, hi) in listOf(50.0 to 150.0, 25.0 to 80.0)) {
            Imgproc.Canny(gray, edges, lo, hi)
            collectContourQuads(edges, area, cands)
            val dil = Mat()
            Imgproc.dilate(edges, dil, kernel, Point(-1.0, -1.0), 2)
            collectContourQuads(dil, area, cands)
            dil.release()
        }
        val th = Mat()
        Imgproc.threshold(gray, th, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(th, th, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 3)
        collectContourQuads(th, area, cands)
        th.release()

        Imgproc.Canny(gray, edges, 30.0, 90.0)
        collectLineQuads(edges, img, area, cands)
        edges.release(); gray.release(); kernel.release()

        var best: List<Point>? = null
        var bestScore = 0.0
        for (c in cands) {
            val s = score(c, img, area)
            if (s > bestScore) { bestScore = s; best = c }
        }
        val o = best ?: return null
        if (bestScore < 0.06) return null
        val sx = sw.toDouble(); val sy = sh.toDouble()
        return floatArrayOf(
            (o[0].x / sx).toFloat(), (o[0].y / sy).toFloat(),
            (o[1].x / sx).toFloat(), (o[1].y / sy).toFloat(),
            (o[2].x / sx).toFloat(), (o[2].y / sy).toFloat(),
            (o[3].x / sx).toFloat(), (o[3].y / sy).toFloat()
        ).map { it.coerceIn(0f, 1f) }.toFloatArray()
    }

    private class GrayImg(val px: ByteArray, val w: Int, val h: Int) {
        fun at(x: Int, y: Int) = px[y * w + x].toInt() and 0xFF
    }

    private const val OFF = 3.0
    private const val CONTRAST = 10

    /** fraction of samples along a->b where one side is consistently brighter. -1 = mostly outside frame */
    private fun edgeConsistency(a: Point, b: Point, outX: Double, outY: Double, img: GrayImg, fromCentre: Boolean): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1) return 0.0
        var nx = -dy / len; var ny = dx / len
        if (fromCentre) {
            val mx = (a.x + b.x) / 2; val my = (a.y + b.y) / 2
            if ((mx - outX) * nx + (my - outY) * ny < 0) { nx = -nx; ny = -ny }
        }
        val n = (len / 3).toInt() + 2
        var pos = 0; var neg = 0; var cnt = 0
        for (k in 0 until n) {
            val t = if (fromCentre) 0.05 + 0.9 * k / (n - 1) else k.toDouble() / (n - 1)
            val x = a.x + dx * t; val y = a.y + dy * t
            val xo = (x + nx * OFF).toInt(); val yo = (y + ny * OFF).toInt()
            val xi = (x - nx * OFF).toInt(); val yi = (y - ny * OFF).toInt()
            if (xi < 0 || yi < 0 || xi >= img.w || yi >= img.h || xo < 0 || yo < 0 || xo >= img.w || yo >= img.h) continue
            cnt++
            val v = img.at(xi, yi) - img.at(xo, yo)
            if (v > CONTRAST) pos++ else if (v < -CONTRAST) neg++
        }
        if (fromCentre && cnt < n * 0.4) return -1.0
        return max(pos, neg).toDouble() / max(cnt, 1)
    }

    private fun score(q: List<Point>, img: GrayImg, area: Double): Double {
        val a = quadArea(q)
        if (a < area * 0.12 || !sane(q)) return 0.0
        val cx = q.sumOf { it.x } / 4; val cy = q.sumOf { it.y } / 4
        val known = ArrayList<Double>()
        for (i in 0 until 4) {
            val s = edgeConsistency(q[i], q[(i + 1) % 4], cx, cy, img, true)
            if (s >= 0) known.add(s)
        }
        if (known.size < 3) return 0.0
        known.sort()
        if (known[0] < 0.25 || known[1] < 0.5) return 0.0 // allow at most one weak side
        val mean = known.average()
        return mean * mean * mean * mean * Math.pow(a / area, 0.25)
    }

    private fun quadArea(q: List<Point>): Double {
        var s = 0.0
        for (i in 0 until 4) { val p = q[i]; val n = q[(i + 1) % 4]; s += p.x * n.y - n.x * p.y }
        return abs(s) / 2
    }

    private fun collectContourQuads(bin: Mat, imgArea: Double, out: MutableList<List<Point>>) {
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        Imgproc.findContours(bin, contours, hier, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hier.release()
        for (c in contours) {
            val pts = c.toArray()
            val hullIdx = MatOfInt()
            Imgproc.convexHull(c, hullIdx)
            val hullPts = hullIdx.toArray().map { pts[it] }
            hullIdx.release()
            if (hullPts.size < 4) continue
            val hullMat = MatOfPoint(*hullPts.toTypedArray())
            val hullArea = Imgproc.contourArea(hullMat)
            hullMat.release()
            if (hullArea < imgArea * 0.1) continue
            for (src in listOf(pts.toList(), hullPts)) {
                if (src.size < 4) continue
                val m2f = MatOfPoint2f(*src.toTypedArray())
                val peri = Imgproc.arcLength(m2f, true)
                for (eps in listOf(0.015, 0.02, 0.03, 0.04, 0.06, 0.08)) {
                    val approx = MatOfPoint2f()
                    Imgproc.approxPolyDP(m2f, approx, eps * peri, true)
                    val ap = approx.toArray()
                    approx.release()
                    if (ap.size == 4) {
                        val mp = MatOfPoint(*ap)
                        if (Imgproc.isContourConvex(mp)) out.add(order(ap.toList()))
                        mp.release()
                        break
                    }
                    if (ap.size < 4) break
                }
                m2f.release()
            }
        }
        contours.forEach { it.release() }
    }

    private fun collectLineQuads(edges: Mat, img: GrayImg, area: Double, out: MutableList<List<Point>>) {
        val lines = Mat()
        val minLen = min(img.w, img.h) * 0.12
        Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180, 30, minLen, 15.0)
        val hs = ArrayList<Pair<Double, DoubleArray>>()
        val vs = ArrayList<Pair<Double, DoubleArray>>()
        val buf = DoubleArray(4)
        for (r in 0 until lines.rows()) {
            lines.get(r, 0, buf)
            val l = buf.copyOf()
            val ang = (Math.toDegrees(kotlin.math.atan2(l[3] - l[1], l[2] - l[0])) + 180) % 180
            val len = hypot(l[2] - l[0], l[3] - l[1])
            val cons = edgeConsistency(Point(l[0], l[1]), Point(l[2], l[3]), 0.0, 0.0, img, false)
            val rank = len * cons * cons
            if (ang < 30 || ang > 150) hs.add(rank to l) else if (ang > 60 && ang < 120) vs.add(rank to l)
        }
        lines.release()
        val H = dedupe(hs, true); val V = dedupe(vs, false)
        val w = img.w.toDouble(); val h = img.h.toDouble()
        for (i in H.indices) for (j in i + 1 until H.size) for (a in V.indices) for (b in a + 1 until V.size) {
            val p = listOf(
                intersect(H[i], V[a]), intersect(H[i], V[b]),
                intersect(H[j], V[a]), intersect(H[j], V[b])
            )
            if (p.any { it == null }) continue
            val pts = p.filterNotNull()
            if (pts.any { it.x < -w * 0.03 || it.y < -h * 0.03 || it.x > w * 1.03 || it.y > h * 1.03 }) continue
            val o = order(pts).map { Point(it.x.coerceIn(0.0, w - 1), it.y.coerceIn(0.0, h - 1)) }
            if (quadArea(o) < area * 0.15) continue
            out.add(o)
        }
    }

    private fun dedupe(ls: List<Pair<Double, DoubleArray>>, horiz: Boolean): List<DoubleArray> {
        val keys = ArrayList<Double>()
        val res = ArrayList<DoubleArray>()
        for ((_, l) in ls.sortedByDescending { it.first }) {
            val key = if (horiz) (l[1] + l[3]) / 2 else (l[0] + l[2]) / 2
            if (keys.all { abs(it - key) > 6 }) { keys.add(key); res.add(l) }
            if (res.size >= 12) break
        }
        return res
    }

    private fun intersect(l1: DoubleArray, l2: DoubleArray): Point? {
        val (x1, y1, x2, y2) = l1.toList()
        val (x3, y3, x4, y4) = l2.toList()
        val d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(d) < 1e-6) return null
        val a = x1 * y2 - y1 * x2; val b = x3 * y4 - y3 * x4
        return Point((a * (x3 - x4) - (x1 - x2) * b) / d, (a * (y3 - y4) - (y1 - y2) * b) / d)
    }

    /** reject very skewed quads (angles too sharp) */
    private fun sane(p: List<Point>): Boolean {
        for (i in 0 until 4) {
            val a = p[(i + 3) % 4]; val b = p[i]; val c = p[(i + 1) % 4]
            val v1x = a.x - b.x; val v1y = a.y - b.y
            val v2x = c.x - b.x; val v2y = c.y - b.y
            val cos = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y) + 1e-6)
            if (abs(cos) > 0.5) return false // outside 60°..120°
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
