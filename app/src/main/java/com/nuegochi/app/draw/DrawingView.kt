package com.nuegochi.app.draw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
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

    private val strokes = mutableListOf<Stroke>()
    private var activeStroke: Stroke? = null
    private var lastX = 0f
    private var lastY = 0f

    private var buffer: Bitmap? = null
    private var bufferCanvas: Canvas? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
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
        if (guide == GuideShape.NONE) return
        val w = width.toFloat()
        val h = height.toFloat()
        when (guide) {
            GuideShape.HEAD -> canvas.drawOval(RectF(w * 0.28f, h * 0.22f, w * 0.72f, h * 0.72f), guidePaint)
            GuideShape.TORSO -> canvas.drawRoundRect(
                RectF(w * 0.3f, h * 0.15f, w * 0.7f, h * 0.85f), w * 0.16f, w * 0.16f, guidePaint
            )
            GuideShape.LIMB -> canvas.drawRoundRect(
                RectF(w * 0.38f, h * 0.1f, w * 0.62f, h * 0.9f), w * 0.1f, w * 0.1f, guidePaint
            )
            GuideShape.TAIL -> {
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.1f)
                    quadTo(w * 0.85f, h * 0.5f, w * 0.55f, h * 0.9f)
                }
                canvas.drawPath(path, guidePaint)
            }
            GuideShape.NONE -> Unit
        }
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
        for (stroke in strokes) {
            strokePaint.color = stroke.color
            strokePaint.strokeWidth = stroke.width
            strokePaint.xfermode = if (stroke.erase) clearPaint else null
            canvas.drawPath(stroke.path, strokePaint)
        }
        strokePaint.xfermode = null
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
        redrawBuffer()
        invalidate()
        onStrokeCountChanged?.invoke(strokes.size)
    }

    fun hasContent(): Boolean = strokes.isNotEmpty()

    /** Returns a copy of the current drawing, transparent where nothing was drawn. */
    fun exportBitmap(): Bitmap? = buffer?.copy(Bitmap.Config.ARGB_8888, false)
}
