package com.example.intervaltimer.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Task 2. DEVIATION FROM BRIEF: RECORD_AUDIO is intentionally NOT requested
 * here (same finding as Session 4, reaffirmed in Session 6). AudioCueService
 * only plays synthesized tones via AudioTrack -- that needs no microphone
 * permission. The two permissions this app actually uses at runtime are
 * ACCESS_FINE_LOCATION and, on API 33+, POST_NOTIFICATIONS.
 *
 * Uses androidx.activity.result.contract, not Accompanist -- Accompanist
 * isn't a project dependency and this avoids adding one for a handful of
 * permission checks.
 */

fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

fun Context.hasNotificationPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

/**
 * Live location-permission status, re-checked on ON_RESUME so it reflects
 * the user granting/revoking it from system Settings while backgrounded.
 * WorkoutCreatorScreen uses this to disable the Distance option.
 */
@Composable
fun rememberLocationPermissionGranted(): State<Boolean> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val granted = remember { mutableStateOf(context.hasLocationPermission()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted.value = context.hasLocationPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return granted
}

/** Wraps a single-permission ActivityResultLauncher so callers just call .request(). */
class PermissionLauncher internal constructor(
    private val launch: (String) -> Unit,
    val permission: String
) {
    fun request() = launch(permission)
}

@Composable
fun rememberPermissionLauncher(permission: String, onResult: (Boolean) -> Unit): PermissionLauncher {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return remember(permission) { PermissionLauncher({ launcher.launch(it) }, permission) }
}

/** Explains WHY before the system prompt appears, rather than firing it with no context. */
@Composable
fun PermissionRationaleDialog(
    title: String,
    rationale: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(rationale) },
        confirmButton = { Button(onClick = onConfirm) { Text("Continue") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Not now") } }
    )
}

object PermissionRationale {
    const val LOCATION_TITLE = "Location access"
    const val LOCATION_MESSAGE =
        "Interval Timer uses GPS to measure distance during distance-based Work/Rest stages. " +
            "Without it, only time-based stages will work."

    const val NOTIFICATIONS_TITLE = "Notifications"
    const val NOTIFICATIONS_MESSAGE =
        "Interval Timer shows a persistent notification with Pause/Resume/End controls while " +
            "a workout is running, so you can control it without unlocking your phone."
}
