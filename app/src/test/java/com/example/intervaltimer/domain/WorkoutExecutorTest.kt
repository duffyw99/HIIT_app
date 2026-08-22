package com.example.intervaltimer.domain

import com.example.intervaltimer.data.StageType
import com.example.intervaltimer.data.Workout
import com.example.intervaltimer.data.WorkoutStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK 2. DEVIATION FROM BRIEF: uses runTest (virtual time), not runBlocking.
 * WorkoutExecutor accepts an external CoroutineScope; passing the TestScope
 * from runTest lets advanceTimeBy()/runCurrent() fast-forward every
 * delay(1000L) instantly, instead of the test suite actually waiting ~7
 * real seconds per run. Same assertions, far faster and fully deterministic.
 *
 * DEVIATION FROM BRIEF: the requested sequence "Prep -> Work -> Rest ->
 * Cooldown -> COMPLETED" does not match Workout.buildStageSequence()
 * (Session 1), which explicitly SKIPS Prep/Cooldown when their duration is
 * zero. With Cooldown:0s as specified, Cooldown never appears as a real
 * stage transition -- the sequence is Prep -> Work -> Rest -> COMPLETED.
 * Tests below assert the actual (correct) behavior rather than the
 * inaccurate expected sequence, to avoid encoding a bug as "passing."
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutExecutorTest {

    /** Prep 2s, Work 3s, Rest 2s, Cooldown 0s, Intervals 1, Final Rest = true. */
    private fun buildTestWorkout(): Workout = Workout(
        name = "Test Workout",
        prepStage = WorkoutStage.ofSeconds(StageType.PREP, 2),
        workStage = WorkoutStage.ofSeconds(StageType.WORK, 3),
        restStage = WorkoutStage.ofSeconds(StageType.REST, 2),
        cooldownStage = WorkoutStage.ofSeconds(StageType.COOLDOWN, 0),
        intervals = 1,
        finalRest = true
    )

    @Test
    fun `state progresses through Prep, Work, Rest to COMPLETED`() = runTest {
        val executor = WorkoutExecutor(buildTestWorkout(), externalScope = this)
        assertEquals(ExecutionState.IDLE, executor.progress.value.executionState)

        executor.start()
        runCurrent()
        assertEquals(ExecutionState.RUNNING, executor.progress.value.executionState)
        assertEquals(StageType.PREP, executor.progress.value.currentStage?.type)

        advanceTimeBy(2_100) // past Prep's 2s
        runCurrent()
        assertEquals(StageType.WORK, executor.progress.value.currentStage?.type)
        assertEquals(1, executor.progress.value.currentInterval)

        advanceTimeBy(3_100) // past Work's 3s
        runCurrent()
        assertEquals(StageType.REST, executor.progress.value.currentStage?.type)

        advanceTimeBy(2_100) // past Rest's 2s -- Cooldown(0s) is skipped, sequence ends here
        runCurrent()
        assertEquals(ExecutionState.COMPLETED, executor.progress.value.executionState)
        assertTrue(executor.progress.value.isWorkoutComplete)
    }

    @Test
    fun `pause during Prep freezes progress, resume continues it`() = runTest {
        val executor = WorkoutExecutor(buildTestWorkout(), externalScope = this)
        executor.start()
        runCurrent()

        advanceTimeBy(1_100) // 1 of Prep's 2 seconds elapsed
        runCurrent()
        val elapsedBeforePause = executor.progress.value.elapsed

        executor.pause()
        assertEquals(ExecutionState.PAUSED, executor.progress.value.executionState)

        advanceTimeBy(5_000) // time passes while paused -- should NOT advance the stage
        runCurrent()
        assertEquals(elapsedBeforePause, executor.progress.value.elapsed, 0.0)
        assertEquals(StageType.PREP, executor.progress.value.currentStage?.type)

        executor.resume()
        assertEquals(ExecutionState.RUNNING, executor.progress.value.executionState)

        advanceTimeBy(1_100) // remaining 1 second of Prep
        runCurrent()
        assertEquals(StageType.WORK, executor.progress.value.currentStage?.type)
    }

    @Test
    fun `pause during Work freezes progress, resume continues it`() = runTest {
        val executor = WorkoutExecutor(buildTestWorkout(), externalScope = this)
        executor.start()
        runCurrent()
        advanceTimeBy(2_100) // past Prep, into Work
        runCurrent()

        advanceTimeBy(1_100) // 1 of Work's 3 seconds elapsed
        runCurrent()
        val elapsedBeforePause = executor.progress.value.elapsed

        executor.pause()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(elapsedBeforePause, executor.progress.value.elapsed, 0.0)
        assertEquals(StageType.WORK, executor.progress.value.currentStage?.type)

        executor.resume()
        advanceTimeBy(2_100) // remaining 2 seconds of Work
        runCurrent()
        assertEquals(StageType.REST, executor.progress.value.currentStage?.type)
    }

    @Test
    fun `restart resets to IDLE then begins again from Prep`() = runTest {
        val executor = WorkoutExecutor(buildTestWorkout(), externalScope = this)
        executor.start()
        runCurrent()
        advanceTimeBy(2_100) // into Work
        runCurrent()
        assertEquals(StageType.WORK, executor.progress.value.currentStage?.type)

        executor.restart()
        // restart() synchronously resets state to IDLE before its internal
        // start() call's launched coroutine has run its first line --
        // asserting here, before runCurrent(), is what makes this
        // observable rather than racy.
        assertEquals(ExecutionState.IDLE, executor.progress.value.executionState)

        runCurrent()
        assertEquals(ExecutionState.RUNNING, executor.progress.value.executionState)
        assertEquals(StageType.PREP, executor.progress.value.currentStage?.type)
        assertEquals(0.0, executor.progress.value.elapsed, 0.0)
    }

    @Test
    fun `countdown activates at T-3 and counts down to transition`() = runTest {
        // Separate workout: Work 5s is long enough to show isCountdown=false
        // early on, then true only in the final 3 seconds -- the 2s/3s/2s
        // stages in buildTestWorkout() are all <=3s already, which would
        // trivially start "in countdown" and not exercise the T-3 threshold.
        val workout = Workout(
            name = "Countdown Test",
            prepStage = WorkoutStage.ofSeconds(StageType.PREP, 0),
            workStage = WorkoutStage.ofSeconds(StageType.WORK, 5),
            restStage = WorkoutStage.ofSeconds(StageType.REST, 0),
            cooldownStage = WorkoutStage.ofSeconds(StageType.COOLDOWN, 0),
            intervals = 1,
            finalRest = false
        )
        val executor = WorkoutExecutor(workout, externalScope = this)
        executor.start()
        runCurrent() // Prep(0s) skipped entirely -> straight into Work

        assertEquals(StageType.WORK, executor.progress.value.currentStage?.type)
        assertFalse(executor.progress.value.isCountdown) // remaining=5, not yet <=3

        advanceTimeBy(2_100) // remaining = 3
        runCurrent()
        assertTrue(executor.progress.value.isCountdown)
        assertEquals(3, executor.progress.value.countdownValue)

        advanceTimeBy(1_000) // remaining = 2
        runCurrent()
        assertEquals(2, executor.progress.value.countdownValue)

        advanceTimeBy(1_000) // remaining = 1
        runCurrent()
        assertEquals(1, executor.progress.value.countdownValue)

        advanceTimeBy(1_000) // remaining = 0 -> stage/workout completes
        runCurrent()
        assertEquals(ExecutionState.COMPLETED, executor.progress.value.executionState)
    }
}
