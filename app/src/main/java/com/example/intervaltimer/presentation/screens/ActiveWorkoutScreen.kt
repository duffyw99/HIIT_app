package com.example.intervaltimer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.intervaltimer.data.Workout
import com.example.intervaltimer.data.DurationType
import com.example.intervaltimer.domain.ExecutionState
import com.example.intervaltimer.presentation.WorkoutViewModel
import com.example.intervaltimer.presentation.theme.AccentBrightGreen
import com.example.intervaltimer.presentation.theme.AccentNeonBlue
import com.example.intervaltimer.presentation.theme.AmberWarning
import com.example.intervaltimer.presentation.theme.AppDimensions
import com.example.intervaltimer.presentation.theme.BackgroundDark
import com.example.intervaltimer.presentation.theme.DestructiveRed
import com.example.intervaltimer.presentation.theme.OnDark

@Composable
fun ActiveWorkoutScreen(
    workout: Workout,
    onFinished: () -> Unit,
    viewModel: WorkoutViewModel = viewModel()
) {
    val progress by viewModel.progress.collectAsState()
    val stageName by viewModel.currentStageName.collectAsState()
    val timerValue by viewModel.timerValue.collectAsState()
    val remainingIntervals by viewModel.remainingIntervals.collectAsState()

    var showRestartConfirm by remember { mutableStateOf(false) }
    var showEndConfirm by remember { mutableStateOf(false) }

    // Screen wake: on while a session is actually in progress, off otherwise.
    // This is the FLAG_KEEP_SCREEN_ON side of Section 7.5 -- WorkoutService's
    // wake lock (Session 5) covers the CPU; this covers the display.
    val view = LocalView.current
    val keepAwake = progress.executionState == ExecutionState.RUNNING ||
        progress.executionState == ExecutionState.PAUSED
    LaunchedEffect(keepAwake) {
        view.keepScreenOn = keepAwake
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(AppDimensions.PaddingLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stageName,
            color = AccentNeonBlue,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(AppDimensions.PaddingLarge))

        Text(
            text = formatTimerValue(timerValue, progress.currentStage?.durationType),
            color = OnDark,
            fontSize = 96.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(AppDimensions.PaddingStandard))

        if (progress.totalIntervals > 0) {
            Text(
                text = "Interval ${progress.currentInterval.coerceAtLeast(1)} of ${progress.totalIntervals}",
                color = OnDark,
                fontSize = AppDimensions.TextSizeSecondary
            )
        }

        Spacer(Modifier.height(AppDimensions.PaddingLarge * 2))

        ControlButtonRow(
            executionState = progress.executionState,
            onStart = { viewModel.startWorkout(workout) },
            onPause = { viewModel.pauseWorkout() },
            onResume = { viewModel.resumeWorkout() },
            onRestartRequested = { showRestartConfirm = true },
            onEndRequested = { showEndConfirm = true },
            onDone = onFinished
        )
    }

    if (showRestartConfirm) {
        ConfirmDialog(
            title = "Restart workout?",
            message = "This starts over from Prep.",
            confirmLabel = "Restart",
            onConfirm = {
                viewModel.restartWorkout()
                showRestartConfirm = false
            },
            onDismiss = { showRestartConfirm = false }
        )
    }

    if (showEndConfirm) {
        ConfirmDialog(
            title = "End workout?",
            message = "This can't be undone.",
            confirmLabel = "End",
            onConfirm = {
                viewModel.endWorkout()
                showEndConfirm = false
            },
            onDismiss = { showEndConfirm = false }
        )
    }
}

@Composable
private fun ControlButtonRow(
    executionState: ExecutionState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRestartRequested: () -> Unit,
    onEndRequested: () -> Unit,
    onDone: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppDimensions.PaddingStandard)) {
        when (executionState) {
            ExecutionState.IDLE -> {
                ActionButton("Start", AccentBrightGreen, BackgroundDark, onStart)
            }
            ExecutionState.RUNNING -> {
                ActionButton("Pause", AmberWarning, BackgroundDark, onPause)
                ActionButton("End", DestructiveRed, OnDark, onEndRequested)
            }
            ExecutionState.PAUSED -> {
                ActionButton("Resume", AccentBrightGreen, BackgroundDark, onResume)
                ActionButton("Restart", DestructiveRed, OnDark, onRestartRequested)
                ActionButton("End", DestructiveRed, OnDark, onEndRequested)
            }
            ExecutionState.COMPLETED, ExecutionState.CANCELLED -> {
                ActionButton("Done", AccentNeonBlue, BackgroundDark, onDone)
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(AppDimensions.ButtonWidth)
            .height(AppDimensions.ButtonHeight),
        shape = RoundedCornerShape(AppDimensions.ButtonCornerRadius),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor)
    ) {
        Text(label, fontSize = AppDimensions.TextSizeButton, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = OnDark)
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatTimerValue(value: Double, durationType: DurationType?): String {
    return when (durationType) {
        DurationType.DISTANCE_BASED -> "${value.toInt()}m"
        DurationType.TIME_BASED, null -> {
            val totalSeconds = value.toLong()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            "%d:%02d".format(minutes, seconds)
        }
    }
}
