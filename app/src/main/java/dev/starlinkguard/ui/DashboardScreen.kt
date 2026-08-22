package dev.starlinkguard.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.starlinkguard.alarm.AlarmNotifications
import dev.starlinkguard.core.detect.MonitorState
import dev.starlinkguard.core.detect.SuppressionReason
import dev.starlinkguard.core.model.AttitudeEstimationState
import dev.starlinkguard.data.AppSettings
import dev.starlinkguard.data.MonitorUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    state: MonitorUiState,
    settings: AppSettings,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onStopAlarm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state)

        if (state.alarmSounding) {
            Button(
                onClick = onStopAlarm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("STOP ALARM")
            }
        }

        if (state.state == MonitorState.DISARMED) {
            Button(onClick = onArm, modifier = Modifier.fillMaxWidth()) {
                Text("Arm")
            }
        } else {
            OutlinedButton(onClick = onDisarm, modifier = Modifier.fillMaxWidth()) {
                Text("Disarm")
            }
        }

        LiveReadingsCard(state)
        BaselineCard(state)

        if (settings.thresholds.gpsEnabled && !state.locationAvailable) {
            GpsUnavailableCard()
        }

        PermissionsCard(context)
    }
}

@Composable
private fun StatusCard(state: MonitorUiState) {
    val (label, detail, tint) = when (state.state) {
        MonitorState.DISARMED -> Triple("Disarmed", "The dish is not being watched.", Color(0xFF757575))
        MonitorState.ARMING -> Triple("Arming", "Letting the dish settle before recording its position.", Color(0xFFF9A825))
        MonitorState.ARMED -> Triple("Armed", "The dish is where you left it.", Color(0xFF2E7D32))
        MonitorState.SUSPECT -> Triple("Checking", "Something moved. Confirming before sounding the alarm.", Color(0xFFF9A825))
        MonitorState.ALARMING -> Triple("ALARM", "The dish has moved.", Color(0xFFC62828))
        MonitorState.STALE -> Triple("No contact", "The dish is not answering on the local network.", Color(0xFFF9A825))
    }

    Card(colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.12f))) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.headlineSmall, color = tint)
            Text(detail, style = MaterialTheme.typography.bodyMedium)

            state.lastError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            suppressionMessage(state.suppression)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }

            if (state.activeTriggers.isNotEmpty() && state.state == MonitorState.ALARMING) {
                state.activeTriggers.forEach {
                    Text("• ${it.describe()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun suppressionMessage(reason: SuppressionReason): String? = when (reason) {
    SuppressionReason.NONE, SuppressionReason.DISARMED -> null
    SuppressionReason.GRACE_PERIOD -> "Settling — nothing can trigger yet."
    SuppressionReason.DISH_UNREACHABLE -> "Waiting for the dish to answer. Check you are on the Starlink Wi-Fi."
    SuppressionReason.NO_DISH_NETWORK ->
        "No Wi-Fi, so the dish cannot be checked from here. Nothing will trigger until it is back."
    SuppressionReason.UNFAMILIAR_NETWORK ->
        "The dish has not answered on this Wi-Fi, so its silence is not evidence of anything here."
    SuppressionReason.NO_ORIENTATION_DATA -> "This dish is not reporting orientation."
    SuppressionReason.ATTITUDE_UNCONVERGED -> "The dish is still working out which way it points; orientation is not being judged."
    SuppressionReason.ACTUATORS_MOVING -> "The dish is re-aiming itself, so orientation checks are paused."
}

@Composable
private fun LiveReadingsCard(state: MonitorUiState) {
    val status = state.lastStatus ?: return
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Live readings", style = MaterialTheme.typography.titleMedium)
            Reading("Azimuth", status.azimuthDeg?.let { "%.2f°".format(it) })
            Reading("Elevation", status.elevationDeg?.let { "%.2f°".format(it) })
            Reading("Mast tilt", status.tiltDeg?.let { "%.2f°".format(it) })
            Reading("Attitude filter", status.attitudeState.readable())
            Reading("Motors", status.actuatorState.name.removePrefix("ACTUATOR_STATE_").lowercase())
            Reading("GPS", if (status.gpsValid) "locked, ${status.gpsSats} satellites" else "no fix")
            state.lastLocation?.let {
                Reading("Coordinates", "%.5f, %.5f".format(it.latitude, it.longitude))
            }
            status.serial?.let { Reading("Dish", it) }

            if (status.alerts.mastNotNearVertical) {
                Text("Dish reports its mast is not vertical.", color = MaterialTheme.colorScheme.error)
            }
            if (status.alerts.unexpectedLocation) {
                Text("Dish reports it is not where it expects to be.", color = MaterialTheme.colorScheme.error)
            }

            if (state.lastPollAtMs > 0) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "Last poll ${formatTime(state.lastPollAtMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun AttitudeEstimationState.readable(): String = when (this) {
    AttitudeEstimationState.FILTER_CONVERGED -> "converged"
    AttitudeEstimationState.FILTER_UNCONVERGED -> "converging"
    AttitudeEstimationState.FILTER_RESET -> "reset"
    AttitudeEstimationState.FILTER_FAULTED -> "faulted"
    AttitudeEstimationState.FILTER_INVALID -> "invalid"
    AttitudeEstimationState.UNKNOWN -> "not reported"
}

@Composable
private fun BaselineCard(state: MonitorUiState) {
    val baseline = state.baseline ?: return
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Reference position", style = MaterialTheme.typography.titleMedium)
            Text(
                "Recorded ${formatTime(baseline.capturedAtMs)}. Everything is compared against this.",
                style = MaterialTheme.typography.bodySmall,
            )
            Reading("Azimuth", baseline.azimuthDeg?.let { "%.2f°".format(it) })
            Reading("Elevation", baseline.elevationDeg?.let { "%.2f°".format(it) })
            Reading("Mast tilt", baseline.tiltDeg?.let { "%.2f°".format(it) })
            if (baseline.latitude != null && baseline.longitude != null) {
                Reading("Coordinates", "%.5f, %.5f".format(baseline.latitude, baseline.longitude))
            }
        }
    }
}

@Composable
private fun GpsUnavailableCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("GPS position unavailable", style = MaterialTheme.typography.titleMedium)
            Text(
                "The dish is refusing to share coordinates, so only orientation is being watched. " +
                    "To enable it, open the Starlink app and turn on Settings → Advanced → Debug Data → " +
                    "Starlink Location. Some service plans do not expose it at all, which is normal.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PermissionsCard(context: Context) {
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val dndGranted = notificationManager.isNotificationPolicyAccessGranted
    val fullScreenGranted = AlarmNotifications.canUseFullScreenIntent(context)
    val batteryExempt = isIgnoringBatteryOptimizations(context)

    if (dndGranted && fullScreenGranted && batteryExempt) return

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
            Text(
                "The alarm already plays through the alarm stream, which silent and vibrate mode do " +
                    "not mute. These extra grants cover the remaining cases.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (!dndGranted) {
                PermissionRow(
                    title = "Allow through Do Not Disturb",
                    detail = "Without this, Do Not Disturb can silence the alarm.",
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }

            if (!fullScreenGranted) {
                PermissionRow(
                    title = "Allow full-screen alerts",
                    detail = "Lets the alarm screen appear over the lock screen.",
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }

            if (!batteryExempt) {
                PermissionRow(
                    title = "Ignore battery optimisation",
                    detail = "Stops Android from suspending monitoring while the screen is off.",
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return runCatching { powerManager.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
}

@Composable
private fun PermissionRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onClick) { Text("Open") }
    }
}

@Composable
private fun Reading(label: String, value: String?) {
    if (value == null) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

internal fun formatTime(millis: Long): String = timeFormat.format(Date(millis))
