package com.nuegochi.app.data

import android.graphics.Color

/**
 * The pet's look: a simple stick figure whose limb lengths and part colors the user can
 * customize (arms/legs are drawn symmetric, so there's one color/length per limb pair).
 */
data class PetAppearance(
    val headColor: Int,
    val bodyColor: Int,
    val armColor: Int,
    val legColor: Int,
    val tailColor: Int,
    val armLength: Float,
    val legLength: Float,
    val tailLength: Float
) {
    companion object {
        const val MIN_LIMB_LENGTH = 0.6f
        const val MAX_LIMB_LENGTH = 1.6f

        fun default(): PetAppearance {
            val skin = Color.parseColor("#4A3728")
            return PetAppearance(
                headColor = skin,
                bodyColor = skin,
                armColor = skin,
                legColor = skin,
                tailColor = skin,
                armLength = 1f,
                legLength = 1f,
                tailLength = 1f
            )
        }
    }
}
