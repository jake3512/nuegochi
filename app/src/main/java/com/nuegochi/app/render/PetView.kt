package com.nuegochi.app.render

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.nuegochi.app.data.PetPart
import com.nuegochi.app.data.PetRepository
import com.nuegochi.app.data.PetStage
import com.nuegochi.app.data.PetStats
import kotlin.math.abs
import kotlin.math.sin

/**
 * Composites the individually-drawn body part bitmaps into one paper-doll style character,
 * and renders the special egg / cocoon stages procedurally (there are no user-drawn parts yet
 * for an egg, and none needed any more once the pet has become a cocoon).
 */
class PetView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private data class Slot(val part: PetPart, val rect: RectF, val rotates: Boolean = false)

    private companion object {
        val TAU = (Math.PI * 2).toFloat()
    }

    // Normalized (0..1) placement of every part within the view's square bounds, back-to-front.
    private val blueprint = listOf(
        Slot(PetPart.TAIL, RectF(0.55f, 0.50f, 0.98f, 0.85f)),
        Slot(PetPart.LEG_LEFT, RectF(0.26f, 0.76f, 0.48f, 1.00f), rotates = true),
        Slot(PetPart.LEG_RIGHT, RectF(0.52f, 0.76f, 0.74f, 1.00f), rotates = true),
        Slot(PetPart.BODY, RectF(0.28f, 0.32f, 0.72f, 0.86f)),
        Slot(PetPart.ARM_LEFT, RectF(0.04f, 0.34f, 0.32f, 0.66f), rotates = true),
        Slot(PetPart.ARM_RIGHT, RectF(0.68f, 0.34f, 0.96f, 0.66f), rotates = true),
        Slot(PetPart.HEAD, RectF(0.26f, 0.00f, 0.74f, 0.38f))
    )

    private val partBitmaps = mutableMapOf<PetPart, Bitmap>()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val srcRect = android.graphics.Rect()

    var stage: PetStage = PetStage.EGG
        set(value) {
            field = value
            invalidate()
        }

    var isWalking: Boolean = false
    var poopCount: Int = 0
        set(value) {
            field = value
            invalidate()
        }
    var bodyTint: Int = Color.parseColor("#F4CE9B")

    /** 0(exhausted)..1(full of energy), from hunger+thirst - slows and shrinks the idle bob. */
    var energyLevel: Float = 1f
    /** 0(sad)..1(delighted), from happiness - adds a droop when low, little happy hops when high. */
    var moodLevel: Float = 1f
    /** True when hygiene is low or there's uncleaned poop - plays a periodic disgusted shiver. */
    var isDirty: Boolean = false

    /** Convenience to update everything this view cares about from one status snapshot. */
    fun applyStats(stats: PetStats) {
        stage = stats.stage
        poopCount = stats.poopCount
        energyLevel = ((stats.hunger + stats.thirst) / 2f / PetStats.MAX_STAT).coerceIn(0f, 1f)
        moodLevel = (stats.happiness.toFloat() / PetStats.MAX_STAT).coerceIn(0f, 1f)
        isDirty = stats.hygiene < 40 || stats.poopCount > 0
        invalidate()
    }

    private var reactionScale = 1f
    private var reactionAnimator: ValueAnimator? = null
    private var animating = false
    private val frameRunnable = object : Runnable {
        override fun run() {
            invalidate()
            if (animating) postDelayed(this, 40L)
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
        reactionAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    /** Loads every part image that has been drawn so far from internal storage. */
    fun loadFromRepository(repository: PetRepository) {
        partBitmaps.clear()
        for (part in PetPart.entries) {
            val file = repository.partFile(part)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.let { partBitmaps[part] = it }
            }
        }
        invalidate()
    }

    fun setPart(part: PetPart, bitmap: Bitmap?) {
        if (bitmap == null) partBitmaps.remove(part) else partBitmaps[part] = bitmap
        invalidate()
    }

    /** Short "bounce" the pet plays in reaction to being fed / played with / cleaned, etc. */
    fun playReaction() {
        reactionAnimator?.cancel()
        reactionAnimator = ValueAnimator.ofFloat(1f, 1.22f, 1f).apply {
            duration = 420
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                reactionScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val t = SystemClock.uptimeMillis()

        canvas.save()
        canvas.translate(left, top)
        when (stage) {
            PetStage.EGG -> drawEggWithMotion(canvas, size, t)
            PetStage.COCOON -> drawCocoonWithMotion(canvas, size, t)
            else -> drawComposedPetWithMotion(canvas, size, t)
        }
        canvas.restore()

        if (poopCount > 0 && stage.isMoving) {
            drawPoops(canvas, left, top, size)
        }
    }

    /** Egg rocks gently side to side, like something inside is stirring. */
    private fun drawEggWithMotion(canvas: Canvas, size: Float, t: Long) {
        val wobble = sin(t % 1100 / 1100f * TAU).toFloat() * 5f
        val hop = abs(sin(t % 1100 / 1100f * TAU)).toFloat() * size * 0.02f
        canvas.save()
        canvas.translate(0f, -hop)
        canvas.rotate(wobble, size / 2f, size * 0.86f)
        canvas.scale(stage.scale * reactionScale, stage.scale * reactionScale, size / 2f, size / 2f)
        drawEgg(canvas, size)
        canvas.restore()
    }

    /** Cocoon sways slowly from its top, like it's hanging. */
    private fun drawCocoonWithMotion(canvas: Canvas, size: Float, t: Long) {
        val sway = sin(t % 2600 / 2600f * TAU).toFloat() * 3.5f
        canvas.save()
        canvas.rotate(sway, size / 2f, size * 0.14f)
        canvas.scale(stage.scale * reactionScale, stage.scale * reactionScale, size / 2f, size / 2f)
        drawCocoon(canvas, size)
        canvas.restore()
    }

    /**
     * Idle motion for a hatched, still-growing pet: bob speed/height track [energyLevel] (droopy
     * and slow when hungry/thirsty, lively when well-fed), a slow tilt tracks [moodLevel] (a sad
     * lean when unhappy, a little happy hop when delighted), and [isDirty] adds a periodic shiver.
     */
    private fun drawComposedPetWithMotion(canvas: Canvas, size: Float, t: Long) {
        val bobPeriod = lerp(2600f, 1200f, energyLevel)
        val bobAmplitude = lerp(0.007f, 0.02f, energyLevel) * size
        var bob = sin(t % bobPeriod.toLong() / bobPeriod * TAU).toFloat() * bobAmplitude

        if (moodLevel > 0.6f) {
            val hopPhase = (t % 3200L) / 3200f
            if (hopPhase < 0.12f) {
                bob -= sin(hopPhase / 0.12f * Math.PI.toFloat()) * size * 0.03f
            }
        }

        val droopDegrees = (1f - moodLevel) * 6f
        val tilt = sin(t % 2000 / 2000f * TAU).toFloat() * droopDegrees

        var shakeX = 0f
        if (isDirty) {
            val shakePhase = (t % 2400L) / 2400f
            if (shakePhase < 0.25f) {
                shakeX = sin(shakePhase / 0.25f * TAU * 4f).toFloat() * size * 0.01f
            }
        }

        canvas.save()
        canvas.translate(shakeX, bob)
        canvas.rotate(tilt, size / 2f, size * 0.4f)
        canvas.scale(stage.scale * reactionScale, stage.scale * reactionScale, size / 2f, size / 2f)
        drawComposedPet(canvas, size, t)
        canvas.restore()
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    private fun drawComposedPet(canvas: Canvas, size: Float, t: Long) {
        val walkT = if (isWalking) sin(t % 900 / 900f * TAU).toFloat() else 0f
        for (slot in blueprint) {
            val bmp = partBitmaps[slot.part] ?: continue
            val dest = RectF(
                slot.rect.left * size,
                slot.rect.top * size,
                slot.rect.right * size,
                slot.rect.bottom * size
            )
            canvas.save()
            if (slot.rotates && isWalking) {
                val pivotX = dest.centerX()
                val pivotY = dest.top
                val sign = if (slot.part == PetPart.LEG_RIGHT || slot.part == PetPart.ARM_RIGHT) -1f else 1f
                canvas.rotate(walkT * 14f * sign, pivotX, pivotY)
            }
            drawFitted(canvas, bmp, dest)
            canvas.restore()
        }
        if (partBitmaps.isEmpty()) {
            // Fallback so an unfinished creation still shows *something* instead of a blank view.
            shapePaint.color = bodyTint
            canvas.drawOval(RectF(size * 0.28f, size * 0.28f, size * 0.72f, size * 0.86f), shapePaint)
        }
    }

    private fun drawFitted(canvas: Canvas, bmp: Bitmap, dest: RectF) {
        srcRect.set(0, 0, bmp.width, bmp.height)
        val srcAspect = bmp.width.toFloat() / bmp.height.toFloat()
        val dstAspect = dest.width() / dest.height()
        val fitted = RectF(dest)
        if (srcAspect > dstAspect) {
            val h = dest.width() / srcAspect
            val diff = (dest.height() - h) / 2f
            fitted.top += diff
            fitted.bottom -= diff
        } else {
            val w = dest.height() * srcAspect
            val diff = (dest.width() - w) / 2f
            fitted.left += diff
            fitted.right -= diff
        }
        canvas.drawBitmap(bmp, srcRect, fitted, bitmapPaint)
    }

    private fun drawEgg(canvas: Canvas, size: Float) {
        shapePaint.color = Color.parseColor("#FFF3D6")
        val rect = RectF(size * 0.30f, size * 0.18f, size * 0.70f, size * 0.86f)
        canvas.drawOval(rect, shapePaint)
        shapePaint.color = Color.parseColor("#EBD9A5")
        shapePaint.style = Paint.Style.STROKE
        shapePaint.strokeWidth = size * 0.015f
        val crack = Path().apply {
            moveTo(size * 0.42f, size * 0.35f)
            lineTo(size * 0.50f, size * 0.45f)
            lineTo(size * 0.44f, size * 0.55f)
            lineTo(size * 0.52f, size * 0.62f)
        }
        canvas.drawPath(crack, shapePaint)
        shapePaint.style = Paint.Style.FILL
    }

    private fun drawCocoon(canvas: Canvas, size: Float) {
        shapePaint.color = Color.parseColor("#E9CE9A")
        val rect = RectF(size * 0.32f, size * 0.14f, size * 0.68f, size * 0.90f)
        canvas.drawRoundRect(rect, size * 0.18f, size * 0.18f, shapePaint)
        shapePaint.color = Color.parseColor("#C9A76A")
        shapePaint.style = Paint.Style.STROKE
        shapePaint.strokeWidth = size * 0.012f
        var y = rect.top + size * 0.08f
        while (y < rect.bottom - size * 0.04f) {
            canvas.drawLine(rect.left + size * 0.02f, y, rect.right - size * 0.02f, y, shapePaint)
            y += size * 0.09f
        }
        shapePaint.style = Paint.Style.FILL
    }

    private fun drawPoops(canvas: Canvas, left: Float, top: Float, size: Float) {
        shapePaint.color = Color.parseColor("#8B5E34")
        val count = poopCount.coerceAtMost(5)
        for (i in 0 until count) {
            val cx = left + size * (0.12f + i * 0.16f)
            val cy = top + size * 0.96f
            val r = size * 0.045f
            canvas.drawCircle(cx, cy, r, shapePaint)
            canvas.drawCircle(cx - r * 0.3f, cy - r * 0.9f, r * 0.6f, shapePaint)
        }
    }
}
