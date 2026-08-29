package com.example.intervaltimer.presentation

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.intervaltimer.data.Workout
import com.example.intervaltimer.domain.WorkoutProgress
import com.example.intervaltimer.service.WorkoutService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// NOTE: audio cue triggering (countdown/transition tones) is NOT wired here.
// WorkoutService.wireExecutorCollectors() (Session 5) already does this by
// listening to executor.progress directly and calling AudioCueService.
// Duplicating that here would fire every tone twice.
class WorkoutViewModel(application: Application) : AndroidViewModel(application) {

    private var boundService: WorkoutService? = null

    private val _progress = MutableStateFlow(WorkoutProgress())
    val progress: StateFlow<WorkoutProgress> = _progress.asStateFlow()

    /**
     * Prefers the stage's user-supplied [com.example.intervaltimer.data.WorkoutStage.displayName]
     * alias (e.g. "Sprint", "Jog") over the generic type label ("Work"),
     * via WorkoutStage.resolvedDisplayName() -- shown in large font on
     * ActiveWorkoutScreen per the interval-block refactor's requirement.
     */
    val currentStageName: StateFlow<String> = progress
        .map { it.currentStage?.resolvedDisplayName() ?: "Ready" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Ready")

    val timerValue: StateFlow<Double> = progress
        .map { it.remaining }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val remainingIntervals: StateFlow<Int> = progress
        .map { (it.totalIntervals - it.currentInterval).coerceAtLeast(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as WorkoutService.LocalBinder).getService()
            boundService = service
            viewModelScope.launch {
                service.progress.collect { _progress.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }

    init {
        val intent = Intent(application, WorkoutService::class.java)
        // Started (not just bound) so the service survives independently of
        // this ViewModel's binding lifecycle -- required for a foreground
        // service that should keep running if the Activity briefly unbinds.
        ContextCompat.startForegroundService(application, intent)
        application.bindService(intent, connection, 0)
    }

    fun startWorkout(workout: Workout) = boundService?.startWorkout(workout) ?: Unit
    fun pauseWorkout() = boundService?.pauseWorkout() ?: Unit
    fun resumeWorkout() = boundService?.resumeWorkout() ?: Unit
    fun restartWorkout() = boundService?.restartWorkout() ?: Unit
    fun endWorkout() = boundService?.endWorkout() ?: Unit

    override fun onCleared() {
        getApplication<Application>().unbindService(connection)
        super.onCleared()
    }
}
