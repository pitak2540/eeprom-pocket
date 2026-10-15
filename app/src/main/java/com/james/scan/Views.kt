package com.james.scan

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

private const val TEAL = 0xFF19BC9C.toInt()

/** Live outline drawn on top of the camera preview (preview uses FIT_CENTER). */
class QuadOverlayView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEAL; style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3319BC9C; style = Paint.Style.FILL }
    private var cur: FloatArray? = null
    private var imgW = 3; private var imgH = 4
    private var misses = 0
    var stable = 0; private set

    fun setQuad(q: FloatArray?, w: Int, h: Int) {
        imgW = w; imgH = h
        if (q == null) {
            misses++
            if (misses > 6) { cur = null; stable = 0 }
        } else {
            misses = 0
            val c = cur
            if (c == null) { cur = q.copyOf(); stable = 0 } else {
                var moved = 0f
                for (i in 0 until 8) { moved = maxOf(moved, kotlin.math.abs(q[i] - c[i])); c[i] = c[i] * 0.45f + q[i] * 0.55f }
                stable = if (moved < 0.02f) stable + 1 else 0
            }
        }
        invalidate()
    }

    fun current(): FloatArray? = cur?.copyOf()

    override fun onDraw(canvas: Canvas) {
        val q = cur ?: return
        val sc = min(width.toFloat() / imgW, height.toFloat() / imgH)
        val ox = (width - imgW * sc) / 2f
        val oy = (height - imgH * sc) / 2f
        val p = Path()
        for (i in 0 until 4) {
            val x = ox + q[i * 2] * imgW * sc
            val y = oy + q[i * 2 + 1] * imgH * sc
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        canvas.drawPath(p, fill)
        canvas.drawPath(p, stroke)
    }
}

/** Image with four draggable corners and a magnifier. Quad is normalized tl,tr,br,bl. */
class CropView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private var bmp: Bitmap? = null
    private val q = FloatArray(8)
    private val rect = RectF()
    private var active = -1
    private var lastX = 0f; private var lastY = 0f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEAL; style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val shade = Paint().apply { color = 0x88000000.toInt() }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEAL; style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val magPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val magRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEAL; strokeWidth = 1.5f * d }

    fun setImage(b: Bitmap, quad: FloatArray?) {
        bmp = b
        setQuad(quad)
        magPaint.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        requestLayout(); invalidate()
    }

    fun setQuad(quad: FloatArray?) {
        val src = quad ?: fullQuad()
        System.arraycopy(src, 0, q, 0, 8)
        invalidate()
    }

    fun quad(): FloatArray = q.copyOf()

    companion object {
        fun fullQuad() = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    }

    private fun layoutRect() {
        val b = bmp ?: return
        val pad = 24 * d
        val aw = width - 2 * pad; val ah = height - 2 * pad
        val sc = min(aw / b.width, ah / b.height)
        val w = b.width * sc; val h = b.height * sc
        rect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun px(i: Int) = rect.left + q[i * 2] * rect.width()
    private fun py(i: Int) = rect.top + q[i * 2 + 1] * rect.height()

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        layoutRect()
        canvas.drawBitmap(b, null, rect, bmpPaint)
        val p = Path()
        for (i in 0 until 4) if (i == 0) p.moveTo(px(i), py(i)) else p.lineTo(px(i), py(i))
        p.close()
        // darken outside of the selection
        val outside = Path().apply { addRect(rect, Path.Direction.CW); fillType = Path.FillType.EVEN_ODD; addPath(p) }
        canvas.drawPath(outside, shade)
        canvas.drawPath(p, line)
        val r = 11 * d
        for (i in 0 until 4) {
            canvas.drawCircle(px(i), py(i), r, handleFill)
            canvas.drawCircle(px(i), py(i), r, handleRing)
        }
        if (active >= 0) drawMagnifier(canvas, b)
    }

    private fun drawMagnifier(canvas: Canvas, b: Bitmap) {
        val mr = 56 * d
        val zoom = 2.2f
        val tx = px(active); val ty = py(active)
        // place lens in the opposite top corner so the finger never covers it
        val cx = if (tx < width / 2f) width - mr - 16 * d else mr + 16 * d
        val cy = mr + 16 * d
        val sc = rect.width() / b.width * zoom
        val m = Matrix()
        m.setScale(sc, sc)
        val bx = q[active * 2] * b.width * sc
        val by = q[active * 2 + 1] * b.height * sc
        m.postTranslate(cx - bx, cy - by)
        magPaint.shader.setLocalMatrix(m)
        canvas.drawCircle(cx, cy, mr, magPaint)
        canvas.drawCircle(cx, cy, mr, magRing)
        canvas.drawLine(cx - 12 * d, cy, cx + 12 * d, cy, cross)
        canvas.drawLine(cx, cy - 12 * d, cx, cy + 12 * d, cross)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bmp == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var best = -1; var bd = 48 * d
                for (i in 0 until 4) {
                    val dist = hypot(e.x - px(i), e.y - py(i))
                    if (dist < bd) { bd = dist; best = i }
                }
                active = best
                lastX = e.x; lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return active >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (active < 0) return false
                val dx = (e.x - lastX) / rect.width()
                val dy = (e.y - lastY) / rect.height()
                lastX = e.x; lastY = e.y
                q[active * 2] = (q[active * 2] + dx).coerceIn(0f, 1f)
                q[active * 2 + 1] = (q[active * 2 + 1] + dy).coerceIn(0f, 1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
