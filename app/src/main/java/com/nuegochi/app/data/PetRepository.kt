package com.nuegochi.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

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
        /** Fractional minutes accumulated toward the next poop - not lost between short ticks. */
        val minutesTowardPoop: Double,
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

    /** A snapshot of a pet that finished growing into a cocoon, kept for the storage/exhibit screen. */
    data class CompletedPet(
        val name: String,
        val appearance: PetAppearance,
        val completedAtMillis: Long
    )

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("nuegochi_prefs", Context.MODE_PRIVATE)

    private var precise: Precise = loadOrCreate()

    private val _statsFlow = MutableStateFlow(precise.toStats())
    val statsFlow: StateFlow<PetStats> = _statsFlow.asStateFlow()

    private val _effects = MutableSharedFlow<PetEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<PetEffect> = _effects.asSharedFlow()

    /** Whether the device screen is currently on - growth only advances while this is true. */
    private var screenOn: Boolean = true

    /**
     * Call whenever the screen turns on/off (from a SCREEN_ON/SCREEN_OFF receiver). Flushes decay
     * under the *previous* state first, so the elapsed-time split between screen-on and
     * screen-off growth stays accurate.
     */
    fun setScreenOn(on: Boolean) {
        if (screenOn == on) return
        applyDecay()
        screenOn = on
    }

    fun hasPet(): Boolean = prefs.contains(KEY_NAME)

    fun currentStats(): PetStats {
        applyDecay()
        return _statsFlow.value
    }

    /** Call periodically (e.g. every 30s from the overlay service) so passive changes show up live. */
    fun tick() {
        applyDecay()
    }

    fun currentAppearance(): PetAppearance = PetAppearance(
        headColor = prefs.getInt(KEY_HEAD_COLOR, DEFAULT_APPEARANCE.headColor),
        bodyColor = prefs.getInt(KEY_BODY_COLOR, DEFAULT_APPEARANCE.bodyColor),
        armColor = prefs.getInt(KEY_ARM_COLOR, DEFAULT_APPEARANCE.armColor),
        legColor = prefs.getInt(KEY_LEG_COLOR, DEFAULT_APPEARANCE.legColor),
        armLength = prefs.getFloat(KEY_ARM_LENGTH, DEFAULT_APPEARANCE.armLength),
        legLength = prefs.getFloat(KEY_LEG_LENGTH, DEFAULT_APPEARANCE.legLength)
    )

    fun saveAppearance(appearance: PetAppearance) {
        prefs.edit()
            .putInt(KEY_HEAD_COLOR, appearance.headColor)
            .putInt(KEY_BODY_COLOR, appearance.bodyColor)
            .putInt(KEY_ARM_COLOR, appearance.armColor)
            .putInt(KEY_LEG_COLOR, appearance.legColor)
            .putFloat(KEY_ARM_LENGTH, appearance.armLength)
            .putFloat(KEY_LEG_LENGTH, appearance.legLength)
            .apply()
    }

    /**
     * Wipes any previous pet's appearance and stats. Call once, right before a fresh creation flow
     * starts. Keeps the storage/exhibit archive of already-completed pets intact.
     */
    fun prepareForNewPetCreation() {
        val archive = prefs.getString(KEY_COMPLETED_PETS, null)
        val editor = prefs.edit().clear()
        if (archive != null) editor.putString(KEY_COMPLETED_PETS, archive)
        editor.apply()
    }

    /** Completed pets (finished growing into a cocoon), most recently completed first. */
    fun completedPets(): List<CompletedPet> {
        val array = JSONArray(prefs.getString(KEY_COMPLETED_PETS, "[]"))
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            CompletedPet(
                name = obj.getString("name"),
                appearance = PetAppearance(
                    headColor = obj.getInt("headColor"),
                    bodyColor = obj.getInt("bodyColor"),
                    armColor = obj.getInt("armColor"),
                    legColor = obj.getInt("legColor"),
                    armLength = obj.getDouble("armLength").toFloat(),
                    legLength = obj.getDouble("legLength").toFloat()
                ),
                completedAtMillis = obj.getLong("completedAt")
            )
        }.sortedByDescending { it.completedAtMillis }
    }

    /** Archives the current pet's look into the storage/exhibit list, called once it becomes a cocoon. */
    private fun archiveCompletedPet() {
        val appearance = currentAppearance()
        val entry = JSONObject()
            .put("name", precise.name)
            .put("headColor", appearance.headColor)
            .put("bodyColor", appearance.bodyColor)
            .put("armColor", appearance.armColor)
            .put("legColor", appearance.legColor)
            .put("armLength", appearance.armLength.toDouble())
            .put("legLength", appearance.legLength.toDouble())
            .put("completedAt", System.currentTimeMillis())
        val array = JSONArray(prefs.getString(KEY_COMPLETED_PETS, "[]")).put(entry)
        prefs.edit().putString(KEY_COMPLETED_PETS, array.toString()).apply()
    }

    /** Call after the user has picked an appearance and name to actually start raising the pet. */
    fun finalizeNewPet(name: String) {
        save(Precise(
            name = name.trim().ifBlank { "누에" },
            stage = PetStage.EGG,
            hunger = 80.0,
            thirst = 80.0,
            happiness = 80.0,
            hygiene = 100.0,
            poopCount = 0,
            minutesTowardPoop = 0.0,
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
        s.copy(hunger = (s.hunger + 30).coerceAtMost(100.0))
    }

    fun giveWater() = applyAction(PetEffect.WATER) { s ->
        s.copy(thirst = (s.thirst + 30).coerceAtMost(100.0))
    }

    fun play() = applyAction(PetEffect.PLAY) { s ->
        s.copy(
            happiness = (s.happiness + 25).coerceAtMost(100.0),
            hunger = (s.hunger - 5).coerceAtLeast(0.0),
            thirst = (s.thirst - 5).coerceAtLeast(0.0)
        )
    }

    fun cleanPoop() = applyAction(PetEffect.CLEAN) { s ->
        if (s.poopCount == 0) return@applyAction s
        s.copy(
            poopCount = 0,
            minutesTowardPoop = 0.0,
            hygiene = (s.hygiene + 10).coerceAtMost(100.0)
        )
    }

    /** Removes exactly one poop, e.g. long-pressing directly on the marker it left on screen. */
    fun cleanOnePoop() = applyAction(PetEffect.CLEAN) { s ->
        if (s.poopCount == 0) return@applyAction s
        val newCount = s.poopCount - 1
        s.copy(
            poopCount = newCount,
            minutesTowardPoop = if (newCount == 0) 0.0 else s.minutesTowardPoop,
            hygiene = (s.hygiene + 4).coerceAtMost(100.0)
        )
    }

    fun wash() = applyAction(PetEffect.WASH) { s ->
        s.copy(
            hygiene = 100.0,
            happiness = (s.happiness + 5).coerceAtMost(100.0)
        )
    }

    /** A quick affectionate stroke - a small mood bump with no growth exp, meant to be repeatable. */
    fun pet() = applyAction(PetEffect.PET) { s ->
        s.copy(happiness = (s.happiness + 2).coerceAtMost(100.0))
    }

    fun markEndingShown() {
        applyDecay()
        if (!precise.endingShown) archiveCompletedPet()
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
            val eggGrowthMinutes = if (screenOn) elapsedMinutes else 0.0
            val grown = stats.copy(growthExp = stats.growthExp + eggGrowthMinutes * GROWTH_EXP_PER_MIN, lastUpdateMillis = now)
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

        // Accumulate fractional minutes toward the next poop so short, frequent ticks (e.g. the
        // overlay's 30s ticker) still add up correctly instead of each one truncating to zero.
        var minutesTowardPoop = stats.minutesTowardPoop + elapsedMinutes
        var newPoopCount = stats.poopCount
        while (minutesTowardPoop >= MINUTES_PER_POOP && newPoopCount < PetStats.MAX_POOP) {
            minutesTowardPoop -= MINUTES_PER_POOP
            newPoopCount++
        }
        if (newPoopCount >= PetStats.MAX_POOP) minutesTowardPoop = 0.0
        val newPoops = newPoopCount - stats.poopCount
        val newHygiene = (newHygieneFromTime - newPoops * 5).coerceAtLeast(0.0)

        var happinessPenalty = elapsedMinutes * HAPPINESS_DECAY_PER_MIN
        if (newHunger < 30.0) happinessPenalty += elapsedMinutes * 0.2
        if (newThirst < 30.0) happinessPenalty += elapsedMinutes * 0.2
        if (newHygiene < 30.0) happinessPenalty += elapsedMinutes * 0.2
        val newHappiness = (stats.happiness - happinessPenalty).coerceAtLeast(0.0)

        // Growth advances purely with elapsed time (care actions only affect the four stats
        // above), but only while the screen is on - locking the phone pauses growth, while the
        // four stats above and poop spawning keep progressing in the background either way.
        val growthMinutes = if (screenOn) elapsedMinutes else 0.0
        val newGrowthExp = stats.growthExp + growthMinutes * GROWTH_EXP_PER_MIN

        save(
            advanceStageIfReady(
                stats.copy(
                    hunger = newHunger,
                    thirst = newThirst,
                    happiness = newHappiness,
                    hygiene = newHygiene,
                    poopCount = newPoopCount,
                    minutesTowardPoop = minutesTowardPoop,
                    growthExp = newGrowthExp,
                    lastUpdateMillis = now
                )
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
            minutesTowardPoop = 0.0,
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
            minutesTowardPoop = prefs.getFloat(KEY_MINUTES_TOWARD_POOP, 0f).toDouble(),
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
            .putFloat(KEY_MINUTES_TOWARD_POOP, stats.minutesTowardPoop.toFloat())
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
        private const val KEY_MINUTES_TOWARD_POOP = "pet_minutes_toward_poop"
        private const val KEY_EXP = "pet_exp"
        private const val KEY_LAST_UPDATE = "pet_last_update"
        private const val KEY_ENDING_SHOWN = "pet_ending_shown"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_COMPLETED_PETS = "completed_pets_archive"

        private const val KEY_HEAD_COLOR = "appearance_head_color"
        private const val KEY_BODY_COLOR = "appearance_body_color"
        private const val KEY_ARM_COLOR = "appearance_arm_color"
        private const val KEY_LEG_COLOR = "appearance_leg_color"
        private const val KEY_ARM_LENGTH = "appearance_arm_length"
        private const val KEY_LEG_LENGTH = "appearance_leg_length"

        private val DEFAULT_APPEARANCE = PetAppearance.default()

        private const val HUNGER_DECAY_PER_MIN = 1.0 / 3.0
        private const val THIRST_DECAY_PER_MIN = 1.0 / 4.0
        private const val HYGIENE_DECAY_PER_MIN = 1.0 / 6.0
        private const val HAPPINESS_DECAY_PER_MIN = 1.0 / 5.0
        private const val MINUTES_PER_POOP = 15.0
        /** Growth is purely time-based: 1 exp/minute, matching the egg's incubation pace. */
        private const val GROWTH_EXP_PER_MIN = 1.0

        @Volatile private var instance: PetRepository? = null

        fun get(context: Context): PetRepository = instance ?: synchronized(this) {
            instance ?: PetRepository(context).also { instance = it }
        }
    }
}
