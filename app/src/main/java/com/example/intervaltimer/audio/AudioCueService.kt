package com.example.intervaltimer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Plays the workout's audible cues (Section 6) so they overlay music or
 * podcasts without pausing or ducking them — the app is designed to be used
 * with the device stowed while listening to other audio (Section 1).
 *
 * IMPLEMENTATION NOTE: [android.media.ToneGenerator] was considered per the
 * task brief, but it only exposes a fixed catalog of DTMF/supervisory tones
 * (e.g. TONE_DTMF_1, TONE_SUP_DIAL) — it cannot generate an arbitrary
 * frequency like 800 Hz or 1000 Hz. Since Section 6.3 requires those exact
 * frequencies, this class synthesizes tones directly with [AudioTrack]
 * (16-bit PCM sine wave), which is the "OR" fallback the brief allowed for.
 *
 * Audio focus is requested as AUDIOFOCUS_GAIN_TRANSIENT (not exclusive) for
 * the ~200-500ms a tone plays, then immediately abandoned — this is what
 * lets other apps' audio continue uninterrupted (FR-10).
 */
class AudioCueService(private val context: Context) {

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Serializes tone playback so overlapping calls don't create competing AudioTrack/focus requests. */
    private val toneMutex = Mutex()

    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile private var volumePercent: Int = DEFAULT_VOLUME_PERCENT

    // =====================================================================
    // Public API (Task 1)
    // =====================================================================

    /** FR-9: short tone marking each second during a stage's final countdown (800 Hz, 200ms). */
    fun playCountdownTone() {
        serviceScope.launch {
            playTone(frequencyHz = SHORT_TONE_FREQUENCY_HZ, durationMs = SHORT_TONE_DURATION_MS)
        }
    }

    /** FR-9: long tone marking a stage transition (1000 Hz, 500ms). */
    fun playStageTransitionTone() {
        serviceScope.launch {
            playTone(frequencyHz = LONG_TONE_FREQUENCY_HZ, durationMs = LONG_TONE_DURATION_MS)
        }
    }

    /** FR-9: three consecutive long tones signaling the entire workout has ended. */
    fun playWorkoutCompletionSignal() {
        serviceScope.launch {
            repeat(COMPLETION_TONE_COUNT) {
                playTone(frequencyHz = LONG_TONE_FREQUENCY_HZ, durationMs = LONG_TONE_DURATION_MS)
                delay(GAP_BETWEEN_COMPLETION_TONES_MS)
            }
        }
    }

    /**
     * Sets cue volume independently of the user's media/alarm volume elsewhere
     * on the device. [level] is 0-100; out-of-range values are clamped.
     */
    fun setVolume(level: Int) {
        volumePercent = level.coerceIn(0, 100)

        try {
            val maxStreamVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val targetStreamVolume = ((volumePercent / 100.0) * maxStreamVolume)
                .roundToInt()
                .coerceIn(0, maxStreamVolume)

            // flags = 0 (no FLAG_SHOW_UI): adjusting this shouldn't pop up the
            // system volume overlay over whatever the user is looking at.
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetStreamVolume, 0)
        } catch (e: SecurityException) {
            // Some OEMs restrict STREAM_ALARM changes (e.g. Do Not Disturb policies).
            Log.w(TAG, "Unable to set STREAM_ALARM volume", e)
        }
    }

    /** Releases coroutine resources and any held audio focus. Call from onDestroy/service teardown. */
    fun release() {
        serviceScope.cancel()
        abandonAudioFocus()
    }

    // =====================================================================
    // Tone playback
    // =====================================================================

    private suspend fun playTone(frequencyHz: Int, durationMs: Int) = toneMutex.withLock {
        withContext(Dispatchers.Default) {
            if (!requestAudioFocus()) {
                Log.w(TAG, "Audio focus not granted; skipping tone at ${frequencyHz}Hz")
                return@withContext
            }

            var track: AudioTrack? = null
            try {
                track = buildToneTrack(frequencyHz, durationMs)
                track.play()
                // Block this background coroutine until playback genuinely
                // finishes so a fast countdown (T-3, T-2, T-1) can't stack
                // overlapping AudioTrack instances.
                delay(durationMs.toLong() + PLAYBACK_SAFETY_MARGIN_MS)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play ${frequencyHz}Hz tone", e)
            } finally {
                try {
                    track?.stop()
                } catch (e: IllegalStateException) {
                    // Already stopped/released — safe to ignore.
                }
                track?.release()
                abandonAudioFocus()
            }
        }
    }

    /** Synthesizes [durationMs] of a [frequencyHz] sine wave as 16-bit mono PCM. */
    private fun buildToneTrack(frequencyHz: Int, durationMs: Int): AudioTrack {
        val sampleCount = (SAMPLE_RATE_HZ * durationMs / 1000.0).toInt()
        val samples = ShortArray(sampleCount)
        val amplitude = Short.MAX_VALUE * (volumePercent / 100.0)

        // Short linear fade-in/out prevents an audible click/pop at the
        // start and end of each tone.
        val fadeSampleCount = (SAMPLE_RATE_HZ * FADE_DURATION_SECONDS).toInt().coerceAtMost(sampleCount / 2)

        for (i in 0 until sampleCount) {
            val angle = 2.0 * Math.PI * i * frequencyHz / SAMPLE_RATE_HZ
            var value = sin(angle) * amplitude

            val fadeMultiplier = when {
                i < fadeSampleCount -> i.toDouble() / fadeSampleCount
                i >= sampleCount - fadeSampleCount -> (sampleCount - i).toDouble() / fadeSampleCount
                else -> 1.0
            }
            value *= fadeMultiplier

            samples[i] = value.toInt().toShort()
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM) // maps to STREAM_ALARM behavior
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val bufferSizeBytes = sampleCount * 2 // 16-bit samples = 2 bytes each

        val track = AudioTrack(
            audioAttributes,
            audioFormat,
            bufferSizeBytes,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.write(samples, 0, samples.size)
        return track
    }

    // =====================================================================
    // Audio focus (Task 2)
    // =====================================================================

    /** Requests transient, non-exclusive focus so other apps' playback is never paused (FR-10). */
    private fun requestAudioFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { /* no-op: cue is fire-and-forget, nothing to resume */ }
            .build()

        audioFocusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    companion object {
        private const val TAG = "AudioCueService"

        // Section 6.3 tone specifications
        private const val SHORT_TONE_FREQUENCY_HZ = 800
        private const val SHORT_TONE_DURATION_MS = 200
        private const val LONG_TONE_FREQUENCY_HZ = 1000
        private const val LONG_TONE_DURATION_MS = 500
        private const val COMPLETION_TONE_COUNT = 3
        private const val GAP_BETWEEN_COMPLETION_TONES_MS = 150L

        private const val SAMPLE_RATE_HZ = 44100
        private const val FADE_DURATION_SECONDS = 0.01 // 10ms fade in/out, avoids click artifacts
        private const val PLAYBACK_SAFETY_MARGIN_MS = 50L
        private const val DEFAULT_VOLUME_PERCENT = 80
    }
}
