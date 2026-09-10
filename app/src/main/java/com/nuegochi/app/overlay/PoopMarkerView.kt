package com.nuegochi.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A tiny standalone icon left on screen exactly where a poop happened, independent of the pet's
 * own window - it stays behind as the pet wanders off, and is cleaned by long-pressing it directly.
 */
class PoopMarkerView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#8B5E34")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val r = size * 0.32f
        canvas.drawCircle(cx, cy, r, paint)
        canvas.drawCircle(cx - r * 0.3f, cy - r * 0.9f, r * 0.6f, paint)
    }
}
