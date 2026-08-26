package com.example.intervaltimer.domain

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tracks cumulative distance travelled (meters) for DISTANCE_BASED workout
 * stages, using FusedLocationProviderClient. Distance is computed with the
 * Haversine formula between consecutive accepted GPS fixes.
 *
 * This class does NOT request runtime permissions itself (Section 5.6,
 * FR-17/18) — the UI layer (Session 8) is responsible for that. If
 * permission isn't granted when [startTracking] is called, tracking simply
 * does not start and [isTracking] remains false.
 *
 * Distance updates are pushed to [distanceFlow], which the caller (e.g. the
 * WorkoutService, Session 5) forwards into
 * [com.example.intervaltimer.domain.WorkoutExecutor.distanceTravelledCallback]
 * so the executor's distance-based stage logic can react to it.
 */
class GPSTracker(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val _distanceFlow = MutableStateFlow(0.0)

    /** Cumulative distance (meters) since the last [resetDistance] call. */
    val distanceFlow: StateFlow<Double> = _distanceFlow.asStateFlow()

    /**
     * Reflects whether the last known GPS fix is recent enough to trust.
     * Exposed so the UI can show a "GPS signal lost" indicator; the
     * distance calculation itself already pauses automatically (see
     * [handleNewLocation]) without needing this to be observed.
     */
    private val _signalAvailable = MutableStateFlow(true)
    val signalAvailable: StateFlow<Boolean> = _signalAvailable.asStateFlow()

    var isTracking: Boolean = false
        private set

    private var lastAcceptedLocation: Location? = null
    /** Set by resetDistance(); see handleNewLocation() for why this exists. */
    private var baselineValidAfterElapsedRealtimeNanos: Long = 0L
    private var accumulatedDistanceMeters: Double = 0.0
    private var lastFixTimestampMs: Long = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            handleNewLocation(location)
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            _signalAvailable.value = availability.isLocationAvailable
        }
    }

    /**
     * Begins location updates. No-op if already tracking or if
     * ACCESS_FINE_LOCATION has not been granted.
     *
     * @return true if tracking actually started, false otherwise (e.g.
     * missing permission) — lets the caller decide how to inform the user.
     */
    @SuppressLint("MissingPermission") // permission checked explicitly below
    fun startTracking(): Boolean {
        if (isTracking) return true
        if (!hasLocationPermission()) return false

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())

        isTracking = true
        lastFixTimestampMs = System.currentTimeMillis()
        _signalAvailable.value = true
        return true
    }

    /** Ends location updates. Accumulated distance is preserved until [resetDistance] is called. */
    fun stopTracking() {
        if (!isTracking) return
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isTracking = false
        lastAcceptedLocation = null
    }

    /** Clears accumulated distance, e.g. when a new DISTANCE_BASED stage begins. */
    fun resetDistance() {
        accumulatedDistanceMeters = 0.0
        _distanceFlow.value = 0.0
        lastAcceptedLocation = null
        // Authoritative "this new measurement period starts NOW" marker.
        // A fix computed before this moment cannot represent movement
        // within this stage, by definition -- see handleNewLocation().
        baselineValidAfterElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    // =====================================================================
    // Distance calculation
    // =====================================================================

    private fun handleNewLocation(newLocation: Location) {
        val now = System.currentTimeMillis()
        val gapSinceLastFixMs = now - lastFixTimestampMs
        lastFixTimestampMs = now

        val previous = lastAcceptedLocation

        if (previous == null) {
            // New stage's starting position (resetDistance() was just
            // called). FusedLocationProviderClient commonly delivers an
            // already-cached last-known-location as the very FIRST callback
            // after requestLocationUpdates() -- sometimes from minutes
            // earlier, or from wherever the device was during a PREVIOUS
            // stage, not where it actually is right now. Accepting that
            // stale fix as the baseline unconditionally, then comparing it
            // against the next genuinely fresh fix moments later, produced
            // a large spurious "distance" reading almost instantly (e.g. a
            // 70m stage reporting ~30m travelled before the user had
            // actually moved).
            //
            // Fix: only accept a fix as the new baseline if it was computed
            // AT OR AFTER the exact moment resetDistance() declared this
            // measurement period began -- an exact correctness check
            // against our own authoritative timestamp, not a guessed
            // freshness window. A fix from before that moment is discarded
            // outright; we simply wait for the next callback.
            if (newLocation.elapsedRealtimeNanos < baselineValidAfterElapsedRealtimeNanos) {
                return
            }

            lastAcceptedLocation = newLocation
            _signalAvailable.value = true
            return
        }

        if (gapSinceLastFixMs > SIGNAL_LOSS_THRESHOLD_MS) {
            // GPS signal was lost and just reacquired mid-stage (Section
            // 5.6, FR-18) -- unlike the new-stage case above, there's no
            // authoritative "this is when reacquisition should count from"
            // timestamp available here, only an inferred gap. A threshold
            // heuristic is the right tool for this specific case, even
            // though an exact check was better for the new-stage case.
            val fixAgeMs = (SystemClock.elapsedRealtimeNanos() - newLocation.elapsedRealtimeNanos) / 1_000_000
            if (fixAgeMs > MAX_REACQUISITION_FIX_AGE_MS) {
                return
            }

            lastAcceptedLocation = newLocation
            _signalAvailable.value = true
            return
        }

        // Discard fixes with poor accuracy rather than let them inject noise.
        if (newLocation.accuracy > MAX_ACCEPTABLE_ACCURACY_METERS) {
            return
        }

        val segmentMeters = haversineDistanceMeters(
            previous.latitude, previous.longitude,
            newLocation.latitude, newLocation.longitude
        )

        // Below our configured min-distance, treat as GPS jitter and ignore
        // (mirrors the min-update-distance filter already set on the
        // LocationRequest, applied again here for fixes that still slip
        // through slightly under threshold).
        if (segmentMeters >= MIN_DISTANCE_METERS) {
            accumulatedDistanceMeters += segmentMeters
            _distanceFlow.value = accumulatedDistanceMeters
            lastAcceptedLocation = newLocation
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Haversine great-circle distance between two lat/lng points, in
     * meters. Accurate enough for on-foot/cycling interval distances;
     * does not account for elevation change.
     */
    private fun haversineDistanceMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val FASTEST_INTERVAL_MS = 500L
        private const val MIN_DISTANCE_METERS = 2.0f
        private const val MAX_ACCEPTABLE_ACCURACY_METERS = 20f
        private const val SIGNAL_LOSS_THRESHOLD_MS = 5000L
        /** A reacquisition fix older than this (per its own elapsedRealtimeNanos) is treated as stale, not the device's actual current position -- used only for the signal-loss case, which has no better reference point available. */
        private const val MAX_REACQUISITION_FIX_AGE_MS = 2000L
        private const val EARTH_RADIUS_METERS = 6371000.0
    }
}
