package com.nuegochi.app.overlay

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nuegochi.app.NuegochiApp
import com.nuegochi.app.R
import com.nuegochi.app.data.PetEffect
import com.nuegochi.app.data.PetRepository
import com.nuegochi.app.data.PetStage
import com.nuegochi.app.data.PetStats
import com.nuegochi.app.render.PetView
import com.nuegochi.app.ui.EndingActivity
import com.nuegochi.app.ui.MainActivity
import kotlin.math.hypot
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the pet drawn in a system-wide overlay window so it is always visible on top of the
 * launcher and every other app, roaming the screen on its own and reacting to taps.
 */
class PetOverlayService : LifecycleService() {

    private lateinit var repository: PetRepository
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private var petContainer: FrameLayout? = null
    private var petView: PetView? = null
    private var petParams: WindowManager.LayoutParams? = null

    private var actionMenu: ActionMenuView? = null
    private var actionMenuParams: WindowManager.LayoutParams? = null

    private var effectView: EffectOverlayView? = null
    private var effectParams: WindowManager.LayoutParams? = null

    private var petSizePx = 0

    // Autonomous wandering state.
    private var currentX = 0f
    private var currentY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var pausedUntil = 0L
    private var downRawX = 0f
    private var downRawY = 0f
    private var endingLaunched = false

    // Drag state: the window follows the finger 1:1 while held and moving, computed directly
    // inside the touch event (not through the async wander loop, which would otherwise fight
    // the gesture); releasing hands control straight back to free autonomous wandering.
    private var isDragging = false
    private var downParamX = 0
    private var downParamY = 0
    private var longPressFired = false
    private val longPressRunnable = Runnable { onLongPress() }

    override fun onCreate() {
        super.onCreate()
        repository = PetRepository.get(this)
        petSizePx = dp(84)

        // Must promote to foreground right away: the service may have been started via
        // startForegroundService(), which requires startForeground() within seconds or the
        // system kills the app. If we lack the overlay permission we still call it, then stop.
        startForegroundNotification()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        addPetOverlay()
        observeState()
        startTickerLoop()
        startWanderLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun startForegroundNotification() {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stats = repository.currentStats()
        val notification = NotificationCompat.Builder(this, NuegochiApp.OVERLAY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title, stats.name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun addPetOverlay() {
        val view = PetView(this).also { petView = it }
        val container = FrameLayout(this).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) }
        petContainer = container

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        currentX = (screenW - petSizePx) / 2f
        currentY = (screenH - petSizePx) / 2f
        targetX = currentX
        targetY = currentY

        val params = WindowManager.LayoutParams(
            petSizePx, petSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = currentX.toInt()
            y = currentY.toInt()
        }
        petParams = params
        windowManager.addView(container, params)

        container.setOnTouchListener { _, event -> handleTouch(event) }
        view.applyAppearance(repository.currentAppearance())
        view.applyStats(repository.currentStats())
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val container = petContainer ?: return false
        val params = petParams ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                longPressFired = false
                downRawX = event.rawX
                downRawY = event.rawY
                downParamX = params.x
                downParamY = params.y
                hideActionMenu()
                container.postDelayed(longPressRunnable, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > dp(12)) {
                    isDragging = true
                    container.removeCallbacks(longPressRunnable)
                    hideActionMenu()
                }
                if (isDragging) {
                    val screenW = resources.displayMetrics.widthPixels
                    val screenH = resources.displayMetrics.heightPixels
                    val newX = (downParamX + dx).coerceIn(0f, (screenW - petSizePx).toFloat())
                    val newY = (downParamY + dy).coerceIn(0f, (screenH - petSizePx).toFloat())
                    params.x = newX.toInt()
                    params.y = newY.toInt()
                    currentX = newX
                    currentY = newY
                    runCatching { windowManager.updateViewLayout(container, params) }
                    petView?.moveDirX = (dx / dp(80)).coerceIn(-1f, 1f)
                    petView?.isWalking = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                container.removeCallbacks(longPressRunnable)
                if (isDragging) {
                    // Hand control straight back to free autonomous wandering from here.
                    isDragging = false
                    petView?.isWalking = false
                    pausedUntil = 0L
                    pickNewTarget()
                } else if (!longPressFired) {
                    onShortTap(event.x, event.y)
                }
            }
        }
        return true
    }

    /** A quick tap: a poke on a poop cleans it, otherwise it's a little one-shot affectionate reaction. */
    private fun onShortTap(x: Float, y: Float) {
        val stats = repository.currentStats()
        if (stats.stage == PetStage.COCOON) {
            openEndingIfNeeded(stats)
            return
        }
        if (stats.stage == PetStage.EGG) return
        if (petView?.isPoopHit(x, y) == true) {
            repository.cleanPoop()
            return
        }
        repository.pet()
    }

    /** Press and hold (without moving) opens the care menu instead of a quick tap reaction. */
    private fun onLongPress() {
        longPressFired = true
        val stats = repository.currentStats()
        if (stats.stage == PetStage.EGG || stats.stage == PetStage.COCOON) return
        showActionMenu()
    }

    private fun showActionMenu() {
        hideActionMenu()
        val params = petParams ?: return
        val menu = ActionMenuView(this).apply {
            onFeed = { repository.feed() }
            onWater = { repository.giveWater() }
            onPlay = { repository.play() }
            onClean = { repository.cleanPoop() }
            onWash = { repository.wash() }
        }
        actionMenu = menu
        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x
            y = (params.y - dp(64)).coerceAtLeast(dp(8))
        }
        actionMenuParams = menuParams
        runCatching { windowManager.addView(menu, menuParams) }
        menu.postDelayed({ if (actionMenu === menu) hideActionMenu() }, 6000)
    }

    private fun hideActionMenu() {
        actionMenu?.let { runCatching { windowManager.removeView(it) } }
        actionMenu = null
        actionMenuParams = null
    }

    private fun playEffect(effect: PetEffect) {
        petView?.playReaction(effect)
        val params = petParams ?: return
        hideEffect()
        val view = EffectOverlayView(this)
        effectView = view
        val size = (petSizePx * 1.6f).toInt()
        val eParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x - (size - petSizePx) / 2
            y = params.y - (size - petSizePx) / 2
        }
        effectParams = eParams
        runCatching { windowManager.addView(view, eParams) }
        view.play(effect) { hideEffect() }
    }

    private fun hideEffect() {
        effectView?.let {
            it.stop()
            runCatching { windowManager.removeView(it) }
        }
        effectView = null
        effectParams = null
    }

    private fun observeState() {
        lifecycleScope.launch {
            repository.statsFlow.collect { stats ->
                petView?.applyStats(stats)
                if (stats.stage == PetStage.COCOON) {
                    openEndingIfNeeded(stats)
                }
            }
        }
        lifecycleScope.launch {
            repository.effects.collect { effect -> playEffect(effect) }
        }
    }

    private fun openEndingIfNeeded(stats: PetStats) {
        if (endingLaunched || stats.endingShown) return
        endingLaunched = true
        repository.markEndingShown()
        val intent = Intent(this, EndingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EndingActivity.EXTRA_PET_NAME, stats.name)
        }
        startActivity(intent)
    }

    private fun startTickerLoop() {
        lifecycleScope.launch {
            while (isActive) {
                delay(30_000)
                repository.tick()
            }
        }
    }

    private fun startWanderLoop() {
        lifecycleScope.launch {
            var lastFrame = SystemClock.elapsedRealtime()
            pickNewTarget()
            while (isActive) {
                delay(16)
                val now = SystemClock.elapsedRealtime()
                val dtSeconds = (now - lastFrame) / 1000f
                lastFrame = now
                stepWander(now, dtSeconds)
            }
        }
    }

    /**
     * Moves the pet one frame closer to its autonomous wander target, using the normal walk
     * animation rather than teleporting. Skipped entirely while the user is actively dragging it
     * (that's handled synchronously in [handleTouch] instead, so the two never fight over
     * [petParams]).
     */
    private fun stepWander(now: Long, dt: Float) {
        if (isDragging) return
        val container = petContainer ?: return
        val params = petParams ?: return
        val stats = repository.currentStats()
        if (!stats.stage.isMoving) {
            petView?.isWalking = false
            return
        }
        if (now < pausedUntil) {
            petView?.isWalking = false
            return
        }
        val dx = targetX - currentX
        val dy = targetY - currentY
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (distance < WANDER_SPEED_PX_PER_SEC * dt || distance < 4f) {
            currentX = targetX
            currentY = targetY
            petView?.isWalking = false
            pausedUntil = now + Random.nextLong(1200, 4000)
            pickNewTarget()
        } else {
            val step = WANDER_SPEED_PX_PER_SEC * dt
            currentX += dx / distance * step
            currentY += dy / distance * step
            petView?.moveDirX = dx / distance
            petView?.isWalking = true
        }
        val newX = currentX.toInt()
        val newY = currentY.toInt()
        if (params.x != newX || params.y != newY) {
            params.x = newX
            params.y = newY
            runCatching { windowManager.updateViewLayout(container, params) }
        }
    }

    private fun pickNewTarget() {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val maxX = (screenW - petSizePx).coerceAtLeast(0)
        val maxY = (screenH - petSizePx).coerceAtLeast(dp(200))
        targetX = Random.nextInt(0, maxX + 1).toFloat()
        targetY = Random.nextInt(dp(80), maxY + 1).toFloat()
    }

    override fun onDestroy() {
        petContainer?.removeCallbacks(longPressRunnable)
        hideActionMenu()
        hideEffect()
        petContainer?.let { runCatching { windowManager.removeView(it) } }
        petContainer = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 42
        private const val WANDER_SPEED_PX_PER_SEC = 90f
        private const val LONG_PRESS_MS = 350L
    }
}
