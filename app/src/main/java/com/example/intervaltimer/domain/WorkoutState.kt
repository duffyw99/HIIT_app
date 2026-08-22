package com.example.intervaltimer.domain

import com.example.intervaltimer.data.WorkoutStage

/** Task 2 - workout execution state machine. */
enum class ExecutionState {
    IDLE, RUNNING, PAUSED, COMPLETED, CANCELLED
}

/**
 * Task 7 - continuous snapshot of a running workout, emitted by
 * [WorkoutExecutor.progress] so the UI (Session 6/7) and AudioCueService
 * (Session 4) can react in real time.
 *
 * [elapsed]/[remaining] serve double duty per the requirement ("elapsedTime
 * or distanceTravelled"): for a TIME_BASED stage they're seconds; for a
 * DISTANCE_BASED stage they're meters. Check [currentStage]'s
 * [com.example.intervaltimer.data.DurationType] to know which
 * interpretation applies.
 */
data class WorkoutProgress(
    val executionState: ExecutionState = ExecutionState.IDLE,
    val currentStage: WorkoutStage? = null,
    val currentStageIndex: Int = -1,
    val totalStages: Int = 0,
    /** 1-based Work/Rest interval number; 0 during Prep/Cooldown. */
    val currentInterval: Int = 0,
    val totalIntervals: Int = 0,
    /** Elapsed seconds (TIME_BASED) or meters travelled (DISTANCE_BASED) in the current stage. */
    val elapsed: Double = 0.0,
    /** Remaining seconds (TIME_BASED) or meters (DISTANCE_BASED) in the current stage. */
    val remaining: Double = 0.0,
    /** True in the final 3 seconds/meters of a stage (Section 5.3, FR-9). */
    val isCountdown: Boolean = false,
    /** 3, 2, 1 while counting down; 0 otherwise. */
    val countdownValue: Int = 0,
    /** True once the entire stage sequence has finished (drives the FR-9 completion signal). */
    val isWorkoutComplete: Boolean = false
)
