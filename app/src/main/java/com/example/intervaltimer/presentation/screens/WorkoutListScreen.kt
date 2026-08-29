package com.example.intervaltimer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.intervaltimer.data.DurationType
import com.example.intervaltimer.data.Workout
import com.example.intervaltimer.data.WorkoutStage
import com.example.intervaltimer.presentation.WorkoutListViewModel
import com.example.intervaltimer.presentation.theme.AccentBrightGreen
import com.example.intervaltimer.presentation.theme.AccentNeonBlue
import com.example.intervaltimer.presentation.theme.AppDimensions
import com.example.intervaltimer.presentation.theme.BackgroundDark
import com.example.intervaltimer.presentation.theme.DestructiveRed
import com.example.intervaltimer.presentation.theme.OnDark
import com.example.intervaltimer.presentation.theme.SurfaceDark

/**
 * Task 3. Displays saved workouts and lets the user create, edit, delete,
 * or select one to run.
 *
 * Navigation is deliberately NOT wired here -- NavController doesn't exist
 * until Session 9. The three callbacks below are how this screen stays
 * decoupled from that; MainActivity currently passes no-op placeholders.
 */
@Composable
fun WorkoutListScreen(
    onCreateNew: () -> Unit,
    onEditWorkout: (Workout) -> Unit,
    onSelectWorkout: (Workout) -> Unit,
    viewModel: WorkoutListViewModel = viewModel()
) {
    val workouts by viewModel.workouts.collectAsState()
    var pendingDelete by remember { mutableStateOf<Workout?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(AppDimensions.PaddingLarge)
    ) {
        Button(
            onClick = onCreateNew,
            modifier = Modifier
                .fillMaxWidth()
                .height(AppDimensions.ButtonHeight),
            shape = RoundedCornerShape(AppDimensions.ButtonCornerRadius),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentBrightGreen,
                contentColor = BackgroundDark
            )
        ) {
            Text(
                text = "+ Create New Workout",
                fontSize = AppDimensions.TextSizeButton,
                fontWeight = FontWeight.Bold
            )
        }

        androidx.compose.foundation.layout.Spacer(Modifier.height(AppDimensions.PaddingStandard))

        if (workouts.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(AppDimensions.PaddingStandard),
                contentPadding = PaddingValues(bottom = AppDimensions.PaddingLarge)
            ) {
                items(workouts, key = { it.id }) { workout ->
                    WorkoutCard(
                        workout = workout,
                        onSelect = { onSelectWorkout(workout) },
                        onEdit = { onEditWorkout(workout) },
                        onDeleteRequested = { pendingDelete = workout }
                    )
                }
            }
        }
    }

    pendingDelete?.let { workout ->
        DeleteConfirmationDialog(
            workoutName = workout.name,
            onConfirm = {
                viewModel.deleteWorkout(workout.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No workouts yet.\nTap Create New Workout to add one.",
            color = OnDark,
            fontSize = AppDimensions.TextSizeSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun WorkoutCard(
    workout: Workout,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDeleteRequested: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, shape = RoundedCornerShape(AppDimensions.ButtonCornerRadius))
            .padding(AppDimensions.PaddingStandard)
    ) {
        Text(
            text = workout.name,
            color = OnDark,
            fontSize = AppDimensions.TextSizeSecondary,
            fontWeight = FontWeight.Bold
        )

        androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))

        Text(
            text = stageSummary(workout),
            color = OnDark.copy(alpha = 0.75f),
            fontSize = 18.sp
        )

        androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))

        Text(
            text = "${workout.intervals} interval${if (workout.intervals == 1) "" else "s"}" +
                " • Final rest: ${if (workout.finalRest) "Yes" else "No"}",
            color = OnDark.copy(alpha = 0.6f),
            fontSize = 16.sp
        )

        androidx.compose.foundation.layout.Spacer(Modifier.height(AppDimensions.PaddingStandard))

        Row(horizontalArrangement = Arrangement.spacedBy(AppDimensions.PaddingStandard)) {
            Button(
                onClick = onSelect,
                modifier = Modifier
                    .width(AppDimensions.ButtonWidth)
                    .height(AppDimensions.ButtonHeight),
                shape = RoundedCornerShape(AppDimensions.ButtonCornerRadius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBrightGreen,
                    contentColor = BackgroundDark
                )
            ) {
                Text("Start", fontSize = AppDimensions.TextSizeButton, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onEdit,
                modifier = Modifier
                    .width(AppDimensions.ButtonWidth)
                    .height(AppDimensions.ButtonHeight),
                shape = RoundedCornerShape(AppDimensions.ButtonCornerRadius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentNeonBlue,
                    contentColor = BackgroundDark
                )
            ) {
                Text("Edit", fontSize = AppDimensions.TextSizeButton, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onDeleteRequested,
                modifier = Modifier
                    .width(AppDimensions.ButtonWidth)
                    .height(AppDimensions.ButtonHeight),
                shape = RoundedCornerShape(AppDimensions.ButtonCornerRadius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DestructiveRed,
                    contentColor = OnDark
                )
            ) {
                Text("Delete", fontSize = AppDimensions.TextSizeButton, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    workoutName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"$workoutName\"?") },
        text = { Text("This can't be undone.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = OnDark)
            ) { Text("Delete") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Intentionally shows RAW canonical units (mm:ss for time, meters for
 * distance) rather than the user's original display unit (miles, etc.) --
 * full round-tripping through WorkoutStage.displayUnit is a Workout
 * Creator/Editor concern (Session 8), not this list view's job.
 */
private fun stageSummary(workout: Workout): String {
    fun formatStage(stage: WorkoutStage, label: String = stage.resolvedDisplayName()): String {
        val value = when (stage.durationType) {
            DurationType.TIME_BASED -> formatMmSs(stage.durationInSeconds ?: 0L)
            DurationType.DISTANCE_BASED -> "${stage.durationInMeters?.toInt() ?: 0}m"
        }
        return "$label $value"
    }

    // Prep/Cooldown keep their fixed bookend labels; each interval block
    // stage uses its own resolvedDisplayName() (alias if set, else "Work"/
    // "Rest") since a block can now contain several distinctly-named
    // stages (e.g. "Sprint", "Jog") rather than one fixed Work + one Rest.
    // Truncated at 4 block stages so a large block (up to
    // Workout.MAX_INTERVAL_BLOCK_STAGES = 20) doesn't produce an
    // unreadably long summary line on this compact card -- full detail is
    // always visible on the Creator/Edit screen.
    val parts = mutableListOf(formatStage(workout.prepStage, "Prep"))
    val blockStageSummaries = workout.intervalBlockStages.map { formatStage(it) }
    if (blockStageSummaries.size <= 4) {
        parts.addAll(blockStageSummaries)
    } else {
        parts.addAll(blockStageSummaries.take(4))
        parts.add("+${blockStageSummaries.size - 4} more")
    }
    parts.add(formatStage(workout.cooldownStage, "Cooldown"))

    return parts.joinToString(" • ")
}

private fun formatMmSs(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
