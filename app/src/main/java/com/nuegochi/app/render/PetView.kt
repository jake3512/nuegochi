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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the pet as a simple stick figure whose limb lengths and part colors come from
 * [PetAppearance], and renders the special egg / cocoon stages procedurally (a stick figure
 * has nothing to show yet as an egg, and spins itself into a cocoon that hides it completely).
 */
class PetView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    companion object {
        private val TAU = (Math.PI * 2).toFloat()
        private const val COLLAPSE_FALL_MS = 300L
        private const val COLLAPSE_HOLD_MS = 900L
        private const val COLLAPSE_RISE_MS = 400L
        const val COLLAPSE_TOTAL_MS = COLLAPSE_FALL_MS + COLLAPSE_HOLD_MS + COLLAPSE_RISE_MS
        const val DIZZY_DURATION_MS = 3000L
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
    /** True while the user is actively dragging the pet around - plays a struggling/flailing motion. */
    var isBeingDragged: Boolean = false
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
    /** Set to the uptime a new poop just appeared, so a brief disgusted flinch can play once. */
    private var poopFlinchStart = -1L
    /** Set to the uptime it bumped into the top/bottom edge, so a one-shot faceplant can play. */
    private var collapseStart = -1L
    /** While uptime is before this, plays a dizzy wobble with stars circling the head. */
    private var dizzyUntil = -1L

    /** Plays a one-shot faceplant: falls prone, holds a beat, then gets back up. */
    fun playCollapse() {
        collapseStart = SystemClock.uptimeMillis()
    }

    /** Plays a wobbly, seeing-stars dizzy spell for a few seconds, e.g. after being shaken. */
    fun playDizzy() {
        dizzyUntil = SystemClock.uptimeMillis() + DIZZY_DURATION_MS
    }

    /** Convenience to update everything this view cares about from one status snapshot. */
    fun applyStats(stats: PetStats) {
        stage = stats.stage
        if (stats.poopCount > poopCount) {
            poopFlinchStart = SystemClock.uptimeMillis()
        }
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
                // A small, content wiggle plus a slight bow down, like leaning into the touch.
                reactionRotation = sin(t * Math.PI.toFloat() * 2f) * 4f * (1f - t)
                reactionScaleX = 1f + hump * 0.05f
                reactionScaleY = 1f + hump * 0.05f
                reactionOffsetY = hump * 0.09f
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
        // Each growth stage gets one extra signature quirk, on top of the general bob/wobble.
        var stageQuirkTilt = 0f
        var stageQuirkShakeX = 0f
        var stageQuirkBob = 0f
        when (stage) {
            PetStage.BABY -> {
                stageBobMul = 1.35f
                stageWobbleDeg = 5f
                // Wobbly toddler balance: a quick off-balance lurch every few seconds.
                val period = 4200L
                val phase = (t % period) / period.toFloat()
                if (phase < 0.18f) {
                    val hump = sin((phase / 0.18f).coerceIn(0f, 1f) * Math.PI.toFloat())
                    val lurchSign = if ((t / period) % 2L == 0L) 1f else -1f
                    stageQuirkShakeX = hump * size * 0.045f * lurchSign
                }
            }
            PetStage.CHILD -> {
                stageBobMul = 1.15f
                stageWobbleDeg = 2.5f
                // Curious peek: holds a sideways lean, like it noticed something.
                val period = 6000L
                val phase = (t % period) / period.toFloat()
                if (phase < 0.35f) {
                    val hump = sin((phase / 0.35f).coerceIn(0f, 1f) * Math.PI.toFloat())
                    stageQuirkTilt = hump * 10f
                }
            }
            PetStage.ADULT -> {
                stageBobMul = 0.8f
                stageWobbleDeg = 0.4f
                // Confident nod: a slow, deliberate small bow.
                val period = 5000L
                val phase = (t % period) / period.toFloat()
                if (phase < 0.25f) {
                    val hump = sin((phase / 0.25f).coerceIn(0f, 1f) * Math.PI.toFloat())
                    stageQuirkTilt = hump * 6f
                    stageQuirkBob = hump * size * 0.015f
                }
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

        // A subtle side-to-side weight shift synced to each footstep, for a more natural walk.
        val walkSway = if (isWalking && !isBeingDragged) {
            sin(t % 900 / 900f * TAU).toFloat() * size * 0.012f
        } else 0f

        // Slouches lower in its resting pose the hungrier it is.
        bob += (1f - hungerLevel) * size * 0.035f

        // A sharp little double-jolt when hunger is critically low (a tummy grumble).
        var grumblePulse = 0f
        if (hungerLevel < 0.15f) {
            val period = 2600L
            val phase = (t % period) / period.toFloat()
            if (phase < 0.2f) {
                grumblePulse = abs(sin((phase / 0.2f) * TAU * 2f)) * 0.05f
            }
        }

        // A slow forward head-hang hold when thirst is critically low.
        var thirstDroopBob = 0f
        var thirstDroopTilt = 0f
        if (thirstLevel < 0.15f) {
            val period = 6000L
            val phase = (t % period) / period.toFloat()
            if (phase < 0.22f) {
                val hump = sin((phase / 0.22f).coerceIn(0f, 1f) * Math.PI.toFloat())
                thirstDroopBob = hump * size * 0.02f
                thirstDroopTilt = hump * 5f
            }
        }

        // A long, slow sigh when happiness is critically low.
        var sighBob = 0f
        if (moodLevel < 0.15f) {
            val period = 5200L
            val phase = (t % period) / period.toFloat()
            if (phase < 0.3f) {
                sighBob = sin((phase / 0.3f).coerceIn(0f, 1f) * Math.PI.toFloat()) * size * 0.018f
            }
        }
        bob += stageQuirkBob + thirstDroopBob + sighBob

        val droopDegrees = (1f - moodLevel) * 6f
        val stageWobble = sin(t % 2600 / 2600f * TAU).toFloat() * stageWobbleDeg
        // A constant (non-oscillating) hunched lean that deepens the hungrier it is.
        val hungerHunch = (1f - hungerLevel) * 5f
        var tilt = sin(t % 2000 / 2000f * TAU).toFloat() * droopDegrees + stageWobble + hungerHunch +
            moveLean + stageQuirkTilt + thirstDroopTilt + reactionRotation

        var shakeX = stageQuirkShakeX + walkSway
        if (messyLevel > 0.02f) {
            val shakePeriod = lerp(3200f, 1000f, messyLevel)
            val shakePhase = (t % shakePeriod.toLong()) / shakePeriod
            val activeFraction = lerp(0.15f, 0.4f, messyLevel)
            if (shakePhase < activeFraction) {
                val shiverStrength = lerp(0.4f, 1.3f, messyLevel)
                shakeX += sin(shakePhase / activeFraction * TAU * 4f).toFloat() * size * 0.01f * shiverStrength
            }
        }
        // On top of the quick shiver, a slower disgusted sway once it's really filthy.
        if (messyLevel > 0.8f) {
            shakeX += sin(t % 1800 / 1800f * TAU).toFloat() * size * 0.012f
        }

        // Extra frantic wobble/squirm layered on top of everything else while being carried.
        if (isBeingDragged) {
            tilt += sin(t % 220 / 220f * TAU).toFloat() * 9f
            shakeX += sin(t % 170 / 170f * TAU).toFloat() * size * 0.02f
        }

        // A quick, shallow breathing pulse that gets more pronounced the thirstier it is.
        val pantPulse = sin(t % 380 / 380f * TAU).toFloat() * (1f - thirstLevel) * 0.05f

        // A one-shot disgusted flinch/squash right when a new poop appears.
        var flinchSquash = 0f
        if (poopFlinchStart >= 0) {
            val elapsed = t - poopFlinchStart
            if (elapsed in 0..420L) {
                val progress = (elapsed / 420f).coerceIn(0f, 1f)
                flinchSquash = sin(progress * Math.PI.toFloat()) * 0.09f
                tilt -= sin(progress * Math.PI.toFloat()) * 9f
            } else {
                poopFlinchStart = -1L
            }
        }

        // A one-shot faceplant after bumping into the top/bottom edge of the screen: falls onto
        // its side, holds a beat, then gets back up.
        var collapseSquash = 0f
        if (collapseStart >= 0) {
            val elapsed = t - collapseStart
            when {
                elapsed < COLLAPSE_FALL_MS -> {
                    val p = elapsed / COLLAPSE_FALL_MS.toFloat()
                    tilt += p * 84f
                    bob += p * size * 0.10f
                    collapseSquash = p * 0.15f
                }
                elapsed < COLLAPSE_FALL_MS + COLLAPSE_HOLD_MS -> {
                    tilt += 84f
                    bob += size * 0.10f
                    collapseSquash = 0.15f
                }
                elapsed < COLLAPSE_TOTAL_MS -> {
                    val p = 1f - (elapsed - COLLAPSE_FALL_MS - COLLAPSE_HOLD_MS) / COLLAPSE_RISE_MS.toFloat()
                    tilt += 84f * p
                    bob += size * 0.10f * p
                    collapseSquash = 0.15f * p
                }
                else -> collapseStart = -1L
            }
        }

        // An exaggerated wobble while dizzy (see the orbiting stars drawn in drawStickFigure).
        if (t < dizzyUntil) {
            tilt += sin(t % 450 / 450f * TAU).toFloat() * 12f
        }

        canvas.save()
        canvas.translate(shakeX, bob + reactionOffsetY * size)
        canvas.rotate(tilt, size / 2f, size * 0.4f)
        canvas.scale(
            stage.scale * reactionScaleX * (1f + flinchSquash * 0.6f + collapseSquash * 0.5f),
            stage.scale * reactionScaleY * (1f + pantPulse - grumblePulse) * (1f - flinchSquash) * (1f - collapseSquash),
            size / 2f, size / 2f
        )
        drawStickFigure(canvas, size, t)
        canvas.restore()
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    /**
     * The stick figure skeleton. Limbs pivot at their attachment point and swing during
     * [isWalking] (or flail out of sync while [isBeingDragged]); their resting length is scaled
     * by the corresponding [appearance] value.
     */
    private fun drawStickFigure(canvas: Canvas, size: Float, t: Long) {
        var legSwingLeft: Float
        var legSwingRight: Float
        var armSwingLeft: Float
        var armSwingRight: Float
        if (isBeingDragged) {
            // A frantic, out-of-sync flail while being picked up and carried around.
            legSwingRight = sin(t % 240 / 240f * TAU).toFloat() * 30f
            legSwingLeft = sin((t + 130) % 260 / 260f * TAU).toFloat() * 30f
            armSwingRight = sin((t + 60) % 220 / 220f * TAU).toFloat() * 36f
            armSwingLeft = sin((t + 170) % 250 / 250f * TAU).toFloat() * 36f
        } else if (isWalking) {
            // A natural alternating gait: the two legs swing opposite each other, and each arm
            // swings with the OPPOSITE-side leg (contralateral coordination, like a real walk).
            val legPhase = t % 900 / 900f * TAU
            legSwingRight = sin(legPhase).toFloat() * 16f
            legSwingLeft = sin(legPhase + Math.PI.toFloat()).toFloat() * 16f
            armSwingRight = sin(legPhase + Math.PI.toFloat()).toFloat() * 14f
            armSwingLeft = sin(legPhase).toFloat() * 14f
        } else {
            legSwingLeft = 0f
            legSwingRight = 0f
            armSwingLeft = 0f
            armSwingRight = 0f
        }

        // A tired stretch: both arms swing wide and hold briefly when energy is very low.
        if (!isBeingDragged && energyLevel < 0.25f) {
            val stretchPeriod = 4000L
            val stretchPhase = (t % stretchPeriod) / stretchPeriod.toFloat()
            if (stretchPhase < 0.3f) {
                val stretch = sin((stretchPhase / 0.3f).coerceIn(0f, 1f) * Math.PI.toFloat()) * 38f
                armSwingLeft += stretch
                armSwingRight += stretch
            }
        }

        // An idle quirk: one arm reaches up to scratch when standing still, fed, and content.
        var scratchBend = 0f
        if (!isBeingDragged && !isWalking && moodLevel > 0.3f && energyLevel > 0.3f) {
            val quirkPeriod = 7000L
            val quirkPhase = (t % quirkPeriod) / quirkPeriod.toFloat()
            if (quirkPhase < 0.12f) {
                val hump = sin((quirkPhase / 0.12f).coerceIn(0f, 1f) * Math.PI.toFloat())
                armSwingRight += hump * 70f
                scratchBend = hump * 50f
            }
        }

        // TEEN-only idle quirk: a nonchalant one-shoulder shrug.
        if (!isBeingDragged && !isWalking && stage == PetStage.TEEN) {
            val shrugPeriod = 5000L
            val shrugPhase = (t % shrugPeriod) / shrugPeriod.toFloat()
            if (shrugPhase < 0.15f) {
                val hump = sin((shrugPhase / 0.15f).coerceIn(0f, 1f) * Math.PI.toFloat())
                armSwingLeft -= hump * 25f
            }
        }

        // Arms hang limper (closer to the body) the sadder it is, instead of a fixed open stance.
        val armRestAngle = lerp(12f, 30f, moodLevel)

        val limbWidth = size * 0.065f

        val headCx = size * 0.5f
        val headCy = size * 0.19f
        val headRadius = size * 0.11f
        val neckY = size * 0.30f
        val shoulderY = size * 0.34f
        val hipY = size * 0.50f

        // Legs (knee bends more the harder the leg is swinging, for a natural walking/kicking bend).
        val legLen = size * 0.22f * appearance.legLength
        val legBendRight = 10f + abs(legSwingRight) * 0.55f
        val legBendLeft = 10f + abs(legSwingLeft) * 0.55f
        shapePaint.color = appearance.legColor
        drawLimb(canvas, headCx, hipY, legLen, limbWidth, restAngle = 18f, swingDegrees = legSwingRight, sign = 1f, jointBend = legBendRight)
        drawLimb(canvas, headCx, hipY, legLen, limbWidth, restAngle = 18f, swingDegrees = legSwingLeft, sign = -1f, jointBend = legBendLeft)

        // Arms (elbow bends more the harder it's swinging, plus extra bend for the scratch quirk).
        val armLen = size * 0.17f * appearance.armLength
        val armBendRight = 14f + abs(armSwingRight) * 0.45f + scratchBend
        val armBendLeft = 14f + abs(armSwingLeft) * 0.45f
        shapePaint.color = appearance.armColor
        drawLimb(canvas, headCx, shoulderY, armLen, limbWidth, restAngle = armRestAngle, swingDegrees = armSwingRight, sign = 1f, jointBend = armBendRight)
        drawLimb(canvas, headCx, shoulderY, armLen, limbWidth, restAngle = armRestAngle, swingDegrees = armSwingLeft, sign = -1f, jointBend = armBendLeft)

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
        // Eyes slowly drift left/right, like it's glancing around.
        val gazeShift = sin(t % 5200 / 5200f * TAU).toFloat() * headRadius * 0.16f
        canvas.drawCircle(headCx - eyeOffset + gazeShift, eyeY, eyeRadius, shapePaint)
        canvas.drawCircle(headCx + eyeOffset + gazeShift, eyeY, eyeRadius, shapePaint)

        // Little stars circling the head while dizzy.
        if (t < dizzyUntil) {
            shapePaint.style = Paint.Style.FILL
            shapePaint.color = Color.parseColor("#F2C94C")
            val orbitRadius = headRadius * 1.4f
            val orbitCy = headCy - headRadius * 1.1f
            for (i in 0 until 3) {
                val angle = (t % 900L) / 900f * TAU + i * (TAU / 3f)
                val sx = headCx + cos(angle) * orbitRadius
                val sy = orbitCy + sin(angle) * orbitRadius * 0.4f
                canvas.drawCircle(sx, sy, headRadius * 0.14f, shapePaint)
            }
        }
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

}
