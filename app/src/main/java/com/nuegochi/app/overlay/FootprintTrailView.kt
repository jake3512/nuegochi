package com.nuegochi.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * A full-screen, non-touchable decorative layer that fades in a trail of little footprints along
 * wherever the pet has been walking - only meant to appear (and only shows up more/darker) the
 * dirtier the pet currently is, so a spotless pet leaves no trace.
 */
class FootprintTrailView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private data class Footprint(val x: Float, val y: Float, val bornAt: Long, val strength: Float, val leftFoot: Boolean)

    private val footprints = mutableListOf<Footprint>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#5A4632")
    }

    private var animating = false
    private val frameRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (footprints.removeAll { now - it.bornAt > LIFETIME_MS }) invalidate()
            if (footprints.isNotEmpty()) invalidate()
            if (animating) postDelayed(this, 60L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animating = true
        post(frameRunnable)
    }

    override fun onDetachedFromWindow() {
        animating = false
        removeCallbacks(frameRunnable)
        super.onDetachedFromWindow()
    }

    /** Drops one footprint at the given screen coordinates. [strength] (0..1) scales size/opacity. */
    fun addFootprint(x: Float, y: Float, strength: Float, leftFoot: Boolean) {
        footprints.add(Footprint(x, y, SystemClock.uptimeMillis(), strength.coerceIn(0f, 1f), leftFoot))
        if (footprints.size > MAX_FOOTPRINTS) footprints.removeAt(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        for (f in footprints) {
            val age = now - f.bornAt
            val fade = (1f - age.toFloat() / LIFETIME_MS).coerceIn(0f, 1f)
            if (fade <= 0f) continue
            paint.alpha = (fade * f.strength * 150).toInt().coerceIn(0, 150)
            val r = 6f + f.strength * 4f
            val sideOffset = if (f.leftFoot) -r * 0.5f else r * 0.5f
            canvas.drawOval(f.x - r + sideOffset, f.y - r * 1.5f, f.x + r + sideOffset, f.y + r * 1.5f, paint)
        }
    }

    private companion object {
        const val LIFETIME_MS = 5000L
        const val MAX_FOOTPRINTS = 80
    }
}
