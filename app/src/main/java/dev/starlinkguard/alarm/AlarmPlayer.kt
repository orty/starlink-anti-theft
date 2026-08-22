package dev.starlinkguard.alarm

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Makes the phone scream.
 *
 * The important detail is the audio *usage*: [AudioAttributes.USAGE_ALARM] routes playback to
 * the alarm stream, and the alarm stream is not one of the streams the ringer switch silences
 * on stock Android. Silent and vibrate modes therefore do not affect it. Do Not Disturb is the
 * real obstacle, and that is handled separately — see [applyDndBypass].
 */
class AlarmPlayer(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var previousAlarmVolume: Int? = null
    private var previousInterruptionFilter: Int? = null
    private var active = false

    /**
     * Whether the alarm is currently raised.
     *
     * Deliberately tracks the alarm state rather than the existence of a [MediaPlayer]: if every
     * candidate sound fails the phone still vibrates and the alarm is still going, and the
     * service must not treat that as "not started" and try to raise it again on the next poll.
     */
    val isPlaying: Boolean get() = active

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * @param soundUri the user's chosen sound, or null for the system default. It is only the
     *   first thing tried — see [AlarmSound] for why the alarm never depends on it working.
     */
    fun start(vibrate: Boolean, soundUri: Uri? = null) {
        if (isPlaying) return
        active = true

        applyDndBypass()
        raiseAlarmVolume()
        requestAudioFocus()

        player = openFirstPlayable(AlarmSound.candidates(soundUri))
        if (player == null) {
            // Vibration below is now the only signal, but the alarm is still considered raised.
            Log.e(TAG, "no alarm sound could be played")
        }

        if (vibrate) startVibration()
    }

    fun stop() {
        active = false
        runCatching {
            player?.apply {
                if (isPlaying) stop()
                release()
            }
        }.onFailure { Log.w(TAG, "could not stop the alarm sound", it) }
        player = null

        stopVibration()
        abandonAudioFocus()
        restoreAlarmVolume()
        restoreInterruptionFilter()
    }

    /**
     * Plays the first candidate that works, so a missing or unreadable custom sound degrades to
     * the system alarm instead of to silence.
     */
    private fun openFirstPlayable(candidates: List<Uri>): MediaPlayer? {
        for (uri in candidates) {
            val candidate = MediaPlayer()
            val started = runCatching {
                candidate.setAudioAttributes(attributes)
                candidate.setDataSource(context, uri)
                candidate.isLooping = true
                candidate.prepare()
                candidate.start()
            }.onFailure {
                Log.w(TAG, "alarm sound $uri could not be played, falling back", it)
            }.isSuccess

            if (started) return candidate
            runCatching { candidate.release() }
        }
        return null
    }

    private fun raiseAlarmVolume() {
        // Some devices run a fixed-volume policy where this is a no-op; do not fight it.
        if (audioManager.isVolumeFixed) return
        runCatching {
            previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }.onFailure { Log.w(TAG, "could not raise the alarm volume", it) }
    }

    private fun restoreAlarmVolume() {
        val previous = previousAlarmVolume ?: return
        previousAlarmVolume = null
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0) }
    }

    /**
     * Lifts Do Not Disturb for the duration of the alarm, if the user has granted policy
     * access. Without that grant this is a no-op and the alarm channel's own bypass flag is
     * the only defence.
     */
    private fun applyDndBypass() {
        if (!notificationManager.isNotificationPolicyAccessGranted) return
        runCatching {
            val current = notificationManager.currentInterruptionFilter
            if (current != NotificationManager.INTERRUPTION_FILTER_ALL) {
                previousInterruptionFilter = current
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            }
        }.onFailure { Log.w(TAG, "could not relax Do Not Disturb", it) }
    }

    private fun restoreInterruptionFilter() {
        val previous = previousInterruptionFilter ?: return
        previousInterruptionFilter = null
        if (!notificationManager.isNotificationPolicyAccessGranted) return
        runCatching { notificationManager.setInterruptionFilter(previous) }
    }

    private fun requestAudioFocus() {
        // Without focus a music app will duck the alarm to a whisper.
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .build()
        focusRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
    }

    private fun abandonAudioFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { audioManager.abandonAudioFocusRequest(request) }
    }

    private fun vibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    private fun startVibration() {
        val vibrator = vibrator() ?: return
        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0, 800, 400)
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), attributes)
        }.onFailure { Log.w(TAG, "could not vibrate", it) }
    }

    private fun stopVibration() {
        runCatching { vibrator()?.cancel() }
    }

    private companion object {
        const val TAG = "AlarmPlayer"
    }
}
