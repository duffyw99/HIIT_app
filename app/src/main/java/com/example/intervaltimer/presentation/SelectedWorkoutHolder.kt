package com.example.intervaltimer.presentation

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.example.intervaltimer.data.Workout

/**
 * Not requested explicitly, but needed: "workout_detail" and "active_workout"
 * both need a specific Workout object, and Compose Navigation's string-based
 * routes don't pass complex objects without custom NavType/Parcelable
 * boilerplate. Since every route lives in the same single Activity, calling
 * viewModel() for this class from any composable resolves to the SAME
 * activity-scoped instance -- the standard shared-ViewModel pattern for
 * single-Activity Compose apps.
 */
class SelectedWorkoutHolder(application: Application) : AndroidViewModel(application) {
    var selectedWorkout by mutableStateOf<Workout?>(null)
}
