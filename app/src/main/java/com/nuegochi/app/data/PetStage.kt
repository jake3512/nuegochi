package com.nuegochi.app.data

/**
 * Growth stages of a Nuegochi pet. The name is a pun on 누에고치 (silkworm cocoon):
 * every pet that is raised well eventually spins itself into a [COCOON] as its ending.
 */
enum class PetStage(
    val label: String,
    /** Total accumulated growth exp required to advance OUT of this stage. */
    val expToNext: Int,
    /** Visual scale applied to the composited pet drawing. */
    val scale: Float
) {
    EGG(label = "알", expToNext = 30, scale = 0.55f),
    BABY(label = "아기", expToNext = 120, scale = 0.7f),
    CHILD(label = "유년기", expToNext = 280, scale = 0.85f),
    TEEN(label = "청소년기", expToNext = 500, scale = 1.0f),
    ADULT(label = "성체", expToNext = 800, scale = 1.15f),
    COCOON(label = "번데기", expToNext = Int.MAX_VALUE, scale = 1.0f);

    val isEgg: Boolean get() = this == EGG
    val isCocoon: Boolean get() = this == COCOON
    val isMoving: Boolean get() = this != EGG && this != COCOON

    fun next(): PetStage = ORDER.getOrElse(ordinal + 1) { COCOON }

    companion object {
        val ORDER: List<PetStage> = listOf(EGG, BABY, CHILD, TEEN, ADULT, COCOON)
    }
}
