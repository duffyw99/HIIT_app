package com.example.intervaltimer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.intervaltimer.data.DurationType
import com.example.intervaltimer.data.StageType
import com.example.intervaltimer.data.Workout
import com.example.intervaltimer.presentation.DisplayUnit
import com.example.intervaltimer.presentation.WorkoutCreatorViewModel
import com.example.intervaltimer.presentation.buildWorkoutStage
import com.example.intervaltimer.presentation.initialDisplayUnitFor
import com.example.intervaltimer.presentation.initialDurationTextFor
import com.example.intervaltimer.presentation.rememberLocationPermissionGranted
import com.example.intervaltimer.presentation.rememberPermissionLauncher
import com.example.intervaltimer.presentation.theme.AccentBrightGreen
import com.example.intervaltimer.presentation.theme.AccentNeonBlue
import com.example.intervaltimer.presentation.theme.AppDimensions
import com.example.intervaltimer.presentation.theme.BackgroundDark
import com.example.intervaltimer.presentation.theme.DestructiveRed
import com.example.intervaltimer.presentation.theme.OnDark
import com.example.intervaltimer.presentation.theme.SurfaceDark
import java.util.UUID

/**
 * Task 1. [workoutToEdit] null = create mode; non-null = edit mode
 * (pre-populates every field, preserves id/createdAt on save).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutCreatorScreen(
    workoutToEdit: Workout?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    viewModel: WorkoutCreatorViewModel = viewModel()
) {
    var name by remember { mutableStateOf(workoutToEdit?.name ?: "") }

    val prepState = rememberStageFormState(workoutToEdit?.prepStage, "30", DisplayUnit.SECONDS)
    val workState = rememberStageFormState(workoutToEdit?.workStage, "180", DisplayUnit.SECONDS)
    val restState = rememberStageFormState(workoutToEdit?.restStage, "60", DisplayUnit.SECONDS)
    val cooldownState = rememberStageFormState(workoutToEdit?.cooldownStage, "0", DisplayUnit.SECONDS)

    var intervalsText by remember { mutableStateOf((workoutToEdit?.intervals ?: 3).toString()) }
    var finalRest by remember { mutableStateOf(workoutToEdit?.finalRest ?: true) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val locationGranted by rememberLocationPermissionGranted()

    fun trySave() {
        val trimmedName = name.trim()
        val intervals = intervalsText.toIntOrNull()

        val prep = buildWorkoutStage(StageType.PREP, prepState.durationText, prepState.unit)
        val work = buildWorkoutStage(StageType.WORK, workState.durationText, workState.unit)
        val rest = buildWorkoutStage(StageType.REST, restState.durationText, restState.unit)
        val cooldown = buildWorkoutStage(StageType.COOLDOWN, cooldownState.durationText, cooldownState.unit)

        validationError = when {
            trimmedName.isEmpty() -> "Name can't be empty"
            intervals == null || intervals <= 0 -> "Intervals must be a positive number"
            prep == null || work == null || rest == null || cooldown == null ->
                "Check that every duration is a valid non-negative number"
            work.isZeroDuration -> "Work duration must be greater than zero"
            else -> null
        }
        if (validationError != null) return

        val workout = Workout(
            id = workoutToEdit?.id ?: UUID.randomUUID().toString(),
            name = trimmedName,
            prepStage = prep!!,
            workStage = work!!,
            restStage = rest!!,
            cooldownStage = cooldown!!,
            intervals = intervals!!,
            finalRest = finalRest,
            createdAtEpochMillis = workoutToEdit?.createdAtEpochMillis ?: System.currentTimeMillis()
        )
        viewModel.save(workout, onSaved)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .verticalScroll(rememberScrollState())
            .padding(AppDimensions.PaddingLarge)
    ) {
        Text(
            text = if (workoutToEdit == null) "New Workout" else "Edit Workout",
            color = OnDark,
            fontSize = AppDimensions.TextSizeSecondary,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(AppDimensions.PaddingStandard))

        FormLabel("Workout name")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        )

        Spacer(Modifier.height(AppDimensions.PaddingLarge))

        StageFormSection("Prep", prepState, locationGranted)
        StageFormSection("Work", workState, locationGranted)
        StageFormSection("Rest", restState, locationGranted)
        StageFormSection("Cooldown", cooldownState, locationGranted)

        FormLabel("Intervals")
        OutlinedTextField(
            value = intervalsText,
            onValueChange = { intervalsText = it.filter { c -> c.isDigit() } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(120.dp).height(56.dp)
        )

        Spacer(Modifier.height(AppDimensions.PaddingLarge))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FormLabel("Final rest")
            Switch(checked = finalRest, onCheckedChange = { finalRest = it })
        }

        validationError?.let {
            Spacer(Modifier.height(AppDimensions.PaddingStandard))
            Text(it, color = DestructiveRed, fontSize = 18.sp)
        }

        Spacer(Modifier.height(AppDimensions.PaddingLarge))

        Row(horizontalArrangement = Arrangement.spacedBy(AppDimensions.PaddingStandard)) {
            Button(
                onClick = ::trySave,
                modifier = Modifier.height(AppDimensions.ButtonHeight).width(AppDimensions.ButtonWidth),
                shape = RoundedCornerShape(AppDimensions.ButtonCornerRadius),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBrightGreen, contentColor = BackgroundDark)
            ) {
                Text("Save", fontSize = AppDimensions.TextSizeButton, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.height(AppDimensions.ButtonHeight).width(AppDimensions.ButtonWidth),
                shape = RoundedCornerShape(AppDimensions.ButtonCornerRadius)
            ) {
                Text("Cancel", fontSize = AppDimensions.TextSizeButton, fontWeight = FontWeight.Bold, color = OnDark)
            }
        }

        Spacer(Modifier.height(AppDimensions.PaddingLarge))
    }
}

// =========================================================================
// Per-stage form state
// =========================================================================

class StageFormState(initialText: String, initialUnit: DisplayUnit) {
    var durationText by mutableStateOf(initialText)
    var unit by mutableStateOf(initialUnit)
}

@Composable
private fun rememberStageFormState(
    stage: com.example.intervaltimer.data.WorkoutStage?,
    defaultText: String,
    defaultUnit: DisplayUnit
): StageFormState {
    return remember {
        if (stage != null) {
            val unit = initialDisplayUnitFor(stage)
            StageFormState(initialDurationTextFor(stage, unit), unit)
        } else {
            StageFormState(defaultText, defaultUnit)
        }
    }
}

// =========================================================================
// Stage form section: duration + Time/Distance toggle + unit dropdown
// =========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StageFormSection(label: String, state: StageFormState, locationGranted: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(AppDimensions.ButtonCornerRadius))
            .padding(AppDimensions.PaddingStandard)
    ) {
        FormLabel(label)

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.durationText,
                onValueChange = { state.durationText = it.filter { c -> c.isDigit() || c == '.' } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(100.dp).height(56.dp)
            )

            Spacer(Modifier.width(12.dp))

            TypeToggle(state, locationGranted)

            Spacer(Modifier.width(12.dp))

            UnitDropdown(state)
        }

        if (!locationGranted) {
            Spacer(Modifier.height(4.dp))
            LocationPermissionCaption()
        }
    }
    Spacer(Modifier.height(AppDimensions.PaddingStandard))
}

@Composable
private fun TypeToggle(state: StageFormState, locationGranted: Boolean) {
    val isTime = state.unit.durationType == DurationType.TIME_BASED
    val isDistance = !isTime

    Row {
        Button(
            onClick = { if (!isTime) state.unit = DisplayUnit.SECONDS },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isTime) AccentNeonBlue else BackgroundDark,
                contentColor = if (isTime) BackgroundDark else OnDark
            )
        ) { Text("Time") }

        Spacer(Modifier.width(4.dp))

        Button(
            onClick = { if (locationGranted) state.unit = DisplayUnit.METERS },
            enabled = locationGranted || isDistance,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDistance) AccentNeonBlue else BackgroundDark,
                contentColor = if (isDistance) BackgroundDark else OnDark
            )
        ) { Text("Distance") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitDropdown(state: StageFormState) {
    var expanded by remember { mutableStateOf(false) }
    val options = if (state.unit.durationType == DurationType.TIME_BASED) {
        DisplayUnit.TIME_UNITS
    } else {
        DisplayUnit.DISTANCE_UNITS
    }

    Column {
        OutlinedButton(onClick = { expanded = true }) {
            Text(state.unit.label, color = OnDark)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        state.unit = option
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun LocationPermissionCaption() {
    val locationLauncher = rememberPermissionLauncher(
        android.Manifest.permission.ACCESS_FINE_LOCATION
    ) { /* rememberLocationPermissionGranted() re-checks on ON_RESUME */ }

    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            "Location permission needed for distance-based stages",
            color = OnDark.copy(alpha = 0.6f),
            fontSize = 14.sp
        )
        TextButton(onClick = { locationLauncher.request() }) {
            Text("Grant", color = AccentNeonBlue)
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(text, color = OnDark, fontSize = 24.sp, fontWeight = FontWeight.Bold)
}
