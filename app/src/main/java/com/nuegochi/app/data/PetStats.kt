package com.nuegochi.app.data

/** Immutable snapshot of everything the UI/overlay need to render the pet's current condition. */
data class PetStats(
    val name: String,
    val stage: PetStage,
    val hunger: Int,
    val thirst: Int,
    val happiness: Int,
    val hygiene: Int,
    val poopCount: Int,
    val growthExp: Int,
    val lastUpdateMillis: Long,
    val endingShown: Boolean
) {
    /** Progress within the current stage, for a growth bar. Cocoon has no "next stage" to bar toward. */
    val expIntoStage: Int
        get() {
            if (stage == PetStage.COCOON) return 1
            val previousThreshold = PetStage.ORDER.getOrNull(stage.ordinal - 1)?.expToNext ?: 0
            return (growthExp - previousThreshold).coerceAtLeast(0)
        }

    val expNeededForStage: Int
        get() {
            if (stage == PetStage.COCOON) return 1
            val previousThreshold = PetStage.ORDER.getOrNull(stage.ordinal - 1)?.expToNext ?: 0
            return (stage.expToNext - previousThreshold).coerceAtLeast(1)
        }

    companion object {
        const val MAX_STAT = 100
        const val MAX_POOP = 5

        fun initial(name: String): PetStats = PetStats(
            name = name,
            stage = PetStage.EGG,
            hunger = 80,
            thirst = 80,
            happiness = 80,
            hygiene = 100,
            poopCount = 0,
            growthExp = 0,
            lastUpdateMillis = System.currentTimeMillis(),
            endingShown = false
        )
    }
}
