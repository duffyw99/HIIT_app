package com.example.intervaltimer.service

import android.content.Context
import com.example.intervaltimer.data.Workout
import com.example.intervaltimer.data.toJsonObject
import com.example.intervaltimer.data.toWorkoutStage
import com.example.intervaltimer.domain.WorkoutProgress
import org.json.JSONArray
import org.json.JSONObject

/**
 * FR-16: "If the app is terminated during an active workout, the app shall
 * restore the workout state upon re-launch and allow the user to resume or
 * restart."
 *
 * This uses plain SharedPreferences + hand-rolled JSON (org.json, built
 * into Android — no new Gradle dependency needed) rather than Room, since
 * this is a single mutable "current session" slot, not a query-able table.
 *
 * Restoration is stage-granular (Section note in WorkoutExecutor.start()):
 * we persist which stage index the workout was on, not the exact
 * elapsed-time-within-stage at the moment of the kill.
 */
object WorkoutStatePersistence {

    private const val PREFS_NAME = "workout_state_prefs"
    private const val KEY_WORKOUT_JSON = "active_workout_json"
    private const val KEY_STAGE_INDEX = "active_stage_index"
    private const val KEY_EXECUTION_STATE = "active_execution_state"
    private const val KEY_SAVED_AT_EPOCH_MS = "active_saved_at"

    data class PersistedState(
        val workout: Workout,
        val stageIndex: Int,
        val executionStateName: String,
        val savedAtEpochMillis: Long
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Called once, when a workout starts (or is restored). */
    fun saveActiveWorkout(context: Context, workout: Workout) {
        prefs(context).edit()
            .putString(KEY_WORKOUT_JSON, workout.toJson().toString())
            .putInt(KEY_STAGE_INDEX, 0)
            .putString(KEY_EXECUTION_STATE, "RUNNING")
            .putLong(KEY_SAVED_AT_EPOCH_MS, System.currentTimeMillis())
            .apply()
    }

    /**
     * Called on every progress tick to keep the persisted stage index
     * current. This is deliberately cheap (SharedPreferences.edit().apply()
     * is async) since it runs once per second during TIME_BASED stages.
     */
    fun updateProgressSnapshot(context: Context, progress: WorkoutProgress) {
        // Nothing to persist if no workout is active in this snapshot.
        if (progress.currentStageIndex < 0) return

        prefs(context).edit()
            .putInt(KEY_STAGE_INDEX, progress.currentStageIndex)
            .putString(KEY_EXECUTION_STATE, progress.executionState.name)
            .putLong(KEY_SAVED_AT_EPOCH_MS, System.currentTimeMillis())
            .apply()
    }

    /** Clears persisted state — called on normal workout completion or explicit End. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * Returns the last persisted in-progress workout, or null if there is
     * none (normal case: no workout was running, or it completed/ended
     * cleanly and was already cleared).
     */
    fun load(context: Context): PersistedState? {
        val p = prefs(context)
        val workoutJson = p.getString(KEY_WORKOUT_JSON, null) ?: return null
        val executionStateName = p.getString(KEY_EXECUTION_STATE, null) ?: return null

        // COMPLETED/CANCELLED sessions shouldn't be offered for restoration.
        if (executionStateName == "COMPLETED" || executionStateName == "CANCELLED") return null

        val workout = try {
            JSONObject(workoutJson).toWorkout()
        } catch (e: Exception) {
            null
        } ?: return null

        return PersistedState(
            workout = workout,
            stageIndex = p.getInt(KEY_STAGE_INDEX, 0),
            executionStateName = executionStateName,
            savedAtEpochMillis = p.getLong(KEY_SAVED_AT_EPOCH_MS, 0L)
        )
    }

    // =====================================================================
    // Manual JSON (de)serialization for Workout. WorkoutStage's own
    // toJsonObject()/toWorkoutStage() are shared top-level functions
    // defined in WorkoutModels.kt (also used by Room's TypeConverters
    // there) -- reused here rather than duplicated.
    // =====================================================================

    private fun Workout.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("prepStage", prepStage.toJsonObject())
        put("intervalBlockStages", JSONArray().apply {
            intervalBlockStages.forEach { put(it.toJsonObject()) }
        })
        put("cooldownStage", cooldownStage.toJsonObject())
        put("intervals", intervals)
        put("finalRest", finalRest)
        put("createdAtEpochMillis", createdAtEpochMillis)
    }

    private fun JSONObject.toWorkout(): Workout {
        val blockArray = getJSONArray("intervalBlockStages")
        val blockStages = (0 until blockArray.length()).map { i ->
            blockArray.getJSONObject(i).toWorkoutStage()
        }
        return Workout(
            id = getString("id"),
            name = getString("name"),
            prepStage = getJSONObject("prepStage").toWorkoutStage(),
            intervalBlockStages = blockStages,
            cooldownStage = getJSONObject("cooldownStage").toWorkoutStage(),
            intervals = getInt("intervals"),
            finalRest = getBoolean("finalRest"),
            createdAtEpochMillis = getLong("createdAtEpochMillis")
        )
    }
}
