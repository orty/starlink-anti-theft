package dev.starlinkguard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.starlinkguard.core.detect.DetectorSnapshot
import dev.starlinkguard.core.detect.Thresholds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Everything the user can configure, plus the bits of state that must survive a reboot. */
data class AppSettings(
    val thresholds: Thresholds = Thresholds(),
    val armed: Boolean = false,
    val webhookEnabled: Boolean = false,
    val webhookUrl: String = "",
    val alarmMaxDurationSec: Int = 300,
    val vibrateOnAlarm: Boolean = true,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val POLL_INTERVAL = intPreferencesKey("poll_interval_sec")
        val SUDDEN_WINDOW = intPreferencesKey("sudden_window_sec")
        val AZIMUTH = floatPreferencesKey("azimuth_deg")
        val ELEVATION = floatPreferencesKey("elevation_deg")
        val TILT = floatPreferencesKey("tilt_deg")
        val GPS_METERS = doublePreferencesKey("gps_meters")
        val CONFIRM_SAMPLES = intPreferencesKey("confirm_samples")
        val ARMING_GRACE = intPreferencesKey("arming_grace_sec")
        val SUPPRESS_ACTUATING = booleanPreferencesKey("suppress_while_actuating")
        val REQUIRE_CONVERGED = booleanPreferencesKey("require_converged_attitude")
        val GPS_ENABLED = booleanPreferencesKey("gps_enabled")
        val DISH_ALERTS = booleanPreferencesKey("use_dish_moved_alerts")

        val ARMED = booleanPreferencesKey("armed")
        val WEBHOOK_ENABLED = booleanPreferencesKey("webhook_enabled")
        val WEBHOOK_URL = stringPreferencesKey("webhook_url")
        val ALARM_MAX_DURATION = intPreferencesKey("alarm_max_duration_sec")
        val VIBRATE = booleanPreferencesKey("vibrate_on_alarm")

        val DETECTOR_SNAPSHOT = stringPreferencesKey("detector_snapshot")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val defaults = Thresholds()
        AppSettings(
            thresholds = Thresholds(
                pollIntervalSec = prefs[Keys.POLL_INTERVAL] ?: defaults.pollIntervalSec,
                suddenWindowSec = prefs[Keys.SUDDEN_WINDOW] ?: defaults.suddenWindowSec,
                azimuthDeg = prefs[Keys.AZIMUTH] ?: defaults.azimuthDeg,
                elevationDeg = prefs[Keys.ELEVATION] ?: defaults.elevationDeg,
                tiltDeg = prefs[Keys.TILT] ?: defaults.tiltDeg,
                gpsMeters = prefs[Keys.GPS_METERS] ?: defaults.gpsMeters,
                confirmSamples = prefs[Keys.CONFIRM_SAMPLES] ?: defaults.confirmSamples,
                armingGraceSec = prefs[Keys.ARMING_GRACE] ?: defaults.armingGraceSec,
                suppressWhileActuating = prefs[Keys.SUPPRESS_ACTUATING] ?: defaults.suppressWhileActuating,
                requireConvergedAttitude = prefs[Keys.REQUIRE_CONVERGED] ?: defaults.requireConvergedAttitude,
                gpsEnabled = prefs[Keys.GPS_ENABLED] ?: defaults.gpsEnabled,
                useDishMovedAlerts = prefs[Keys.DISH_ALERTS] ?: defaults.useDishMovedAlerts,
            ),
            armed = prefs[Keys.ARMED] ?: false,
            webhookEnabled = prefs[Keys.WEBHOOK_ENABLED] ?: false,
            webhookUrl = prefs[Keys.WEBHOOK_URL].orEmpty(),
            alarmMaxDurationSec = prefs[Keys.ALARM_MAX_DURATION] ?: 300,
            vibrateOnAlarm = prefs[Keys.VIBRATE] ?: true,
        )
    }

    suspend fun updateThresholds(transform: (Thresholds) -> Thresholds) {
        context.dataStore.edit { prefs ->
            val defaults = Thresholds()
            val current = Thresholds(
                pollIntervalSec = prefs[Keys.POLL_INTERVAL] ?: defaults.pollIntervalSec,
                suddenWindowSec = prefs[Keys.SUDDEN_WINDOW] ?: defaults.suddenWindowSec,
                azimuthDeg = prefs[Keys.AZIMUTH] ?: defaults.azimuthDeg,
                elevationDeg = prefs[Keys.ELEVATION] ?: defaults.elevationDeg,
                tiltDeg = prefs[Keys.TILT] ?: defaults.tiltDeg,
                gpsMeters = prefs[Keys.GPS_METERS] ?: defaults.gpsMeters,
                confirmSamples = prefs[Keys.CONFIRM_SAMPLES] ?: defaults.confirmSamples,
                armingGraceSec = prefs[Keys.ARMING_GRACE] ?: defaults.armingGraceSec,
                suppressWhileActuating = prefs[Keys.SUPPRESS_ACTUATING] ?: defaults.suppressWhileActuating,
                requireConvergedAttitude = prefs[Keys.REQUIRE_CONVERGED] ?: defaults.requireConvergedAttitude,
                gpsEnabled = prefs[Keys.GPS_ENABLED] ?: defaults.gpsEnabled,
                useDishMovedAlerts = prefs[Keys.DISH_ALERTS] ?: defaults.useDishMovedAlerts,
            )
            // Thresholds validates itself in its constructor; an invalid edit is dropped
            // rather than being persisted and breaking the next launch.
            val updated = runCatching { transform(current) }.getOrNull() ?: return@edit

            prefs[Keys.POLL_INTERVAL] = updated.pollIntervalSec
            prefs[Keys.SUDDEN_WINDOW] = updated.suddenWindowSec
            prefs[Keys.AZIMUTH] = updated.azimuthDeg
            prefs[Keys.ELEVATION] = updated.elevationDeg
            prefs[Keys.TILT] = updated.tiltDeg
            prefs[Keys.GPS_METERS] = updated.gpsMeters
            prefs[Keys.CONFIRM_SAMPLES] = updated.confirmSamples
            prefs[Keys.ARMING_GRACE] = updated.armingGraceSec
            prefs[Keys.SUPPRESS_ACTUATING] = updated.suppressWhileActuating
            prefs[Keys.REQUIRE_CONVERGED] = updated.requireConvergedAttitude
            prefs[Keys.GPS_ENABLED] = updated.gpsEnabled
            prefs[Keys.DISH_ALERTS] = updated.useDishMovedAlerts
        }
    }

    suspend fun setArmed(armed: Boolean) {
        context.dataStore.edit { it[Keys.ARMED] = armed }
    }

    suspend fun setWebhook(enabled: Boolean, url: String) {
        context.dataStore.edit {
            it[Keys.WEBHOOK_ENABLED] = enabled
            it[Keys.WEBHOOK_URL] = url
        }
    }

    suspend fun setAlarmMaxDuration(seconds: Int) {
        context.dataStore.edit { it[Keys.ALARM_MAX_DURATION] = seconds }
    }

    suspend fun setVibrate(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VIBRATE] = enabled }
    }

    suspend fun saveDetectorSnapshot(snapshot: DetectorSnapshot?) {
        context.dataStore.edit { prefs ->
            if (snapshot == null) {
                prefs.remove(Keys.DETECTOR_SNAPSHOT)
            } else {
                prefs[Keys.DETECTOR_SNAPSHOT] = json.encodeToString(DetectorSnapshot.serializer(), snapshot)
            }
        }
    }

    val detectorSnapshot: Flow<DetectorSnapshot?> = context.dataStore.data.map { prefs ->
        prefs[Keys.DETECTOR_SNAPSHOT]?.let {
            runCatching { json.decodeFromString(DetectorSnapshot.serializer(), it) }.getOrNull()
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
