package com.example.intervaltimer.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.intervaltimer.data.Workout
import com.example.intervaltimer.data.WorkoutDatabase
import kotlinx.coroutines.launch

/**
 * Not explicitly requested this session, but added for consistency with
 * every other screen (WorkoutListViewModel, WorkoutViewModel) rather than
 * having WorkoutCreatorScreen touch the database directly.
 */
class WorkoutCreatorViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = WorkoutDatabase.getInstance(application).workoutDao()

    /**
     * insertWorkout uses OnConflictStrategy.REPLACE (Session 1), so this one
     * call handles both create (new id) and edit (same id) -- no separate
     * update path needed.
     */
    fun save(workout: Workout, onSaved: () -> Unit) {
        viewModelScope.launch {
            dao.insertWorkout(workout)
            onSaved()
        }
    }
}
