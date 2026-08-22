package com.example.intervaltimer.presentation

import com.example.intervaltimer.data.DurationType
import com.example.intervaltimer.data.StageType
import com.example.intervaltimer.data.WorkoutStage
import kotlin.math.floor
import kotlin.math.round

/**
 * Section 11.2.1 - user-facing units. [toCanonicalFactor] converts ONE unit
 * of this type into the canonical storage unit (seconds for TIME_BASED,
 * meters for DISTANCE_BASED): canonicalValue = inputValue * toCanonicalFactor.
 */
enum class DisplayUnit(val label: String, val durationType: DurationType, val toCanonicalFactor: Double) {
    SECONDS("seconds", DurationType.TIME_BASED, 1.0),
    MINUTES("minutes", DurationType.TIME_BASED, 60.0),
    HOURS("hours", DurationType.TIME_BASED, 3600.0),
    FEET("feet", DurationType.DISTANCE_BASED, 0.3048),
    YARDS("yards", DurationType.DISTANCE_BASED, 0.9144),
    QUARTER_MILE("quarter-mile", DurationType.DISTANCE_BASED, 402.336),
    MILE("mile", DurationType.DISTANCE_BASED, 1609.344),
    METERS("meters", DurationType.DISTANCE_BASED, 1.0),
    KILOMETERS("kilometers", DurationType.DISTANCE_BASED, 1000.0);

    companion object {
        val TIME_UNITS = listOf(SECONDS, MINUTES, HOURS)
        val DISTANCE_UNITS = listOf(FEET, YARDS, QUARTER_MILE, MILE, METERS, KILOMETERS)
    }
}

/**
 * Parses [rawText] under [unit] and builds a WorkoutStage in canonical
 * units. Returns null on invalid/negative input so the caller can show a
 * validation error instead of crashing.
 */
fun buildWorkoutStage(stageType: StageType, rawText: String, unit: DisplayUnit): WorkoutStage? {
    val inputValue = rawText.toDoubleOrNull() ?: return null
    if (inputValue < 0) return null
    val canonical = inputValue * unit.toCanonicalFactor

    return when (unit.durationType) {
        DurationType.TIME_BASED -> WorkoutStage(
            type = stageType,
            durationType = DurationType.TIME_BASED,
            durationInSeconds = round(canonical).toLong(),
            displayUnit = unit.name
        )
        DurationType.DISTANCE_BASED -> WorkoutStage(
            type = stageType,
            durationType = DurationType.DISTANCE_BASED,
            durationInMeters = canonical,
            displayUnit = unit.name
        )
    }
}

/** Recovers the unit the stage was originally entered in (Section 11.2.1 round-tripping), falling back to the canonical unit if unknown/absent. */
fun initialDisplayUnitFor(stage: WorkoutStage): DisplayUnit {
    stage.displayUnit?.let { name -> DisplayUnit.entries.find { it.name == name }?.let { return it } }
    return if (stage.durationType == DurationType.TIME_BASED) DisplayUnit.SECONDS else DisplayUnit.METERS
}

/** Converts the stage's canonical value back into [unit] for display in the form field. */
fun initialDurationTextFor(stage: WorkoutStage, unit: DisplayUnit): String {
    val canonical = when (stage.durationType) {
        DurationType.TIME_BASED -> stage.durationInSeconds?.toDouble() ?: 0.0
        DurationType.DISTANCE_BASED -> stage.durationInMeters ?: 0.0
    }
    return formatForDisplay(canonical / unit.toCanonicalFactor)
}

private fun formatForDisplay(value: Double): String {
    return if (value == floor(value)) {
        value.toLong().toString()
    } else {
        "%.3f".format(value).trimEnd('0').trimEnd('.')
    }
}
