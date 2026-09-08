package com.nuegochi.app.render

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.nuegochi.app.data.PetAppearance
import com.nuegochi.app.data.PetEffect
import com.nuegochi.app.data.PetStage
import com.nuegochi.app.data.PetStats
import kotlin.math.abs
import kotlin.math.sin

/**
 * Draws the pet as a simple stick figure whose limb lengths and part colors come from
 * [PetAppearance], and renders the special egg / cocoon stages procedurally (a stick figure
 * has nothing to show yet as an egg, and spins itself into a cocoon that hides it completely).
 */
class PetView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private companion object {
        val TAU = (Math.PI * 2).toFloat()
    }

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }

    var stage: PetStage = PetStage.EGG
        set(value) {
            field = value
            invalidate()
        }

    var isWalking: Boolean = false
    /** -1(left)..1(right), the horizontal direction of travel while walking - drives a forward lean. */
    var moveDirX: Float = 0f
    var poopCount: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    /** 0(exhausted)..1(full of energy), from hunger+thirst - slows and shrinks the idle bob. */
    var energyLevel: Float = 1f
    /** 0(starving)..1(full) - drives a slouch/hanging-head posture that grows the emptier it is. */
    var hungerLevel: Float = 1f
    /** 0(parched)..1(hydrated) - drives a panting breath motion that grows the emptier it is. */
    var thirstLevel: Float = 1f
    /** 0(sad)..1(delighted), from happiness - adds a droop when low, little happy hops when high. */
    var moodLevel: Float = 1f
    /** 0(spotless)..1(filthy), from hygiene and uncleaned poop - shiver frequency/strength scale with it. */
    var messyLevel: Float = 0f

    private var appearance: PetAppearance = PetAppearance.default()

    /** Convenience to update everything this view cares about from one status snapshot. */
    fun applyStats(stats: PetStats) {
        stage = stats.stage
        poopCount = stats.poopCount
        hungerLevel = (stats.hunger.toFloat() / PetStats.MAX_STAT).coerceIn(0f, 1f)
        thirstLevel = (stats.thirst.toFloat() / PetStats.MAX_STAT).coerceIn(0f, 1f)
        energyLevel = (hungerLevel + thirstLevel) / 2f
        moodLevel = (stats.happiness.toFloat() / PetStats.MAX_STAT).coerceIn(0f, 1f)
        val hygieneDirt = 1f - (stats.hygiene.toFloat() / PetStats.MAX_STAT).coerceIn(0f, 1f)
        val poopDirt = if (stats.poopCount > 0) 0.4f + 0.12f * stats.poopCount else 0f
        messyLevel = (hygieneDirt.coerceAtLeast(poopDirt)).coerceIn(0f, 1f)
        invalidate()
    }

    fun applyAppearance(newAppearance: PetAppearance) {
        appearance = newAppearance
        invalidate()
    }

    private var reactionScaleX = 1f
    private var reactionScaleY = 1f
    private var reactionRotation = 0f
    private var reactionOffsetY = 0f
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

    /** A short, distinct body reaction the pet plays for each kind of care action. */
    fun playReaction(effect: PetEffect) {
        reactionAnimator?.cancel()
        val duration = when (effect) {
            PetEffect.HATCH, PetEffect.EVOLVE, PetEffect.COCOON -> 600L
            PetEffect.PLAY -> 650L
            PetEffect.PET -> 320L
            else -> 400L
        }
        reactionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                applyReactionCurve(effect, it.animatedValue as Float)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    reactionScaleX = 1f
                    reactionScaleY = 1f
                    reactionRotation = 0f
                    reactionOffsetY = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    /** Maps animation progress [t] (0..1) to a squash/stretch/rotate/hop shape specific to [effect]. */
    private fun applyReactionCurve(effect: PetEffect, t: Float) {
        val hump = sin(t * Math.PI.toFloat())
        when (effect) {
            PetEffect.FEED -> {
                // A quick "chomp": squashes wide and flat, like taking a big bite.
                reactionScaleX = 1f + hump * 0.15f
                reactionScaleY = 1f - hump * 0.15f
                reactionRotation = 0f
                reactionOffsetY = 0f
            }
            PetEffect.WATER -> {
                // Stretches up and tips back slightly, like tilting the head to drink.
                reactionScaleX = 1f - hump * 0.06f
                reactionScaleY = 1f + hump * 0.18f
                reactionRotation = -hump * 6f
                reactionOffsetY = 0f
            }
            PetEffect.PLAY -> {
                // Two quick joyful hops.
                val hop = abs(sin(t * Math.PI.toFloat() * 2f))
                reactionScaleX = 1f
                reactionScaleY = 1f - hop * 0.05f
                reactionRotation = 0f
                reactionOffsetY = -hop * 0.12f
            }
            PetEffect.CLEAN -> {
                // A shake-off wiggle, like shaking away dust.
                reactionRotation = sin(t * Math.PI.toFloat() * 6f) * 8f * (1f - t)
                reactionScaleX = 1f
                reactionScaleY = 1f
                reactionOffsetY = 0f
            }
            PetEffect.WASH -> {
                // A lighter, happier wiggle plus a little refreshed puff.
                reactionRotation = sin(t * Math.PI.toFloat() * 5f) * 5f * (1f - t)
                reactionScaleX = 1f + hump * 0.08f
                reactionScaleY = 1f + hump * 0.08f
                reactionOffsetY = 0f
            }
            PetEffect.PET -> {
                // A small, content wiggle - gentler than the shake-off, meant to repeat while held.
                reactionRotation = sin(t * Math.PI.toFloat() * 2f) * 4f * (1f - t)
                reactionScaleX = 1f + hump * 0.05f
                reactionScaleY = 1f + hump * 0.05f
                reactionOffsetY = 0f
            }
            PetEffect.HATCH, PetEffect.EVOLVE, PetEffect.COCOON -> {
                // A bigger growth pulse for stage transitions.
                reactionScaleX = 1f + hump * 0.25f
                reactionScaleY = 1f + hump * 0.25f
                reactionRotation = 0f
                reactionOffsetY = 0f
            }
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
            else -> drawStickFigureWithMotion(canvas, size, t)
        }
        canvas.restore()

        if (poopCount > 0 && stage.isMoving) {
            drawPoops(canvas, left, top, size)
        }
    }

    /** Egg rocks gently side to side, like something inside is stirring. */
    private fun drawEggWithMotion(canvas: Canvas, size: Float, t: Long) {
        val wobble = sin(t % 1100 / 1100f * TAU).toFloat() * 5f + reactionRotation
        val hop = abs(sin(t % 1100 / 1100f * TAU)).toFloat() * size * 0.02f
        canvas.save()
        canvas.translate(0f, -hop + reactionOffsetY * size)
        canvas.rotate(wobble, size / 2f, size * 0.86f)
        canvas.scale(stage.scale * reactionScaleX, stage.scale * reactionScaleY, size / 2f, size / 2f)
        drawEgg(canvas, size)
        canvas.restore()
    }

    /** Cocoon sways slowly from its top, like it's hanging. */
    private fun drawCocoonWithMotion(canvas: Canvas, size: Float, t: Long) {
        val sway = sin(t % 2600 / 2600f * TAU).toFloat() * 3.5f + reactionRotation
        canvas.save()
        canvas.rotate(sway, size / 2f, size * 0.14f)
        canvas.scale(stage.scale * reactionScaleX, stage.scale * reactionScaleY, size / 2f, size / 2f)
        drawCocoon(canvas, size)
        canvas.restore()
    }

    /**
     * Idle motion for a hatched, still-growing pet. Layered on top of a per-[stage] personality
     * (babies wobble more and settle less; adults are calmer and steadier):
     * - bob speed/height track [energyLevel] (droopy/slow when hungry+thirsty, lively when full)
     * - a slow tilt tracks [moodLevel] (a sad lean when unhappy, a little happy hop when delighted)
     * - [hungerLevel] adds a permanent slouch/sagging posture the emptier it gets
     * - [thirstLevel] adds a panting breath pulse the emptier it gets
     * - [messyLevel] adds a shiver whose frequency and strength scale with how dirty it is
     */
    private fun drawStickFigureWithMotion(canvas: Canvas, size: Float, t: Long) {
        val stageBobMul: Float
        val stageWobbleDeg: Float
        when (stage) {
            PetStage.BABY -> {
                stageBobMul = 1.35f
                stageWobbleDeg = 5f
            }
            PetStage.CHILD -> {
                stageBobMul = 1.15f
                stageWobbleDeg = 2.5f
            }
            PetStage.ADULT -> {
                stageBobMul = 0.8f
                stageWobbleDeg = 0.4f
            }
            else -> { // TEEN and any fallback
                stageBobMul = 1f
                stageWobbleDeg = 1.2f
            }
        }

        val bobPeriod = lerp(2600f, 1200f, energyLevel)
        val bobAmplitude = lerp(0.007f, 0.02f, energyLevel) * size * stageBobMul
        var bob = sin(t % bobPeriod.toLong() / bobPeriod * TAU).toFloat() * bobAmplitude

        if (moodLevel > 0.6f) {
            val hopPhase = (t % 3200L) / 3200f
            if (hopPhase < 0.12f) {
                bob -= sin(hopPhase / 0.12f * Math.PI.toFloat()) * size * 0.03f
            }
        }

        // Extra grounded bounce synced to the footstep swing while roaming.
        if (isWalking) {
            bob -= abs(sin(t % 900 / 900f * TAU)).toFloat() * size * 0.012f
        }

        // Leans into the direction it's currently walking/steering toward.
        val moveLean = if (isWalking) moveDirX.coerceIn(-1f, 1f) * 5f else 0f

        // Slouches lower in its resting pose the hungrier it is.
        bob += (1f - hungerLevel) * size * 0.035f

        val droopDegrees = (1f - moodLevel) * 6f
        val stageWobble = sin(t % 2600 / 2600f * TAU).toFloat() * stageWobbleDeg
        // A constant (non-oscillating) hunched lean that deepens the hungrier it is.
        val hungerHunch = (1f - hungerLevel) * 5f
        var tilt = sin(t % 2000 / 2000f * TAU).toFloat() * droopDegrees + stageWobble + hungerHunch + moveLean + reactionRotation

        var shakeX = 0f
        if (messyLevel > 0.02f) {
            val shakePeriod = lerp(3200f, 1000f, messyLevel)
            val shakePhase = (t % shakePeriod.toLong()) / shakePeriod
            val activeFraction = lerp(0.15f, 0.4f, messyLevel)
            if (shakePhase < activeFraction) {
                val shiverStrength = lerp(0.4f, 1.3f, messyLevel)
                shakeX = sin(shakePhase / activeFraction * TAU * 4f).toFloat() * size * 0.01f * shiverStrength
            }
        }

        // A quick, shallow breathing pulse that gets more pronounced the thirstier it is.
        val pantPulse = sin(t % 380 / 380f * TAU).toFloat() * (1f - thirstLevel) * 0.05f

        canvas.save()
        canvas.translate(shakeX, bob + reactionOffsetY * size)
        canvas.rotate(tilt, size / 2f, size * 0.4f)
        canvas.scale(
            stage.scale * reactionScaleX,
            stage.scale * reactionScaleY * (1f + pantPulse),
            size / 2f, size / 2f
        )
        drawStickFigure(canvas, size, t)
        canvas.restore()
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    /**
     * The stick figure skeleton. Limbs pivot at their attachment point and swing during
     * [isWalking]; their resting length is scaled by the corresponding [appearance] value.
     */
    private fun drawStickFigure(canvas: Canvas, size: Float, t: Long) {
        var legSwing: Float
        var armSwing: Float
        if (isWalking) {
            val walkT = sin(t % 900 / 900f * TAU).toFloat()
            legSwing = walkT * 16f
            armSwing = walkT * 16f
        } else {
            legSwing = 0f
            armSwing = 0f
        }

        // A tired stretch: arms swing wide and hold briefly when energy is very low.
        if (energyLevel < 0.25f) {
            val stretchPeriod = 4000L
            val stretchPhase = (t % stretchPeriod) / stretchPeriod.toFloat()
            if (stretchPhase < 0.3f) {
                armSwing += sin((stretchPhase / 0.3f).coerceIn(0f, 1f) * Math.PI.toFloat()) * 38f
            }
        }
        val limbWidth = size * 0.045f

        val headCx = size * 0.5f
        val headCy = size * 0.19f
        val headRadius = size * 0.11f
        val neckY = size * 0.30f
        val shoulderY = size * 0.36f
        val hipY = size * 0.60f

        // Legs (knee bends more the harder the leg is swinging, for a natural walking/kicking bend).
        val legLen = size * 0.30f * appearance.legLength
        val legBend = 10f + abs(legSwing) * 0.55f
        shapePaint.color = appearance.legColor
        drawLimb(canvas, headCx, hipY, legLen, limbWidth, restAngle = 18f, swingDegrees = legSwing, sign = 1f, jointBend = legBend)
        drawLimb(canvas, headCx, hipY, legLen, limbWidth, restAngle = 18f, swingDegrees = legSwing, sign = -1f, jointBend = legBend)

        // Arms (elbow bends the same way).
        val armLen = size * 0.24f * appearance.armLength
        val armBend = 14f + abs(armSwing) * 0.45f
        shapePaint.color = appearance.armColor
        drawLimb(canvas, headCx, shoulderY, armLen, limbWidth, restAngle = 30f, swingDegrees = armSwing, sign = 1f, jointBend = armBend)
        drawLimb(canvas, headCx, shoulderY, armLen, limbWidth, restAngle = 30f, swingDegrees = armSwing, sign = -1f, jointBend = armBend)

        // Body (spine from neck to hip).
        shapePaint.style = Paint.Style.STROKE
        shapePaint.color = appearance.bodyColor
        shapePaint.strokeWidth = limbWidth
        canvas.drawLine(headCx, neckY, headCx, hipY, shapePaint)

        // Head.
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = appearance.headColor
        canvas.drawCircle(headCx, headCy, headRadius, shapePaint)
        shapePaint.color = Color.parseColor("#2A1E18")
        val eyeOffset = headRadius * 0.4f
        val eyeY = headCy - headRadius * 0.05f
        // A quick periodic blink for a bit of idle life.
        val blinkPhase = t % 2600L
        val eyeRadius = if (blinkPhase < 120L) headRadius * 0.02f else headRadius * 0.11f
        canvas.drawCircle(headCx - eyeOffset, eyeY, eyeRadius, shapePaint)
        canvas.drawCircle(headCx + eyeOffset, eyeY, eyeRadius, shapePaint)
    }

    /**
     * Draws one limb as two jointed segments (upper + lower, like an arm/forearm or thigh/shin)
     * pivoting at ([pivotX], [pivotY]) and leaning outward by [sign]. [jointBend] bends the lower
     * segment relative to the upper one at the elbow/knee, so the limb never looks ramrod-straight.
     */
    private fun drawLimb(
        canvas: Canvas,
        pivotX: Float,
        pivotY: Float,
        length: Float,
        width: Float,
        restAngle: Float,
        swingDegrees: Float,
        sign: Float,
        jointBend: Float
    ) {
        shapePaint.style = Paint.Style.STROKE
        shapePaint.strokeWidth = width
        val upperLen = length * 0.52f
        val lowerLen = length - upperLen
        canvas.save()
        canvas.translate(pivotX, pivotY)
        canvas.rotate((restAngle + swingDegrees) * sign)
        canvas.drawLine(0f, 0f, 0f, upperLen, shapePaint)
        canvas.translate(0f, upperLen)
        canvas.rotate(jointBend * sign)
        canvas.drawLine(0f, 0f, 0f, lowerLen, shapePaint)
        canvas.restore()
    }

    private fun drawEgg(canvas: Canvas, size: Float) {
        shapePaint.style = Paint.Style.FILL
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
    }

    private fun drawCocoon(canvas: Canvas, size: Float) {
        shapePaint.style = Paint.Style.FILL
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
    }

    /** Whether a tap at the view-local ([x], [y]) landed on one of the drawn poop icons. */
    fun isPoopHit(x: Float, y: Float): Boolean {
        if (poopCount <= 0 || !stage.isMoving) return false
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return false
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val count = poopCount.coerceAtMost(5)
        val hitRadius = size * 0.075f
        for (i in 0 until count) {
            val cx = left + size * (0.12f + i * 0.16f)
            val cy = top + size * 0.96f
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= hitRadius * hitRadius) return true
        }
        return false
    }

    private fun drawPoops(canvas: Canvas, left: Float, top: Float, size: Float) {
        shapePaint.style = Paint.Style.FILL
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
