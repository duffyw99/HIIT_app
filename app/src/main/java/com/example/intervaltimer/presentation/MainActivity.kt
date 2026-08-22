package com.example.intervaltimer.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.intervaltimer.presentation.screens.ActiveWorkoutScreen
import com.example.intervaltimer.presentation.screens.WorkoutCreatorScreen
import com.example.intervaltimer.presentation.screens.WorkoutListScreen
import com.example.intervaltimer.presentation.theme.IntervalTimerTheme

/**
 * Task 1. Routes, as requested:
 *   "workout_list"    -> WorkoutListScreen
 *   "workout_creator" -> WorkoutCreatorScreen (create mode: workoutToEdit = null)
 *   "workout_detail"  -> WorkoutCreatorScreen (edit mode: workoutToEdit = selected)
 *   "active_workout"  -> ActiveWorkoutScreen
 *
 * DEVIATION: "workout_detail" routes to WorkoutCreatorScreen, not a separate
 * WorkoutDetailScreen -- Session 8 already built create/edit as ONE screen
 * via the nullable workoutToEdit parameter. A second class duplicating that
 * logic would just be two copies of the same form to keep in sync.
 *
 * Back navigation and per-destination state preservation are handled
 * automatically by NavHost/ComponentActivity -- nothing extra to write for
 * that part of the task.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            IntervalTimerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RequestStartupPermissions()
                    AppNavHost()
                }
            }
        }
    }
}

@Composable
private fun AppNavHost() {
    val navController = rememberNavController()
    // Activity-scoped: same instance regardless of which composable in this
    // NavHost calls viewModel() for it. See SelectedWorkoutHolder.kt.
    val selection: SelectedWorkoutHolder = viewModel()

    NavHost(navController = navController, startDestination = "workout_list") {

        composable("workout_list") {
            WorkoutListScreen(
                onCreateNew = {
                    selection.selectedWorkout = null
                    navController.navigate("workout_creator") { launchSingleTop = true }
                },
                onEditWorkout = { workout ->
                    selection.selectedWorkout = workout
                    navController.navigate("workout_detail") { launchSingleTop = true }
                },
                onSelectWorkout = { workout ->
                    selection.selectedWorkout = workout
                    navController.navigate("active_workout") { launchSingleTop = true }
                }
            )
        }

        composable("workout_creator") {
            WorkoutCreatorScreen(
                workoutToEdit = null,
                onSaved = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }

        composable("workout_detail") {
            WorkoutCreatorScreen(
                workoutToEdit = selection.selectedWorkout,
                onSaved = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }

        composable("active_workout") {
            val workout = selection.selectedWorkout
            if (workout != null) {
                ActiveWorkoutScreen(
                    workout = workout,
                    onFinished = { navController.popBackStack("workout_list", inclusive = false) }
                )
            } else {
                // Defensive fallback: this route should never be reached
                // without a selection, but avoids a crash if it ever is
                // (e.g. process restore into this route with no state).
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
    }
}

/**
 * DEVIATION FROM BRIEF (Session 4/6/8 finding, unchanged here): requests
 * ACCESS_FINE_LOCATION and POST_NOTIFICATIONS, NOT RECORD_AUDIO -- this app
 * never captures audio.
 */
@Composable
private fun RequestStartupPermissions() {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results not branched on here; screens that need a permission re-check it themselves before use */ }

    LaunchedEffect(Unit) {
        val required = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val notYetGranted = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notYetGranted.isNotEmpty()) {
            launcher.launch(notYetGranted.toTypedArray())
        }
    }
}
