package com.example.intervaltimer.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.intervaltimer.data.Workout
import com.example.intervaltimer.data.WorkoutDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Task 2. Uses AndroidViewModel (not a plain ViewModel) so it can reach
 * WorkoutDatabase.getInstance(context) without a DI framework -- reasonable
 * for a single-developer personal app; Compose's viewModel() resolves
 * AndroidViewModel subclasses automatically via its default factory, no
 * extra wiring needed at the call site.
 */
class WorkoutListViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = WorkoutDatabase.getInstance(application).workoutDao()

    private val _workouts = MutableStateFlow<List<Workout>>(emptyList())

    /** Task 2: "Emit via StateFlow<List<Workout>>" */
    val workouts: StateFlow<List<Workout>> = _workouts.asStateFlow()

    init {
        viewModelScope.launch {
            dao.getAllWorkouts().collect { _workouts.value = it }
        }
    }

    /** Explicit method wrapper matching the requested name; equivalent to the [workouts] property. */
    fun getWorkouts(): StateFlow<List<Workout>> = workouts

    fun deleteWorkout(id: String) {
        viewModelScope.launch {
            dao.getWorkoutById(id)?.let { dao.deleteWorkout(it) }
        }
    }

    /**
     * Synchronous lookup against the already-collected list -- no need for
     * a suspend DB query since [workouts] is already cached in memory. The
     * caller (WorkoutListScreen) is responsible for what "selecting" a
     * workout actually does (navigate to editor or active-workout screen);
     * this ViewModel has no navigation awareness by design.
     */
    fun selectWorkout(id: String): Workout? = _workouts.value.find { it.id == id }
}
