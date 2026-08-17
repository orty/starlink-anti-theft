package dev.starlinkguard.alarm

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
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

    val isPlaying: Boolean get() = player != null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start(vibrate: Boolean) {
        if (isPlaying) return

        applyDndBypass()
        raiseAlarmVolume()
        requestAudioFocus()

        val uri = alarmUri()
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure {
            Log.e(TAG, "could not start the alarm sound", it)
        }.getOrNull()

        if (vibrate) startVibration()
    }

    fun stop() {
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
     * Picks a sound that is guaranteed to exist.
     *
     * The default alarm can be unset on some devices, in which case the ringtone and then the
     * notification sound are used instead. An alarm with nothing to play is worthless.
     */
    private fun alarmUri(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

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
