package com.example.intervaltimer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.pm.ServiceInfoCompat
import com.example.intervaltimer.audio.AudioCueService
import com.example.intervaltimer.data.StageType
import com.example.intervaltimer.data.Workout
import com.example.intervaltimer.domain.ExecutionState
import com.example.intervaltimer.domain.GPSTracker
import com.example.intervaltimer.domain.WorkoutExecutor
import com.example.intervaltimer.domain.WorkoutProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps a workout running while the app is
 * backgrounded or the screen is locked (Section 5.8, 7.5, 7.6).
 *
 * Owns the [WorkoutExecutor] for the active session, wires it to
 * [GPSTracker] (distance-based stages) and [AudioCueService] (countdown /
 * transition / completion tones), maintains a partial wake lock so the
 * CPU keeps ticking, and surfaces Pause/Resume/End controls both through
 * its public method API (for a bound Activity/ViewModel) and through the
 * persistent notification (for when the app itself isn't in the
 * foreground).
 *
 * Screen-on behavior: this service only holds a PARTIAL_WAKE_LOCK (CPU
 * awake, screen may still lock per user/system settings — a Service has no
 * window to keep on). The actual FLAG_KEEP_SCREEN_ON call belongs to
 * ActiveWorkoutScreen's Activity (Session 7), which should set it while
 * this service reports RUNNING and clear it otherwise.
 */
class WorkoutService : Service() {

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): WorkoutService = this@WorkoutService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var executor: WorkoutExecutor? = null
    private lateinit var gpsTracker: GPSTracker
    private lateinit var audioCueService: AudioCueService
    private var wakeLock: PowerManager.WakeLock? = null

    private var progressCollectorJob: Job? = null
    private var distanceCollectorJob: Job? = null

    private val _progress = MutableStateFlow(WorkoutProgress())

    /** Bound UI (WorkoutViewModel, Session 7) collects this for live display. */
    val progress: StateFlow<WorkoutProgress> = _progress.asStateFlow()

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate() {
        super.onCreate()
        audioCueService = AudioCueService(applicationContext)
        gpsTracker = GPSTracker(applicationContext)
        createNotificationChannel()
    }

    /**
     * Handles both direct binder calls (startWorkout etc., below) and
     * notification action taps, which arrive as Intents with an ACTION_*
     * extra since a notification button can't call a Kotlin method
     * directly — it can only fire a PendingIntent back into this service.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseWorkout()
            ACTION_RESUME -> resumeWorkout()
            ACTION_END -> endWorkout()
        }
        // START_NOT_STICKY: if the system kills this process, we don't want
        // an empty restart with no workout attached — restoration is
        // handled explicitly via WorkoutStatePersistence + a user-initiated
        // "Resume previous workout?" prompt in the UI instead (FR-16).
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        progressCollectorJob?.cancel()
        distanceCollectorJob?.cancel()
        executor?.release()
        gpsTracker.stopTracking()
        audioCueService.release()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    // =====================================================================
    // Public control surface (Task 1)
    // =====================================================================

    /** Starts a brand-new workout session from the beginning. */
    fun startWorkout(workout: Workout) {
        val newExecutor = WorkoutExecutor(workout, serviceScope)
        executor = newExecutor
        wireExecutorCollectors(newExecutor)

        WorkoutStatePersistence.saveActiveWorkout(applicationContext, workout)
        acquireWakeLock()
        startForegroundNotification(buildNotification(_progress.value))

        newExecutor.start()
    }

    /**
     * Restores a workout that was in progress when the app/service was
     * previously killed (FR-16), jumping back into the stage it was on
     * rather than restarting from Prep.
     */
    fun resumeFromPersistedState(state: WorkoutStatePersistence.PersistedState) {
        val newExecutor = WorkoutExecutor(state.workout, serviceScope)
        executor = newExecutor
        wireExecutorCollectors(newExecutor)

        acquireWakeLock()
        startForegroundNotification(buildNotification(_progress.value))

        newExecutor.start(fromStageIndex = state.stageIndex)
    }

    /** Exposes any persisted-but-not-yet-resumed session, for the UI to offer Resume/Restart. */
    fun findPersistedWorkout(): WorkoutStatePersistence.PersistedState? =
        WorkoutStatePersistence.load(applicationContext)

    fun pauseWorkout() {
        executor?.pause()
        gpsTracker.stopTracking()
        // Partial wake lock released while paused — nothing time-sensitive
        // is happening, and this avoids unnecessary battery drain if the
        // user leaves a workout paused for a while.
        releaseWakeLock()
    }

    fun resumeWorkout() {
        acquireWakeLock()
        maybeStartGpsForCurrentStage()
        executor?.resume()
    }

    /** Cancels the in-flight stage and begins the workout again from Prep. */
    fun restartWorkout() {
        acquireWakeLock()
        executor?.restart()
        maybeStartGpsForCurrentStage()
    }

    /** Terminates the workout early (FR-13); UI must confirm with the user before calling this. */
    fun endWorkout() {
        executor?.end()
        gpsTracker.stopTracking()
        releaseWakeLock()
        WorkoutStatePersistence.clear(applicationContext)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // =====================================================================
    // Executor <-> GPS <-> Audio wiring
    // =====================================================================
    //
    // Not itemized in the original task list, but necessary: without this,
    // WorkoutExecutor's distance-based stages never receive GPS data, and
    // AudioCueService's tones never actually get triggered by progress
    // changes. This is the integration point promised back in Session 3.

    private fun wireExecutorCollectors(executor: WorkoutExecutor) {
        progressCollectorJob?.cancel()
        distanceCollectorJob?.cancel()

        var lastStageId: String? = null
        var lastCountdownValue = -1
        var hasFiredCompletion = false

        progressCollectorJob = serviceScope.launch {
            executor.progress.collect { snapshot ->
                _progress.value = snapshot

                // Stage transition tone: fires when currentStage changes,
                // but not for the very first stage of the workout (no prior
                // stage to transition FROM).
                val stageId = snapshot.currentStage?.id
                if (lastStageId != null && stageId != lastStageId) {
                    audioCueService.playStageTransitionTone()
                }
                lastStageId = stageId

                // Countdown tone: fires once per distinct countdown value
                // (3, 2, 1), not once per progress tick -- distance-based
                // stages poll every 200ms and would otherwise spam tones.
                if (snapshot.isCountdown &&
                    snapshot.countdownValue in 1..3 &&
                    snapshot.countdownValue != lastCountdownValue
                ) {
                    audioCueService.playCountdownTone()
                }
                lastCountdownValue = snapshot.countdownValue

                if (snapshot.isWorkoutComplete && !hasFiredCompletion) {
                    hasFiredCompletion = true
                    audioCueService.playWorkoutCompletionSignal()
                    WorkoutStatePersistence.clear(applicationContext)
                    releaseWakeLock()
                    gpsTracker.stopTracking()
                }

                maybeStartGpsForCurrentStage()
                WorkoutStatePersistence.updateProgressSnapshot(applicationContext, snapshot)
                updateNotification(snapshot)
            }
        }

        distanceCollectorJob = serviceScope.launch {
            gpsTracker.distanceFlow.collect { metersTravelled ->
                executor.distanceTravelledCallback(metersTravelled)
            }
        }
    }

    /** Starts/stops GPS tracking to match whether the current stage actually needs it. */
    private fun maybeStartGpsForCurrentStage() {
        val stage = _progress.value.currentStage ?: return
        val needsGps = stage.durationType == com.example.intervaltimer.data.DurationType.DISTANCE_BASED &&
            _progress.value.executionState == ExecutionState.RUNNING

        if (needsGps && !gpsTracker.isTracking) {
            gpsTracker.resetDistance()
            gpsTracker.startTracking()
        } else if (!needsGps && gpsTracker.isTracking) {
            gpsTracker.stopTracking()
        }
    }

    // =====================================================================
    // Wake lock (CPU only — see class doc for screen-on division of responsibility)
    // =====================================================================

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
            // Safety timeout: if release() is ever missed due to a bug or
            // crash, this guarantees the lock doesn't drain the battery
            // indefinitely. A real workout should never run this long.
            acquire(MAX_WAKE_LOCK_DURATION_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // =====================================================================
    // Notification
    // =====================================================================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active Workout",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows workout progress and Pause/Resume/End controls"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundNotification(notification: Notification) {
        // API 34 requires foreground service TYPE to be declared both in the
        // manifest and here; ServiceCompat handles the pre-29 no-op case.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfoCompat.FOREGROUND_SERVICE_TYPE_LOCATION
        )
    }

    private fun updateNotification(progress: WorkoutProgress) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(progress))
    }

    /**
     * Standard NotificationCompat actions, not a custom RemoteViews layout.
     *
     * Worth flagging: Section 7.3's 56dp/28sp button sizing is NOT
     * achievable here — the Android system, not the app, renders
     * notification action buttons, and it doesn't expose per-app control
     * over their touch-target size. That level of sizing control is only
     * real for in-app UI (ActiveWorkoutScreen, Session 7), which is where
     * FR-21 actually gets satisfied. Likewise, "dark theme" for a stock
     * notification follows the device's system dark mode automatically;
     * it isn't something this Builder can force independently. A fully
     * custom look for the notification itself would require a
     * RemoteViews-based layout — straightforward to add later if the
     * default system styling isn't good enough in practice.
     */
    private fun buildNotification(progress: WorkoutProgress): Notification {
        val stageName = progress.currentStage?.type?.let(::displayNameForStage) ?: "Interval Timer"
        val subtext = buildSubtext(progress)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent().setClassName(packageName, MAIN_ACTIVITY_CLASS_NAME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(stageName)
            .setContentText(subtext)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtext))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(progress.executionState == ExecutionState.RUNNING)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)

        when (progress.executionState) {
            ExecutionState.RUNNING -> builder.addAction(
                android.R.drawable.ic_media_pause, "Pause", actionPendingIntent(ACTION_PAUSE)
            )
            ExecutionState.PAUSED -> builder.addAction(
                android.R.drawable.ic_media_play, "Resume", actionPendingIntent(ACTION_RESUME)
            )
            else -> Unit
        }
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel, "End", actionPendingIntent(ACTION_END)
        )

        return builder.build()
    }

    private fun buildSubtext(progress: WorkoutProgress): String {
        val stage = progress.currentStage ?: return "Ready to start"
        val remainingLabel = when (stage.durationType) {
            com.example.intervaltimer.data.DurationType.TIME_BASED ->
                formatSeconds(progress.remaining.toLong())
            com.example.intervaltimer.data.DurationType.DISTANCE_BASED ->
                "${progress.remaining.toInt()} m remaining"
        }
        val intervalLabel = if (progress.currentInterval > 0) {
            " • Interval ${progress.currentInterval}/${progress.totalIntervals}"
        } else ""
        return "$remainingLabel$intervalLabel"
    }

    private fun formatSeconds(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d remaining", minutes, seconds)
    }

    private fun displayNameForStage(type: StageType): String = when (type) {
        StageType.PREP -> "Prep"
        StageType.WORK -> "Work"
        StageType.REST -> "Rest"
        StageType.COOLDOWN -> "Cooldown"
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, WorkoutService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val CHANNEL_ID = "workout_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PAUSE = "com.example.intervaltimer.action.PAUSE"
        const val ACTION_RESUME = "com.example.intervaltimer.action.RESUME"
        const val ACTION_END = "com.example.intervaltimer.action.END"

        /**
         * Referenced by string, not by class literal (MainActivity::class),
         * because MainActivity doesn't exist yet — it's built in Session 6.
         * String-based Intent resolution compiles fine now and resolves at
         * runtime once MainActivity exists; update here if the package/class
         * name changes.
         */
        private const val MAIN_ACTIVITY_CLASS_NAME = "com.example.intervaltimer.presentation.MainActivity"

        private const val WAKE_LOCK_TAG = "IntervalTimer:WorkoutWakeLock"
        private const val MAX_WAKE_LOCK_DURATION_MS = 3 * 60 * 60 * 1000L // 3 hours safety cap
    }
}
