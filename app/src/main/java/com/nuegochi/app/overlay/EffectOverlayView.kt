package com.nuegochi.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import com.nuegochi.app.data.PetEffect
import kotlin.math.cos
import kotlin.math.sin

/**
 * A short-lived, transparent overlay drawn above the pet that plays a small particle animation
 * for the action that just happened, then removes itself.
 */
class EffectOverlayView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var effect: PetEffect = PetEffect.FEED
    private var animator: ValueAnimator? = null

    fun play(effect: PetEffect, onDone: () -> Unit) {
        this.effect = effect
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = when (effect) {
                PetEffect.EVOLVE, PetEffect.COCOON, PetEffect.HATCH -> 1500L
                PetEffect.PET -> 500L
                else -> 950L
            }
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onDone()
            })
            start()
        }
    }

    fun stop() {
        // Drop listeners before cancelling so this can't re-enter the onDone callback that
        // may itself be in the middle of tearing this view down.
        animator?.removeAllListeners()
        animator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        when (effect) {
            PetEffect.FEED -> drawFalling(canvas, w, h, count = 5, color = Color.parseColor("#E2A33B"))
            PetEffect.WATER -> drawFalling(canvas, w, h, count = 6, color = Color.parseColor("#4FA8E8"))
            PetEffect.PLAY -> drawRising(canvas, w, h, count = 6, color = Color.parseColor("#F06FA0"), heart = true)
            PetEffect.CLEAN -> drawSparkle(canvas, w, h, color = Color.parseColor("#FFD24C"))
            PetEffect.WASH -> drawRising(canvas, w, h, count = 7, color = Color.parseColor("#8FD8F2"), heart = false)
            PetEffect.PET -> drawRising(canvas, w, h, count = 2, color = Color.parseColor("#F49AC1"), heart = true)
            PetEffect.HATCH, PetEffect.EVOLVE, PetEffect.COCOON ->
                drawBurst(canvas, w, h, color = Color.parseColor("#F4C542"))
        }
    }

    private fun alphaFor(localT: Float): Int {
        val t = localT.coerceIn(0f, 1f)
        val a = when {
            t < 0.15f -> t / 0.15f
            t > 0.7f -> (1f - (t - 0.7f) / 0.3f)
            else -> 1f
        }
        return (a.coerceIn(0f, 1f) * 255).toInt()
    }

    private fun drawFalling(canvas: Canvas, w: Float, h: Float, count: Int, color: Int) {
        paint.color = color
        for (i in 0 until count) {
            val phase = (progress + i / count.toFloat()) % 1f
            val x = w * (0.15f + 0.7f * (i / (count - 1f).coerceAtLeast(1f)))
            val y = h * (0.05f + 0.8f * phase)
            paint.alpha = alphaFor(phase)
            canvas.drawCircle(x, y, w * 0.035f, paint)
        }
    }

    private fun drawRising(canvas: Canvas, w: Float, h: Float, count: Int, color: Int, heart: Boolean) {
        paint.color = color
        for (i in 0 until count) {
            val phase = (progress + i / count.toFloat()) % 1f
            val x = w * (0.2f + 0.6f * ((sin(i * 2.1f) + 1f) / 2f))
            val y = h * (0.9f - 0.75f * phase)
            paint.alpha = alphaFor(phase)
            val r = w * (if (heart) 0.05f else 0.03f + 0.02f * ((cos(i * 1.3f) + 1f) / 2f))
            if (heart) {
                drawHeart(canvas, x, y, r)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w * 0.01f
                canvas.drawCircle(x, y, r, paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx - r * 0.5f, cy, r * 0.6f, paint)
        canvas.drawCircle(cx + r * 0.5f, cy, r * 0.6f, paint)
        val path = android.graphics.Path().apply {
            moveTo(cx - r, cy)
            lineTo(cx, cy + r * 1.2f)
            lineTo(cx + r, cy)
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSparkle(canvas: Canvas, w: Float, h: Float, color: Int) {
        paint.color = color
        val count = 8
        for (i in 0 until count) {
            val phase = (progress + i / count.toFloat()) % 1f
            val angle = i * (360f / count) * (Math.PI / 180f)
            val dist = w * 0.28f * phase
            val x = w * 0.5f + cos(angle).toFloat() * dist
            val y = h * 0.85f + sin(angle).toFloat() * dist * 0.5f
            paint.alpha = alphaFor(phase)
            canvas.drawCircle(x, y, w * 0.025f, paint)
        }
    }

    private fun drawBurst(canvas: Canvas, w: Float, h: Float, color: Int) {
        paint.color = color
        val count = 10
        for (i in 0 until count) {
            val angle = i * (360f / count) * (Math.PI / 180f)
            val dist = minOf(w, h) * 0.42f * progress
            val x = w * 0.5f + cos(angle).toFloat() * dist
            val y = h * 0.5f + sin(angle).toFloat() * dist
            paint.alpha = alphaFor(progress)
            canvas.drawCircle(x, y, w * 0.03f, paint)
        }
    }
}
