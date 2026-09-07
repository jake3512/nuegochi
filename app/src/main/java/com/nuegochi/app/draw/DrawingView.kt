package com.nuegochi.app.draw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Reference silhouette shown faintly behind the canvas so the user knows roughly where to draw. */
enum class GuideShape { HEAD, TORSO, LIMB, TAIL, NONE }

/**
 * A simple finger-paint canvas used to let the user hand-draw one body part at a time.
 * Every stroke (draw or erase) is kept in order so [undo] can replay history onto a fresh buffer.
 */
class DrawingView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private class Stroke(val path: Path, val color: Int, val width: Float, val erase: Boolean)
    private class Fill(val pattern: PatternPreset, val color: Int)

    private val strokes = mutableListOf<Stroke>()
    private var activeStroke: Stroke? = null
    private var lastX = 0f
    private var lastY = 0f

    /** The optional preset pattern fill sitting behind every freehand stroke. */
    private var baseFill: Fill? = null

    private var buffer: Bitmap? = null
    private var bufferCanvas: Canvas? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clearPaint = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#552B6CB0")
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    var brushColor: Int = Color.BLACK
    var brushWidth: Float = 18f
    var eraseMode: Boolean = false
    var guide: GuideShape = GuideShape.NONE
        set(value) {
            field = value
            invalidate()
        }

    var onStrokeCountChanged: ((Int) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            buffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bufferCanvas = Canvas(buffer!!)
            redrawBuffer()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGuide(canvas)
        buffer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun drawGuide(canvas: Canvas) {
        val path = outlinePath(guide) ?: return
        canvas.drawPath(path, guidePaint)
    }

    /** Thin reference line shown faintly behind the canvas (open curve for the tail). */
    private fun outlinePath(shape: GuideShape): Path? {
        val w = width.toFloat()
        val h = height.toFloat()
        return when (shape) {
            GuideShape.HEAD -> Path().apply { addOval(RectF(w * 0.28f, h * 0.22f, w * 0.72f, h * 0.72f), Path.Direction.CW) }
            GuideShape.TORSO -> Path().apply {
                addRoundRect(RectF(w * 0.3f, h * 0.15f, w * 0.7f, h * 0.85f), w * 0.16f, w * 0.16f, Path.Direction.CW)
            }
            GuideShape.LIMB -> Path().apply {
                addRoundRect(RectF(w * 0.38f, h * 0.1f, w * 0.62f, h * 0.9f), w * 0.1f, w * 0.1f, Path.Direction.CW)
            }
            GuideShape.TAIL -> Path().apply {
                moveTo(w * 0.5f, h * 0.1f)
                quadTo(w * 0.85f, h * 0.5f, w * 0.55f, h * 0.9f)
            }
            GuideShape.NONE -> null
        }
    }

    /** Closed area used to clip a pattern fill - same silhouette as the guide, but the tail gets real width. */
    private fun fillPath(shape: GuideShape): Path? {
        if (shape == GuideShape.TAIL) {
            val w = width.toFloat()
            val h = height.toFloat()
            return Path().apply {
                moveTo(w * 0.48f, h * 0.08f)
                quadTo(w * 0.90f, h * 0.45f, w * 0.58f, h * 0.90f)
                quadTo(w * 0.50f, h * 0.80f, w * 0.46f, h * 0.68f)
                quadTo(w * 0.75f, h * 0.42f, w * 0.42f, h * 0.14f)
                close()
            }
        }
        return outlinePath(shape)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val path = Path().apply { moveTo(x, y) }
                activeStroke = Stroke(path, brushColor, brushWidth, eraseMode).also { strokes.add(it) }
                lastX = x
                lastY = y
                drawSegmentToBuffer(activeStroke!!, x, y, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = activeStroke ?: return true
                val midX = (lastX + x) / 2
                val midY = (lastY + y) / 2
                stroke.path.quadTo(lastX, lastY, midX, midY)
                drawSegmentToBuffer(stroke, lastX, lastY, x, y)
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeStroke = null
                onStrokeCountChanged?.invoke(strokes.size)
            }
        }
        invalidate()
        return true
    }

    private fun drawSegmentToBuffer(stroke: Stroke, x1: Float, y1: Float, x2: Float, y2: Float) {
        val canvas = bufferCanvas ?: return
        strokePaint.color = stroke.color
        strokePaint.strokeWidth = stroke.width
        strokePaint.xfermode = if (stroke.erase) clearPaint else null
        canvas.drawLine(x1, y1, x2, y2, strokePaint)
        strokePaint.xfermode = null
    }

    private fun redrawBuffer() {
        val canvas = bufferCanvas ?: return
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        baseFill?.let { drawFillToBuffer(canvas, it.pattern, it.color) }
        for (stroke in strokes) {
            strokePaint.color = stroke.color
            strokePaint.strokeWidth = stroke.width
            strokePaint.xfermode = if (stroke.erase) clearPaint else null
            canvas.drawPath(stroke.path, strokePaint)
        }
        strokePaint.xfermode = null
    }

    /** Fills the current part's silhouette with a preset pattern, as a base layer under any strokes. */
    fun applyPattern(pattern: PatternPreset, color: Int) {
        baseFill = Fill(pattern, color)
        redrawBuffer()
        invalidate()
        onStrokeCountChanged?.invoke(strokes.size + 1)
    }

    /** Removes the pattern fill, keeping any freehand strokes drawn on top of it. */
    fun clearPattern() {
        if (baseFill == null) return
        baseFill = null
        redrawBuffer()
        invalidate()
        onStrokeCountChanged?.invoke(strokes.size)
    }

    private fun drawFillToBuffer(canvas: Canvas, pattern: PatternPreset, color: Int) {
        val clip = fillPath(guide) ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.save()
        canvas.clipPath(clip)
        when (pattern) {
            PatternPreset.SOLID -> {
                fillPaint.shader = null
                fillPaint.color = color
                canvas.drawRect(0f, 0f, w, h, fillPaint)
            }
            PatternPreset.GRADIENT -> {
                fillPaint.shader = LinearGradient(0f, 0f, 0f, h, shade(color, 1.35f), shade(color, 0.75f), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, fillPaint)
                fillPaint.shader = null
            }
            PatternPreset.STRIPES -> {
                fillPaint.shader = null
                fillPaint.color = color
                canvas.drawRect(0f, 0f, w, h, fillPaint)
                fillPaint.color = shade(color, 0.72f)
                val stripeWidth = w * 0.12f
                var x = -h
                while (x < w) {
                    canvas.drawRect(x, 0f, x + stripeWidth, h, fillPaint)
                    x += stripeWidth * 2.2f
                }
            }
            PatternPreset.DOTS -> {
                fillPaint.shader = null
                fillPaint.color = shade(color, 1.15f)
                canvas.drawRect(0f, 0f, w, h, fillPaint)
                fillPaint.color = shade(color, 0.65f)
                val step = w * 0.16f
                val radius = step * 0.28f
                var row = 0
                var y = step / 2
                while (y < h) {
                    val offset = if (row % 2 == 0) 0f else step / 2
                    var x = offset
                    while (x < w) {
                        canvas.drawCircle(x, y, radius, fillPaint)
                        x += step
                    }
                    y += step
                    row++
                }
            }
        }
        canvas.restore()
    }

    private fun shade(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    fun undo() {
        if (strokes.isEmpty()) return
        strokes.removeAt(strokes.size - 1)
        redrawBuffer()
        invalidate()
        onStrokeCountChanged?.invoke(strokes.size)
    }

    fun clear() {
        strokes.clear()
        baseFill = null
        redrawBuffer()
        invalidate()
        onStrokeCountChanged?.invoke(strokes.size)
    }

    fun hasContent(): Boolean = strokes.isNotEmpty() || baseFill != null

    /** Returns a copy of the current drawing, transparent where nothing was drawn. */
    fun exportBitmap(): Bitmap? = buffer?.copy(Bitmap.Config.ARGB_8888, false)
}
