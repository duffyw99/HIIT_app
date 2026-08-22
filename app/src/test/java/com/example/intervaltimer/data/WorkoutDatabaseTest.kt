package com.example.intervaltimer.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TASK 3. DEVIATION FROM BRIEF: uses androidx.test (real instrumented test,
 * runs on emulator/device), not Robolectric. Session 1's build.gradle.kts
 * already has androidTestImplementation("androidx.room:room-testing:2.6.1")
 * and androidx.test.ext:junit -- Robolectric would need an entirely new
 * dependency + JVM Android-shadowing setup for no benefit here, since a
 * real emulator is already part of the workflow.
 *
 * Runs on an in-memory Room database (isolated from the app's real
 * interval_timer.db) that is closed after every test -- satisfies "clean
 * up database after each test" without needing explicit row deletion.
 */
@RunWith(AndroidJUnit4::class)
class WorkoutDatabaseTest {

    private lateinit var database: WorkoutDatabase
    private lateinit var dao: WorkoutDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WorkoutDatabase::class.java
        ).allowMainThreadQueries().build() // acceptable for tests; app code never does this
        dao = database.workoutDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun sampleWorkout(name: String = "Sample") = Workout(
        name = name,
        prepStage = WorkoutStage.ofSeconds(StageType.PREP, 30),
        workStage = WorkoutStage.ofSeconds(StageType.WORK, 180),
        restStage = WorkoutStage.ofSeconds(StageType.REST, 60),
        cooldownStage = WorkoutStage.ofSeconds(StageType.COOLDOWN, 0),
        intervals = 3,
        finalRest = true
    )

    @Test
    fun insertAndQueryAllWorkouts() = runBlocking {
        val workout = sampleWorkout()
        dao.insertWorkout(workout)

        val all = dao.getAllWorkouts().first()

        assertEquals(1, all.size)
        assertEquals(workout.id, all.first().id)
        assertEquals("Sample", all.first().name)
    }

    @Test
    fun updateWorkout_persistsChangedFields() = runBlocking {
        val original = sampleWorkout()
        dao.insertWorkout(original)

        dao.updateWorkout(original.copy(name = "Renamed", intervals = 5))

        val fromDb = dao.getWorkoutById(original.id)
        assertNotNull(fromDb)
        assertEquals("Renamed", fromDb!!.name)
        assertEquals(5, fromDb.intervals)
    }

    @Test
    fun deleteWorkout_removesIt() = runBlocking {
        val workout = sampleWorkout()
        dao.insertWorkout(workout)
        assertNotNull(dao.getWorkoutById(workout.id))

        dao.deleteWorkout(workout)

        assertNull(dao.getWorkoutById(workout.id))
    }

    @Test
    fun deleteWorkoutById_removesIt() = runBlocking {
        val workout = sampleWorkout()
        dao.insertWorkout(workout)

        dao.deleteWorkoutById(workout.id)

        assertNull(dao.getWorkoutById(workout.id))
    }

    @Test
    fun getAllWorkouts_ordersByMostRecentFirst() = runBlocking {
        dao.insertWorkout(sampleWorkout("Older").copy(createdAtEpochMillis = 1000L))
        dao.insertWorkout(sampleWorkout("Newer").copy(createdAtEpochMillis = 2000L))

        val all = dao.getAllWorkouts().first()

        assertEquals("Newer", all[0].name)
        assertEquals("Older", all[1].name)
    }
}
