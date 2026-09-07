package com.nuegochi.app.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the pet's persisted state.
 *
 * Stats are tracked internally as [Precise] (fine-grained Doubles) so that decay stays accurate
 * no matter how often the caller reads it - the overlay service may poll this many times a
 * second for smooth animation, and rounding to the public [PetStats] integers on every read
 * would otherwise make decay stall forever. [PetStats] (rounded to whole points, what the UI
 * shows) is derived from [Precise] on every save.
 */
class PetRepository private constructor(context: Context) {

    private data class Precise(
        val name: String,
        val stage: PetStage,
        val hunger: Double,
        val thirst: Double,
        val happiness: Double,
        val hygiene: Double,
        val poopCount: Int,
        val growthExp: Double,
        val lastUpdateMillis: Long,
        val endingShown: Boolean
    ) {
        fun toStats() = PetStats(
            name = name,
            stage = stage,
            hunger = hunger.roundToInt().coerceIn(0, PetStats.MAX_STAT),
            thirst = thirst.roundToInt().coerceIn(0, PetStats.MAX_STAT),
            happiness = happiness.roundToInt().coerceIn(0, PetStats.MAX_STAT),
            hygiene = hygiene.roundToInt().coerceIn(0, PetStats.MAX_STAT),
            poopCount = poopCount,
            growthExp = growthExp.roundToInt(),
            lastUpdateMillis = lastUpdateMillis,
            endingShown = endingShown
        )
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("nuegochi_prefs", Context.MODE_PRIVATE)

    private var precise: Precise = loadOrCreate()

    private val _statsFlow = MutableStateFlow(precise.toStats())
    val statsFlow: StateFlow<PetStats> = _statsFlow.asStateFlow()

    private val _effects = MutableSharedFlow<PetEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<PetEffect> = _effects.asSharedFlow()

    fun hasPet(): Boolean = prefs.contains(KEY_NAME)

    fun currentStats(): PetStats {
        applyDecay()
        return _statsFlow.value
    }

    /** Call periodically (e.g. every 30s from the overlay service) so passive changes show up live. */
    fun tick() {
        applyDecay()
    }

    fun partFile(part: PetPart): File = File(appContext.filesDir, part.fileName)

    fun hasAllParts(): Boolean = PetPart.entries.all { partFile(it).exists() }

    /** Wipes any previous pet's drawn parts and stats. Call once, right before a fresh creation flow starts. */
    fun prepareForNewPetCreation() {
        PetPart.entries.forEach { partFile(it).delete() }
        prefs.edit().clear().apply()
    }

    /** Call after the user has drawn their parts and chosen a name to actually start raising the pet. */
    fun finalizeNewPet(name: String) {
        save(Precise(
            name = name.trim().ifBlank { "누에" },
            stage = PetStage.EGG,
            hunger = 80.0,
            thirst = 80.0,
            happiness = 80.0,
            hygiene = 100.0,
            poopCount = 0,
            growthExp = 0.0,
            lastUpdateMillis = System.currentTimeMillis(),
            endingShown = false
        ))
    }

    fun renamePet(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        applyDecay()
        save(precise.copy(name = trimmed))
    }

    fun feed() = applyAction(PetEffect.FEED) { s ->
        s.copy(
            hunger = (s.hunger + 30).coerceAtMost(100.0),
            growthExp = s.growthExp + if (s.hunger < 100.0) 8 else 2
        )
    }

    fun giveWater() = applyAction(PetEffect.WATER) { s ->
        s.copy(
            thirst = (s.thirst + 30).coerceAtMost(100.0),
            growthExp = s.growthExp + if (s.thirst < 100.0) 6 else 2
        )
    }

    fun play() = applyAction(PetEffect.PLAY) { s ->
        s.copy(
            happiness = (s.happiness + 25).coerceAtMost(100.0),
            hunger = (s.hunger - 5).coerceAtLeast(0.0),
            thirst = (s.thirst - 5).coerceAtLeast(0.0),
            growthExp = s.growthExp + 10
        )
    }

    fun cleanPoop() = applyAction(PetEffect.CLEAN) { s ->
        if (s.poopCount == 0) return@applyAction s
        s.copy(
            poopCount = 0,
            hygiene = (s.hygiene + 10).coerceAtMost(100.0),
            growthExp = s.growthExp + 3
        )
    }

    fun wash() = applyAction(PetEffect.WASH) { s ->
        s.copy(
            hygiene = 100.0,
            happiness = (s.happiness + 5).coerceAtMost(100.0),
            growthExp = s.growthExp + 4
        )
    }

    fun markEndingShown() {
        applyDecay()
        save(precise.copy(endingShown = true))
    }

    fun isOverlayEnabled(): Boolean = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)

    fun setOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
    }

    private fun applyAction(effect: PetEffect, transform: (Precise) -> Precise) {
        applyDecay()
        val before = precise
        if (before.stage == PetStage.EGG || before.stage == PetStage.COCOON) return
        val after = transform(before)
        if (after === before) return
        save(advanceStageIfReady(after))
        _effects.tryEmit(effect)
    }

    private fun advanceStageIfReady(stats: Precise): Precise {
        var current = stats
        var evolved = false
        while (current.stage != PetStage.COCOON && current.growthExp >= current.stage.expToNext) {
            current = current.copy(stage = current.stage.next())
            evolved = true
        }
        if (evolved) {
            _effects.tryEmit(if (current.stage == PetStage.COCOON) PetEffect.COCOON else PetEffect.EVOLVE)
        }
        return current
    }

    /** Applies passive, time-based changes: egg incubation, stat decay, and poop spawning. */
    private fun applyDecay() {
        val stats = precise
        val now = System.currentTimeMillis()
        val elapsedMinutes = (now - stats.lastUpdateMillis) / 60_000.0
        if (elapsedMinutes <= 0.0) return

        if (stats.stage == PetStage.EGG) {
            val grown = stats.copy(growthExp = stats.growthExp + elapsedMinutes, lastUpdateMillis = now)
            val hatched = advanceStageIfReady(grown)
            if (hatched.stage != PetStage.EGG) _effects.tryEmit(PetEffect.HATCH)
            save(hatched)
            return
        }

        if (stats.stage == PetStage.COCOON) {
            save(stats.copy(lastUpdateMillis = now))
            return
        }

        val newHunger = (stats.hunger - elapsedMinutes * HUNGER_DECAY_PER_MIN).coerceAtLeast(0.0)
        val newThirst = (stats.thirst - elapsedMinutes * THIRST_DECAY_PER_MIN).coerceAtLeast(0.0)
        val newHygieneFromTime = (stats.hygiene - elapsedMinutes * HYGIENE_DECAY_PER_MIN).coerceAtLeast(0.0)

        val newPoopCount = (stats.poopCount + (elapsedMinutes / MINUTES_PER_POOP).toInt())
            .coerceAtMost(PetStats.MAX_POOP)
        val newPoops = newPoopCount - stats.poopCount
        val newHygiene = (newHygieneFromTime - newPoops * 5).coerceAtLeast(0.0)

        var happinessPenalty = elapsedMinutes * HAPPINESS_DECAY_PER_MIN
        if (newHunger < 30.0) happinessPenalty += elapsedMinutes * 0.2
        if (newThirst < 30.0) happinessPenalty += elapsedMinutes * 0.2
        if (newHygiene < 30.0) happinessPenalty += elapsedMinutes * 0.2
        val newHappiness = (stats.happiness - happinessPenalty).coerceAtLeast(0.0)

        save(
            stats.copy(
                hunger = newHunger,
                thirst = newThirst,
                happiness = newHappiness,
                hygiene = newHygiene,
                poopCount = newPoopCount,
                lastUpdateMillis = now
            )
        )
    }

    private fun loadOrCreate(): Precise {
        if (!prefs.contains(KEY_NAME)) return Precise(
            name = "누에",
            stage = PetStage.EGG,
            hunger = 80.0,
            thirst = 80.0,
            happiness = 80.0,
            hygiene = 100.0,
            poopCount = 0,
            growthExp = 0.0,
            lastUpdateMillis = System.currentTimeMillis(),
            endingShown = false
        )
        return Precise(
            name = prefs.getString(KEY_NAME, "누에") ?: "누에",
            stage = runCatching { PetStage.valueOf(prefs.getString(KEY_STAGE, PetStage.EGG.name)!!) }
                .getOrDefault(PetStage.EGG),
            hunger = prefs.getFloat(KEY_HUNGER, 80f).toDouble(),
            thirst = prefs.getFloat(KEY_THIRST, 80f).toDouble(),
            happiness = prefs.getFloat(KEY_HAPPINESS, 80f).toDouble(),
            hygiene = prefs.getFloat(KEY_HYGIENE, 100f).toDouble(),
            poopCount = prefs.getInt(KEY_POOP, 0),
            growthExp = prefs.getFloat(KEY_EXP, 0f).toDouble(),
            lastUpdateMillis = prefs.getLong(KEY_LAST_UPDATE, System.currentTimeMillis()),
            endingShown = prefs.getBoolean(KEY_ENDING_SHOWN, false)
        )
    }

    private fun save(stats: Precise) {
        precise = stats
        prefs.edit()
            .putString(KEY_NAME, stats.name)
            .putString(KEY_STAGE, stats.stage.name)
            .putFloat(KEY_HUNGER, stats.hunger.toFloat())
            .putFloat(KEY_THIRST, stats.thirst.toFloat())
            .putFloat(KEY_HAPPINESS, stats.happiness.toFloat())
            .putFloat(KEY_HYGIENE, stats.hygiene.toFloat())
            .putInt(KEY_POOP, stats.poopCount)
            .putFloat(KEY_EXP, stats.growthExp.toFloat())
            .putLong(KEY_LAST_UPDATE, stats.lastUpdateMillis)
            .putBoolean(KEY_ENDING_SHOWN, stats.endingShown)
            .apply()
        _statsFlow.value = stats.toStats()
    }

    companion object {
        private const val KEY_NAME = "pet_name"
        private const val KEY_STAGE = "pet_stage"
        private const val KEY_HUNGER = "pet_hunger"
        private const val KEY_THIRST = "pet_thirst"
        private const val KEY_HAPPINESS = "pet_happiness"
        private const val KEY_HYGIENE = "pet_hygiene"
        private const val KEY_POOP = "pet_poop"
        private const val KEY_EXP = "pet_exp"
        private const val KEY_LAST_UPDATE = "pet_last_update"
        private const val KEY_ENDING_SHOWN = "pet_ending_shown"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"

        private const val HUNGER_DECAY_PER_MIN = 1.0 / 3.0
        private const val THIRST_DECAY_PER_MIN = 1.0 / 4.0
        private const val HYGIENE_DECAY_PER_MIN = 1.0 / 6.0
        private const val HAPPINESS_DECAY_PER_MIN = 1.0 / 5.0
        private const val MINUTES_PER_POOP = 15.0

        @Volatile private var instance: PetRepository? = null

        fun get(context: Context): PetRepository = instance ?: synchronized(this) {
            instance ?: PetRepository(context).also { instance = it }
        }
    }
}
