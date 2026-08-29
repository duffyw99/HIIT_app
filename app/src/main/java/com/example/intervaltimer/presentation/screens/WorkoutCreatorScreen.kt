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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.intervaltimer.data.DurationType
import com.example.intervaltimer.data.StageType
import com.example.intervaltimer.data.Workout
import com.example.intervaltimer.data.WorkoutStage
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
 * [workoutToEdit] null = create mode; non-null = edit mode (pre-populates
 * every field, preserves id/createdAt on save).
 *
 * REFACTORED (interval block support): Work/Rest are no longer two fixed
 * sections. Instead, [blockStages] (see IntervalBlockSection) is a
 * dynamic, user-editable, ordered list of any number of WORK/REST stages
 * (1..Workout.MAX_INTERVAL_BLOCK_STAGES) that repeat together as a unit --
 * e.g. [Work "Sprint" 20s, Work "Jog" 180s, Rest 60s] x8 intervals. Each
 * block stage can carry an optional display-name alias, shown in place of
 * the generic "Work"/"Rest" label wherever the stage is displayed (large
 * font on ActiveWorkoutScreen). Prep and Cooldown remain fixed
 * single-stage bookends, unchanged.
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
    val cooldownState = rememberStageFormState(workoutToEdit?.cooldownStage, "0", DisplayUnit.SECONDS)

    val blockStages = rememberBlockStageFormStates(workoutToEdit?.intervalBlockStages)

    var intervalsText by remember { mutableStateOf((workoutToEdit?.intervals ?: 8).toString()) }
    var finalRest by remember { mutableStateOf(workoutToEdit?.finalRest ?: true) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val locationGranted by rememberLocationPermissionGranted()

    fun trySave() {
        val trimmedName = name.trim()
        val intervals = intervalsText.toIntOrNull()

        val prep = buildWorkoutStage(StageType.PREP, prepState.durationText, prepState.unit)
        val cooldown = buildWorkoutStage(StageType.COOLDOWN, cooldownState.durationText, cooldownState.unit)
        val builtBlockStages = blockStages.map {
            buildWorkoutStage(it.stageType, it.durationText, it.unit, it.displayNameText)
        }

        validationError = when {
            trimmedName.isEmpty() -> "Name can't be empty"
            intervals == null || intervals <= 0 -> "Intervals must be a positive number"
            prep == null || cooldown == null ->
                "Check that Prep and Cooldown durations are valid non-negative numbers"
            blockStages.isEmpty() -> "Add at least one stage to the interval block"
            builtBlockStages.any { it == null } ->
                "Check that every interval block stage's duration is a valid non-negative number"
            builtBlockStages.none { it!!.type == StageType.WORK && !it.isZeroDuration } ->
                "The interval block needs at least one Work stage with a duration greater than zero"
            else -> null
        }
        if (validationError != null) return

        val workout = Workout(
            id = workoutToEdit?.id ?: UUID.randomUUID().toString(),
            name = trimmedName,
            prepStage = prep!!,
            intervalBlockStages = builtBlockStages.map { it!! },
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

        FormLabel("Prep")
        FixedStageSection(prepState, locationGranted)

        Spacer(Modifier.height(AppDimensions.PaddingLarge))

        IntervalBlockSection(blockStages, locationGranted)

        Spacer(Modifier.height(AppDimensions.PaddingLarge))

        FormLabel("Cooldown")
        FixedStageSection(cooldownState, locationGranted)

        Spacer(Modifier.height(AppDimensions.PaddingLarge))

        FormLabel("Intervals")
        Text(
            "Number of times the block above repeats",
            color = OnDark.copy(alpha = 0.6f),
            fontSize = 14.sp
        )
        OutlinedTextField(
            value = intervalsText,
            onValueChange = { intervalsText = it.filter { c -> c.isDigit() } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(120.dp).height(56.dp)
        )

        Spacer(Modifier.height(AppDimensions.PaddingLarge))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                FormLabel("Final rest")
                Text(
                    "Include the block's trailing Rest stage(s) after the last interval",
                    color = OnDark.copy(alpha = 0.6f),
                    fontSize = 14.sp
                )
            }
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
// Fixed-stage form state (Prep / Cooldown) - unchanged shape from before
// the interval-block refactor.
// =========================================================================

class StageFormState(initialText: String, initialUnit: DisplayUnit) {
    var durationText by mutableStateOf(initialText)
    var unit by mutableStateOf(initialUnit)
}

@Composable
private fun rememberStageFormState(
    stage: WorkoutStage?,
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

@Composable
private fun FixedStageSection(state: StageFormState, locationGranted: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(AppDimensions.ButtonCornerRadius))
            .padding(AppDimensions.PaddingStandard)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.durationText,
                onValueChange = { state.durationText = it.filter { c -> c.isDigit() || c == '.' } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(100.dp).height(56.dp)
            )
            Spacer(Modifier.width(12.dp))
            DurationTypeToggle(
                durationType = state.unit.durationType,
                locationGranted = locationGranted,
                onSelectTime = { state.unit = DisplayUnit.SECONDS },
                onSelectDistance = { state.unit = DisplayUnit.METERS }
            )
            Spacer(Modifier.width(12.dp))
            UnitDropdown(unit = state.unit, onUnitChange = { state.unit = it })
        }
        if (!locationGranted) {
            Spacer(Modifier.height(4.dp))
            LocationPermissionCaption()
        }
    }
    Spacer(Modifier.height(AppDimensions.PaddingStandard))
}

// =========================================================================
// Interval block: dynamic list of WORK/REST stages
// =========================================================================

class BlockStageFormState(
    initialStageType: StageType,
    initialDisplayName: String,
    initialText: String,
    initialUnit: DisplayUnit
) {
    var stageType by mutableStateOf(initialStageType)
    var displayNameText by mutableStateOf(initialDisplayName)
    var durationText by mutableStateOf(initialText)
    var unit by mutableStateOf(initialUnit)
}

@Composable
private fun rememberBlockStageFormStates(
    existingStages: List<WorkoutStage>?
): androidx.compose.runtime.snapshots.SnapshotStateList<BlockStageFormState> {
    return remember {
        val initial = if (!existingStages.isNullOrEmpty()) {
            existingStages.map { stage ->
                val unit = initialDisplayUnitFor(stage)
                BlockStageFormState(
                    initialStageType = stage.type,
                    initialDisplayName = stage.displayName ?: "",
                    initialText = initialDurationTextFor(stage, unit),
                    initialUnit = unit
                )
            }
        } else {
            // Sensible default for a brand-new workout: one Work, one Rest --
            // mirrors the original single-Work/single-Rest design, so the
            // common simple case still starts from a familiar shape. Users
            // add more stages only if they want the new multi-stage capability.
            listOf(
                BlockStageFormState(StageType.WORK, "", "180", DisplayUnit.SECONDS),
                BlockStageFormState(StageType.REST, "", "60", DisplayUnit.SECONDS)
            )
        }
        mutableStateListOf(*initial.toTypedArray())
    }
}

@Composable
private fun IntervalBlockSection(
    blockStages: androidx.compose.runtime.snapshots.SnapshotStateList<BlockStageFormState>,
    locationGranted: Boolean
) {
    Column {
        FormLabel("Interval block")
        Text(
            "Repeats as a unit for however many Intervals you set below",
            color = OnDark.copy(alpha = 0.6f),
            fontSize = 14.sp
        )

        Spacer(Modifier.height(8.dp))

        blockStages.forEachIndexed { index, stageState ->
            BlockStageRow(
                stageState = stageState,
                stageNumber = index + 1,
                locationGranted = locationGranted,
                canRemove = blockStages.size > 1,
                onRemove = { blockStages.removeAt(index) }
            )
            Spacer(Modifier.height(AppDimensions.PaddingStandard))
        }

        val atMaxStages = blockStages.size >= Workout.MAX_INTERVAL_BLOCK_STAGES
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    blockStages.add(
                        BlockStageFormState(StageType.WORK, "", "30", DisplayUnit.SECONDS)
                    )
                },
                enabled = !atMaxStages,
                colors = ButtonDefaults.buttonColors(containerColor = AccentNeonBlue, contentColor = BackgroundDark)
            ) {
                Text("+ Add Stage", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "${blockStages.size} / ${Workout.MAX_INTERVAL_BLOCK_STAGES} stages",
                color = OnDark.copy(alpha = 0.6f),
                fontSize = 14.sp
            )
        }
        if (atMaxStages) {
            Text(
                "Maximum ${Workout.MAX_INTERVAL_BLOCK_STAGES} stages reached",
                color = DestructiveRed,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun BlockStageRow(
    stageState: BlockStageFormState,
    stageNumber: Int,
    locationGranted: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(AppDimensions.ButtonCornerRadius))
            .padding(AppDimensions.PaddingStandard)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Stage $stageNumber", color = OnDark.copy(alpha = 0.6f), fontSize = 14.sp)
            if (canRemove) {
                TextButton(onClick = onRemove) {
                    Text("Remove", color = DestructiveRed)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        StageTypeToggle(
            stageType = stageState.stageType,
            onSelect = { stageState.stageType = it }
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = stageState.displayNameText,
            onValueChange = { stageState.displayNameText = it },
            label = { Text("Display name (optional, e.g. \"Sprint\")") },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        )

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = stageState.durationText,
                onValueChange = { stageState.durationText = it.filter { c -> c.isDigit() || c == '.' } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(100.dp).height(56.dp)
            )
            Spacer(Modifier.width(12.dp))
            DurationTypeToggle(
                durationType = stageState.unit.durationType,
                locationGranted = locationGranted,
                onSelectTime = { stageState.unit = DisplayUnit.SECONDS },
                onSelectDistance = { stageState.unit = DisplayUnit.METERS }
            )
            Spacer(Modifier.width(12.dp))
            UnitDropdown(unit = stageState.unit, onUnitChange = { stageState.unit = it })
        }

        if (!locationGranted) {
            Spacer(Modifier.height(4.dp))
            LocationPermissionCaption()
        }
    }
}

@Composable
private fun StageTypeToggle(stageType: StageType, onSelect: (StageType) -> Unit) {
    Row {
        Button(
            onClick = { onSelect(StageType.WORK) },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (stageType == StageType.WORK) AccentBrightGreen else BackgroundDark,
                contentColor = if (stageType == StageType.WORK) BackgroundDark else OnDark
            )
        ) { Text("Work") }

        Spacer(Modifier.width(4.dp))

        Button(
            onClick = { onSelect(StageType.REST) },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (stageType == StageType.REST) AccentNeonBlue else BackgroundDark,
                contentColor = if (stageType == StageType.REST) BackgroundDark else OnDark
            )
        ) { Text("Rest") }
    }
}

// =========================================================================
// Shared duration-type / unit controls -- parametrized by plain value +
// setter (not a specific state class) so both FixedStageSection (Prep/
// Cooldown) and BlockStageRow (interval block) can reuse them.
// =========================================================================

@Composable
private fun DurationTypeToggle(
    durationType: DurationType,
    locationGranted: Boolean,
    onSelectTime: () -> Unit,
    onSelectDistance: () -> Unit
) {
    val isTime = durationType == DurationType.TIME_BASED
    val isDistance = !isTime

    Row {
        Button(
            onClick = { if (!isTime) onSelectTime() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isTime) AccentNeonBlue else BackgroundDark,
                contentColor = if (isTime) BackgroundDark else OnDark
            )
        ) { Text("Time") }

        Spacer(Modifier.width(4.dp))

        Button(
            onClick = { if (locationGranted) onSelectDistance() },
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
private fun UnitDropdown(unit: DisplayUnit, onUnitChange: (DisplayUnit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = if (unit.durationType == DurationType.TIME_BASED) {
        DisplayUnit.TIME_UNITS
    } else {
        DisplayUnit.DISTANCE_UNITS
    }

    Column {
        OutlinedButton(onClick = { expanded = true }) {
            Text(unit.label, color = OnDark)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onUnitChange(option)
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

    Row(verticalAlignment = Alignment.CenterVertically) {
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
