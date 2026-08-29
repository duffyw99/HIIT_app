package com.example.intervaltimer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Schema version bumped 1 -> 2 for the interval-block refactor: Workout's
 * columns changed fundamentally (work_*/rest_* embedded columns removed,
 * replaced by a single intervalBlockStages JSON column). Uses
 * fallbackToDestructiveMigration() rather than a real Migration object --
 * reasonable for a personal, actively-developed, sideloaded app with no
 * existing users to preserve data for, but NOT something to carry forward
 * if this app is ever shared more broadly. This means any workouts saved
 * under the OLD schema are WIPED the first time the app runs after this
 * update -- worth knowing before installing over an existing install.
 */
@Database(
    entities = [Workout::class],
    version = 2,
    exportSchema = false
)
abstract class WorkoutDatabase : RoomDatabase() {

    abstract fun workoutDao(): WorkoutDao

    companion object {
        private const val DATABASE_NAME = "interval_timer.db"

        @Volatile
        private var INSTANCE: WorkoutDatabase? = null

        fun getInstance(context: Context): WorkoutDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WorkoutDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
