package com.example.intervaltimer.data

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Section 4.1 - Stage Types
 * Prep and Cooldown are non-repeating; Work/Rest repeat for [Workout.intervals].
 */
enum class StageType {
    PREP, WORK, REST, COOLDOWN
}

/**
 * Section 4.2 - Duration Definition
 * A stage ends either after elapsed time or after distance traveled.
 */
enum class DurationType {
    TIME_BASED, DISTANCE_BASED
}

/**
 * Canonical internal duration for a single stage.
 *
 * Per Section 11.2.1 (Unit Conversion Strategy), all durations are stored
 * in base units regardless of what the user picked in the UI:
 *   - TIME_BASED     -> durationInSeconds
 *   - DISTANCE_BASED -> durationInMeters
 *
 * [displayUnit] is optional metadata (e.g. "MILES", "MINUTES") that lets the
 * Workout Creator screen round-trip the user's original unit choice when
 * editing a saved workout. It has no effect on execution logic.
 *
 * [displayName] (added for the multi-stage interval block refactor) is an
 * optional user-supplied alias -- e.g. "Sprint", "Jog", "Walk" -- shown
 * instead of the generic StageType label ("Work", "Rest") wherever the
 * stage name is displayed. Null/blank means "use the generic label."
 * Meaningful for any stage type, not just WORK, though the Creator UI
 * (Session 8+) primarily surfaces it for interval-block stages, since
 * that's where distinguishing e.g. two different Work stages by name
 * actually matters.
 */
@Parcelize
data class WorkoutStage(
    val id: String = UUID.randomUUID().toString(),
    val type: StageType,
    val durationType: DurationType,
    @ColumnInfo(name = "duration_seconds") val durationInSeconds: Long? = null,
    @ColumnInfo(name = "duration_meters") val durationInMeters: Double? = null,
    @ColumnInfo(name = "display_unit") val displayUnit: String? = null,
    @ColumnInfo(name = "display_name") val displayName: String? = null
) : Parcelable {

    init {
        when (durationType) {
            DurationType.TIME_BASED ->
                require(durationInSeconds != null && durationInSeconds >= 0) {
                    "TIME_BASED stage requires a non-negative durationInSeconds"
                }
            DurationType.DISTANCE_BASED ->
                require(durationInMeters != null && durationInMeters >= 0) {
                    "DISTANCE_BASED stage requires a non-negative durationInMeters"
                }
        }
    }

    /** True for a Prep/Cooldown stage configured with zero duration (i.e. skip it). */
    val isZeroDuration: Boolean
        get() = when (durationType) {
            DurationType.TIME_BASED -> durationInSeconds == 0L
            DurationType.DISTANCE_BASED -> durationInMeters == 0.0
        }

    /** [displayName] if set and non-blank, otherwise the generic type label. */
    fun resolvedDisplayName(): String {
        val alias = displayName?.trim()
        if (!alias.isNullOrEmpty()) return alias
        return when (type) {
            StageType.PREP -> "Prep"
            StageType.WORK -> "Work"
            StageType.REST -> "Rest"
            StageType.COOLDOWN -> "Cooldown"
        }
    }

    companion object {
        fun ofSeconds(
            type: StageType,
            seconds: Long,
            displayUnit: String? = "SECONDS",
            displayName: String? = null
        ) = WorkoutStage(
            type = type,
            durationType = DurationType.TIME_BASED,
            durationInSeconds = seconds,
            displayUnit = displayUnit,
            displayName = displayName
        )

        fun ofMeters(
            type: StageType,
            meters: Double,
            displayUnit: String? = "METERS",
            displayName: String? = null
        ) = WorkoutStage(
            type = type,
            durationType = DurationType.DISTANCE_BASED,
            durationInMeters = meters,
            displayUnit = displayUnit,
            displayName = displayName
        )
    }
}

/**
 * A single stage within the flattened, ready-to-execute sequence, tagged
 * with which repetition of the interval block it belongs to.
 *
 * [intervalNumber] is 0 for Prep/Cooldown (they don't repeat) and 1-based
 * for anything from [Workout.intervalBlockStages]. WorkoutExecutor reads
 * this directly for WorkoutProgress.currentInterval, rather than trying to
 * infer "which repetition" from stage types the way the original
 * single-Work/single-Rest design could -- that inference breaks once a
 * block can contain multiple WORK stages (e.g. "Sprint" then "Jog"), since
 * counting WORK-type stages seen so far no longer corresponds to
 * "repetition number."
 */
data class SequencedStage(
    val stage: WorkoutStage,
    val intervalNumber: Int
)

/**
 * Section 4 - A full workout definition.
 *
 * REFACTORED: originally had fixed workStage/restStage fields (one Work,
 * one Rest, repeated as a pair). Generalized to [intervalBlockStages]: an
 * ordered list of any number of WORK/REST stages that repeat together as a
 * unit -- e.g. [Work 180s "Jog", Work 20s "Sprint", Rest 60s] x8 intervals.
 * Prep and Cooldown remain fixed single bookend stages, unchanged.
 */
@Entity(tableName = "workouts")
@Parcelize
@TypeConverters(WorkoutConverters::class)
data class Workout(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    @Embedded(prefix = "prep_") val prepStage: WorkoutStage,
    /** Serialized to/from JSON by WorkoutConverters -- Room can't @Embedded a List directly. */
    val intervalBlockStages: List<WorkoutStage>,
    @Embedded(prefix = "cooldown_") val cooldownStage: WorkoutStage,
    val intervals: Int,
    val finalRest: Boolean,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
) : Parcelable {

    init {
        require(intervals > 0) { "intervals must be a positive integer" }
        require(intervalBlockStages.isNotEmpty()) {
            "intervalBlockStages must contain at least one stage"
        }
        require(intervalBlockStages.size <= MAX_INTERVAL_BLOCK_STAGES) {
            "intervalBlockStages cannot exceed $MAX_INTERVAL_BLOCK_STAGES stages " +
                "(software cap, not a hard platform limit)"
        }
        require(intervalBlockStages.all { it.type == StageType.WORK || it.type == StageType.REST }) {
            "intervalBlockStages may only contain WORK or REST stages -- " +
                "Prep/Cooldown are fixed bookends, not part of the repeating block"
        }
    }

    /**
     * Expands the workout definition into the full ordered stage sequence:
     *   1. Prep (once, skipped if zero duration)
     *   2. intervalBlockStages, repeated [intervals] times
     *   3. Cooldown (once, skipped if zero duration)
     *
     * [finalRest] generalizes its original meaning (Section 4.3: omit the
     * trailing Rest after the final Work) to multi-stage blocks: on the
     * FINAL repetition only, if finalRest is false, the trailing run of
     * consecutive REST-type stages at the end of the block is omitted. For
     * a block ending in a single Rest stage (the common case, e.g.
     * [Work, Work, Rest]), this is identical to the original behavior. For
     * a block that doesn't end in Rest, finalRest has no effect (there's
     * nothing trailing to omit).
     */
    fun buildStageSequence(): List<SequencedStage> {
        val sequence = mutableListOf<SequencedStage>()

        if (!prepStage.isZeroDuration) sequence.add(SequencedStage(prepStage, intervalNumber = 0))

        val trailingRestCount = intervalBlockStages.asReversed()
            .takeWhile { it.type == StageType.REST }
            .size

        for (interval in 1..intervals) {
            val isLastInterval = interval == intervals
            val stagesForThisRepetition =
                if (isLastInterval && !finalRest && trailingRestCount > 0) {
                    intervalBlockStages.dropLast(trailingRestCount)
                } else {
                    intervalBlockStages
                }
            stagesForThisRepetition.forEach { stage ->
                sequence.add(SequencedStage(stage, intervalNumber = interval))
            }
        }

        if (!cooldownStage.isZeroDuration) sequence.add(SequencedStage(cooldownStage, intervalNumber = 0))

        return sequence
    }

    companion object {
        /** Software best-practice cap (Section 4, interval block refactor) -- not a platform-imposed limit. */
        const val MAX_INTERVAL_BLOCK_STAGES = 20
    }
}

/**
 * Room TypeConverters. Two responsibilities:
 *  1. StageType/DurationType <-> String, for the @Embedded prep/cooldown
 *     stage columns (enums need explicit conversion; Room can't persist
 *     them directly).
 *  2. List<WorkoutStage> <-> JSON string, for [Workout.intervalBlockStages]
 *     -- Room has no @Embedded support for a List of a custom type, only
 *     TypeConverters or a separate child table + @Relation. A single JSON
 *     column is simpler here since block stages are always read/written as
 *     a whole unit (never queried individually), matching the same manual
 *     org.json approach WorkoutStatePersistence.kt already uses.
 */
class WorkoutConverters {
    @androidx.room.TypeConverter
    fun fromStageType(value: StageType): String = value.name

    @androidx.room.TypeConverter
    fun toStageType(value: String): StageType = StageType.valueOf(value)

    @androidx.room.TypeConverter
    fun fromDurationType(value: DurationType): String = value.name

    @androidx.room.TypeConverter
    fun toDurationType(value: String): DurationType = DurationType.valueOf(value)

    @androidx.room.TypeConverter
    fun fromWorkoutStageList(stages: List<WorkoutStage>): String {
        val array = JSONArray()
        stages.forEach { array.put(it.toJsonObject()) }
        return array.toString()
    }

    @androidx.room.TypeConverter
    fun toWorkoutStageList(json: String): List<WorkoutStage> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i -> array.getJSONObject(i).toWorkoutStage() }
    }
}

/** Shared by Room's TypeConverters above and available for reuse (e.g. persistence layers). */
fun WorkoutStage.toJsonObject(): JSONObject = JSONObject().apply {
    put("id", id)
    put("type", type.name)
    put("durationType", durationType.name)
    put("durationInSeconds", durationInSeconds ?: JSONObject.NULL)
    put("durationInMeters", durationInMeters ?: JSONObject.NULL)
    put("displayUnit", displayUnit ?: JSONObject.NULL)
    put("displayName", displayName ?: JSONObject.NULL)
}

fun JSONObject.toWorkoutStage(): WorkoutStage = WorkoutStage(
    id = getString("id"),
    type = StageType.valueOf(getString("type")),
    durationType = DurationType.valueOf(getString("durationType")),
    durationInSeconds = if (isNull("durationInSeconds")) null else getLong("durationInSeconds"),
    durationInMeters = if (isNull("durationInMeters")) null else getDouble("durationInMeters"),
    displayUnit = if (isNull("displayUnit")) null else getString("displayUnit"),
    displayName = if (!has("displayName") || isNull("displayName")) null else getString("displayName")
)
