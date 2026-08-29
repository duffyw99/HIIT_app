package com.example.intervaltimer.domain

import com.example.intervaltimer.data.DurationType
import com.example.intervaltimer.data.SequencedStage
import com.example.intervaltimer.data.Workout
import com.example.intervaltimer.data.WorkoutStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Drives a single [Workout] through its full stage sequence per Section 4.4.
 *
 * This class consumes [Workout.buildStageSequence] directly, so it has no
 * knowledge of intervals/finalRest/block-composition itself — it just walks
 * the flattened Prep -> (interval block)xn -> Cooldown list, reading each
 * entry's precomputed [SequencedStage.intervalNumber] rather than inferring
 * "which repetition" from stage types. That inference (counting WORK-type
 * stages seen so far) worked for the original fixed one-Work/one-Rest
 * design but breaks once a block can contain multiple WORK stages -- e.g.
 * a block of [Work "Sprint", Work "Jog", Rest] would have incremented the
 * old counter twice per repetition instead of once.
 *
 * GPS is intentionally NOT implemented here (Session 3). For a
 * DISTANCE_BASED stage, this class does not measure distance itself —
 * instead it exposes [distanceTravelledCallback], a hook the GPS layer
 * calls repeatedly with the cumulative distance (meters) covered since the
 * current stage began. The executor advances/completes the stage based on
 * those externally pushed values.
 */
class WorkoutExecutor(
    private val workout: Workout,
    externalScope: CoroutineScope? = null
) {

    /** Falls back to an internally-owned scope if the caller doesn't supply one. */
    private val executorScope: CoroutineScope =
        externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ownsScope = externalScope == null

    /** The full Prep -> (interval block)xn -> Cooldown sequence, built once at construction. */
    val stageSequence: List<SequencedStage> = workout.buildStageSequence()

    private val _progress = MutableStateFlow(
        WorkoutProgress(totalStages = stageSequence.size, totalIntervals = workout.intervals)
    )

    /** Task 6 - state updates emitted via Flow for the UI / AudioCueService to collect. */
    val progress: StateFlow<WorkoutProgress> = _progress.asStateFlow()

    private var runnerJob: Job? = null

    // --- Pause coordination --------------------------------------------
    @Volatile private var isPaused: Boolean = false

    // --- Distance-based stage coordination ------------------------------
    /** Cumulative distance (meters) reported for the CURRENT distance-based stage. */
    private val stageDistanceMeters = MutableStateFlow(0.0)

    /**
     * Task 5 - public hook for the GPS layer (Session 3).
     *
     * Call this repeatedly with the cumulative distance travelled (meters)
     * since the current distance-based stage began. Safe to call at any
     * time; updates are ignored unless the executor is RUNNING and the
     * current stage is DISTANCE_BASED, so the GPS layer doesn't need to
     * know the executor's internal state.
     */
    val distanceTravelledCallback: (Double) -> Unit = { metersTravelled ->
        val state = _progress.value
        if (state.executionState == ExecutionState.RUNNING &&
            state.currentStage?.durationType == DurationType.DISTANCE_BASED
        ) {
            stageDistanceMeters.value = metersTravelled
        }
    }

    // =====================================================================
    // Public controls (Task 3)
    // =====================================================================

    /**
     * Begins execution. [fromStageIndex] defaults to 0 (normal start), but
     * can be set to jump directly into a later stage — used by
     * WorkoutService (Session 5) when restoring a workout after the app was
     * killed mid-session (FR-16). Restoration is stage-granular, not
     * second-granular: it resumes the restored stage from its beginning
     * rather than the exact elapsed time at the moment of the kill.
     */
    fun start(fromStageIndex: Int = 0) {
        if (_progress.value.executionState != ExecutionState.IDLE) return
        require(fromStageIndex in stageSequence.indices || stageSequence.isEmpty()) {
            "fromStageIndex $fromStageIndex out of bounds for ${stageSequence.size} stages"
        }
        isPaused = false
        runnerJob = executorScope.launch { runStageSequence(fromStageIndex) }
    }

    /** Suspends the current stage in place; elapsed/remaining values are preserved. */
    fun pause() {
        if (_progress.value.executionState != ExecutionState.RUNNING) return
        isPaused = true
        _progress.update { it.copy(executionState = ExecutionState.PAUSED) }
    }

    /** Continues a paused workout from exactly where it left off. */
    fun resume() {
        if (_progress.value.executionState != ExecutionState.PAUSED) return
        isPaused = false
        _progress.update { it.copy(executionState = ExecutionState.RUNNING) }
    }

    /** Cancels any in-flight stage and begins the workout again from Prep. */
    fun restart() {
        runnerJob?.cancel()
        isPaused = false
        stageDistanceMeters.value = 0.0
        _progress.value = WorkoutProgress(totalStages = stageSequence.size, totalIntervals = workout.intervals)
        start()
    }

    /**
     * Terminates the workout early (FR-13). The UI is responsible for
     * confirming with the user BEFORE calling this — the executor itself
     * does not prompt.
     */
    fun end() {
        runnerJob?.cancel()
        _progress.update {
            it.copy(executionState = ExecutionState.CANCELLED, isCountdown = false, countdownValue = 0)
        }
    }

    /** Call when the owning component (Service/ViewModel) is destroyed. */
    fun release() {
        runnerJob?.cancel()
        if (ownsScope) executorScope.cancel()
    }

    // =====================================================================
    // Core execution loop
    // =====================================================================

    private suspend fun runStageSequence(startIndex: Int) {
        _progress.update { it.copy(executionState = ExecutionState.RUNNING) }

        for (index in startIndex until stageSequence.size) {
            val sequencedStage = stageSequence[index]

            beginStage(sequencedStage.stage, index, sequencedStage.intervalNumber)

            when (sequencedStage.stage.durationType) {
                DurationType.TIME_BASED -> runTimeBasedStage(sequencedStage.stage)
                DurationType.DISTANCE_BASED -> runDistanceBasedStage(sequencedStage.stage)
            }

            // end()/CANCELLED short-circuits the whole sequence immediately.
            if (_progress.value.executionState == ExecutionState.CANCELLED) return
        }

        _progress.update {
            it.copy(
                executionState = ExecutionState.COMPLETED,
                isWorkoutComplete = true,
                isCountdown = false,
                countdownValue = 0
            )
        }
    }

    private fun beginStage(stage: WorkoutStage, index: Int, interval: Int) {
        val totalForStage = when (stage.durationType) {
            DurationType.TIME_BASED -> stage.durationInSeconds!!.toDouble()
            DurationType.DISTANCE_BASED -> stage.durationInMeters!!
        }
        stageDistanceMeters.value = 0.0
        _progress.update {
            it.copy(
                currentStage = stage,
                currentStageIndex = index,
                currentInterval = interval,
                elapsed = 0.0,
                remaining = totalForStage,
                isCountdown = totalForStage <= COUNTDOWN_THRESHOLD,
                countdownValue = if (totalForStage <= COUNTDOWN_THRESHOLD) ceil(totalForStage).toInt().coerceIn(0, 3) else 0
            )
        }
    }

    /** Task 4 - TIME_BASED stages: coroutine + delay(1000) countdown, one tick per second. */
    private suspend fun runTimeBasedStage(stage: WorkoutStage) {
        val totalSeconds = stage.durationInSeconds!!
        var elapsedSeconds = 0L

        while (elapsedSeconds < totalSeconds) {
            awaitIfPaused()
            if (_progress.value.executionState == ExecutionState.CANCELLED) return

            delay(1000L)
            elapsedSeconds++

            val remainingSeconds = (totalSeconds - elapsedSeconds).coerceAtLeast(0L)
            emitTick(elapsed = elapsedSeconds.toDouble(), remaining = remainingSeconds.toDouble())
        }
    }

    /**
     * Task 5 - DISTANCE_BASED stages: no internal timer. This coroutine polls
     * [stageDistanceMeters], which [distanceTravelledCallback] updates from
     * outside (GPS layer, Session 3). The stage completes as soon as the
     * reported distance reaches the target.
     */
    private suspend fun runDistanceBasedStage(stage: WorkoutStage) {
        val totalMeters = stage.durationInMeters!!

        while (true) {
            awaitIfPaused()
            if (_progress.value.executionState == ExecutionState.CANCELLED) return

            val travelled = stageDistanceMeters.value
            val remainingMeters = (totalMeters - travelled).coerceAtLeast(0.0)
            emitTick(elapsed = travelled, remaining = remainingMeters)

            if (travelled >= totalMeters) return

            // Poll frequently; actual GPS fix rate is controlled by the
            // GPSTracker in Session 3, not by this loop.
            delay(DISTANCE_POLL_INTERVAL_MS)
        }
    }

    private fun emitTick(elapsed: Double, remaining: Double) {
        val isCountdown = remaining in 0.0..COUNTDOWN_THRESHOLD
        val countdownValue = if (isCountdown) ceil(remaining).toInt().coerceIn(0, 3) else 0

        _progress.update {
            it.copy(
                elapsed = elapsed,
                remaining = remaining,
                isCountdown = isCountdown,
                countdownValue = countdownValue
            )
        }
    }

    private suspend fun awaitIfPaused() {
        while (isPaused) {
            delay(PAUSE_POLL_INTERVAL_MS)
        }
    }

    companion object {
        /** Section 5.3 / FR-9: countdown cues begin at T-3 seconds or T-3 meters. */
        private const val COUNTDOWN_THRESHOLD = 3.0
        private const val DISTANCE_POLL_INTERVAL_MS = 200L
        private const val PAUSE_POLL_INTERVAL_MS = 100L
    }
}
