package com.nuegochi.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The small floating row of quick-action buttons that pops up when the user taps the pet.
 */
class ActionMenuView(context: Context) : LinearLayout(context) {

    var onFeed: (() -> Unit)? = null
    var onWater: (() -> Unit)? = null
    var onPlay: (() -> Unit)? = null
    var onClean: (() -> Unit)? = null
    var onWash: (() -> Unit)? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28).toFloat()
            setColor(Color.parseColor("#F2FFFFFF"))
        }
        addAction("🍚") { onFeed?.invoke() }
        addAction("💧") { onWater?.invoke() }
        addAction("🎾") { onPlay?.invoke() }
        addAction("🧹") { onClean?.invoke() }
        addAction("🛁") { onWash?.invoke() }
    }

    private fun addAction(emoji: String, action: () -> Unit) {
        val button = TextView(context).apply {
            text = emoji
            textSize = 20f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFF3E4"))
            }
            setOnClickListener { action() }
        }
        val size = dp(48)
        val params = LayoutParams(size, size).apply { marginEnd = dp(4) }
        addView(button, params)
    }
}
