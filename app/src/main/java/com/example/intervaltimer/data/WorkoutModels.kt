package com.example.intervaltimer.data

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import kotlinx.parcelize.Parcelize
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
 * Workout Creator screen (Session 8) round-trip the user's original unit
 * choice when editing a saved workout, instead of always showing the
 * converted base-unit value. It has no effect on execution logic.
 */
@Parcelize
data class WorkoutStage(
    val id: String = UUID.randomUUID().toString(),
    val type: StageType,
    val durationType: DurationType,
    @ColumnInfo(name = "duration_seconds") val durationInSeconds: Long? = null,
    @ColumnInfo(name = "duration_meters") val durationInMeters: Double? = null,
    @ColumnInfo(name = "display_unit") val displayUnit: String? = null
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

    companion object {
        fun ofSeconds(type: StageType, seconds: Long, displayUnit: String? = "SECONDS") =
            WorkoutStage(
                type = type,
                durationType = DurationType.TIME_BASED,
                durationInSeconds = seconds,
                displayUnit = displayUnit
            )

        fun ofMeters(type: StageType, meters: Double, displayUnit: String? = "METERS") =
            WorkoutStage(
                type = type,
                durationType = DurationType.DISTANCE_BASED,
                durationInMeters = meters,
                displayUnit = displayUnit
            )
    }
}

/**
 * Section 4 - A full workout definition.
 *
 * Prep and Cooldown occur once. Work/Rest repeat [intervals] times; the
 * trailing Rest after the final Work stage is only included if [finalRest]
 * is true (Section 4.3).
 */
@Entity(tableName = "workouts")
@Parcelize
@TypeConverters(WorkoutConverters::class)
data class Workout(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    @Embedded(prefix = "prep_") val prepStage: WorkoutStage,
    @Embedded(prefix = "work_") val workStage: WorkoutStage,
    @Embedded(prefix = "rest_") val restStage: WorkoutStage,
    @Embedded(prefix = "cooldown_") val cooldownStage: WorkoutStage,
    val intervals: Int,
    val finalRest: Boolean,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
) : Parcelable {

    init {
        require(intervals > 0) { "intervals must be a positive integer" }
    }

    /**
     * Expands the workout definition into the full ordered stage sequence
     * per Section 4.4 (Workout Sequence Algorithm):
     *   1. Prep (once, skipped if zero duration)
     *   2. For each interval: Work, then Rest (unless final interval and !finalRest)
     *   3. Cooldown (once, skipped if zero duration)
     *
     * WorkoutExecutor (Session 2) consumes this list directly; it does not
     * need to know about intervals/finalRest itself.
     */
    fun buildStageSequence(): List<WorkoutStage> {
        val sequence = mutableListOf<WorkoutStage>()

        if (!prepStage.isZeroDuration) sequence.add(prepStage)

        for (interval in 1..intervals) {
            sequence.add(workStage)
            val isLastInterval = interval == intervals
            if (!isLastInterval || finalRest) {
                sequence.add(restStage)
            }
        }

        if (!cooldownStage.isZeroDuration) sequence.add(cooldownStage)

        return sequence
    }
}

/**
 * Room TypeConverters for the enum fields on the @Embedded WorkoutStage
 * instances. Room can persist embedded primitives/strings directly, but
 * enum columns need explicit String <-> enum conversion.
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
}
