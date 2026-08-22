package dev.starlinkguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.starlinkguard.alarm.AlarmSound
import dev.starlinkguard.core.detect.Thresholds
import dev.starlinkguard.data.AppSettings
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdateThresholds: ((Thresholds) -> Thresholds) -> Unit,
    onSetWebhook: (Boolean, String) -> Unit,
    onSetAlarmMaxDuration: (Int) -> Unit,
    onSetVibrate: (Boolean) -> Unit,
    onSetAlarmSound: (String) -> Unit,
    onPickAlarmSound: () -> Unit,
    onPickAlarmSoundFile: () -> Unit,
    onTestAlarm: () -> Unit,
    alarmSounding: Boolean,
    modifier: Modifier = Modifier,
) {
    val thresholds = settings.thresholds

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSection("Sensitivity") {
            SliderRow(
                label = "Azimuth",
                value = thresholds.azimuthDeg,
                range = 1f..45f,
                format = { "%.0f°".format(it) },
                helper = "How far the dish may rotate before it counts as movement.",
            ) { value -> onUpdateThresholds { it.copy(azimuthDeg = value) } }

            SliderRow(
                label = "Elevation",
                value = thresholds.elevationDeg,
                range = 1f..45f,
                format = { "%.0f°".format(it) },
                helper = "How far the dish may tip up or down.",
            ) { value -> onUpdateThresholds { it.copy(elevationDeg = value) } }

            SliderRow(
                label = "Mast tilt",
                value = thresholds.tiltDeg,
                range = 1f..45f,
                format = { "%.0f°".format(it) },
                helper = "How far the mast may lean. Lifting a dish off its mount shows up here first.",
            ) { value -> onUpdateThresholds { it.copy(tiltDeg = value) } }

            SliderRow(
                label = "Position",
                value = thresholds.gpsMeters.toFloat(),
                range = 10f..500f,
                format = { "%.0f m".format(it) },
                helper = "How far the dish's own GPS fix may move.",
            ) { value -> onUpdateThresholds { it.copy(gpsMeters = value.toDouble()) } }
        }

        SettingsSection("Timing") {
            SliderRow(
                label = "Poll every",
                value = thresholds.pollIntervalSec.toFloat(),
                range = 2f..60f,
                format = { "%.0f s".format(it) },
                helper = "Faster polling reacts sooner and costs more battery.",
            ) { value ->
                val interval = value.roundToInt()
                onUpdateThresholds {
                    it.copy(
                        pollIntervalSec = interval,
                        // The look-back window can never be shorter than one poll.
                        suddenWindowSec = maxOf(it.suddenWindowSec, interval),
                    )
                }
            }

            SliderRow(
                label = "Sudden-change window",
                value = thresholds.suddenWindowSec.toFloat(),
                range = thresholds.pollIntervalSec.toFloat()..300f,
                format = { "%.0f s".format(it) },
                helper = "A move larger than the limits above within this window is treated as sudden.",
            ) { value -> onUpdateThresholds { it.copy(suddenWindowSec = value.roundToInt()) } }

            SliderRow(
                label = "Confirm over",
                value = thresholds.confirmSamples.toFloat(),
                range = 1f..5f,
                format = { "%.0f polls".format(it) },
                helper = "How many polls in a row must agree before the alarm sounds.",
            ) { value -> onUpdateThresholds { it.copy(confirmSamples = value.roundToInt()) } }

            SliderRow(
                label = "Settling time",
                value = thresholds.armingGraceSec.toFloat(),
                range = 0f..300f,
                format = { "%.0f s".format(it) },
                helper = "How long to wait after arming before recording the reference position.",
            ) { value -> onUpdateThresholds { it.copy(armingGraceSec = value.roundToInt()) } }
        }

        SettingsSection("False alarms") {
            SwitchRow(
                label = "Ignore the dish's own re-aiming",
                checked = thresholds.suppressWhileActuating,
                helper = "Motorised dishes move themselves. With this on, orientation is only judged " +
                    "while the motors are idle. Turn it off for a dish with no actuators.",
            ) { value -> onUpdateThresholds { it.copy(suppressWhileActuating = value) } }

            SwitchRow(
                label = "Only trust a converged attitude",
                checked = thresholds.requireConvergedAttitude,
                helper = "Ignores orientation while the dish is still working out which way it points.",
            ) { value -> onUpdateThresholds { it.copy(requireConvergedAttitude = value) } }
        }

        SettingsSection("Triggers") {
            SwitchRow(
                label = "Watch GPS position",
                checked = thresholds.gpsEnabled,
                helper = "Uses the dish's own fix. Needs Starlink Location enabled in the Starlink app.",
            ) { value -> onUpdateThresholds { it.copy(gpsEnabled = value) } }

            SwitchRow(
                label = "Trust the dish's movement alerts",
                checked = thresholds.useDishMovedAlerts,
                helper = "Also alarm when the dish itself reports an unexpected location or a " +
                    "non-vertical mast. Useful when GPS is unavailable, but can fire for benign reasons.",
            ) { value -> onUpdateThresholds { it.copy(useDishMovedAlerts = value) } }

            SwitchRow(
                label = "Alarm if the dish goes silent",
                checked = thresholds.offlineEnabled,
                helper = "Unplugging the cable or driving off with the dish stops it answering " +
                    "immediately, so this is often the earliest warning there is. By default it " +
                    "is only judged on the Wi-Fi the dish answers on, so leaving home does not " +
                    "count — the two options below relax that.",
            ) { value -> onUpdateThresholds { it.copy(offlineEnabled = value) } }

            if (thresholds.offlineEnabled) {
                SliderRow(
                    label = "Silent for",
                    value = thresholds.offlineGraceSec.toFloat(),
                    range = 5f..300f,
                    format = { "%.0f s".format(it) },
                    // 5-second stops: the useful settings are all at the short end.
                    steps = 58,
                    helper = "How long the dish must stay unreachable before it counts. Short is " +
                        "good — a thief is gone in under a minute. The cost is that a dish reboot " +
                        "or firmware update, which take a minute or two, will also sound the alarm. " +
                        "Cannot react faster than the poll interval.",
                ) { value -> onUpdateThresholds { it.copy(offlineGraceSec = value.roundToInt()) } }

                SwitchRow(
                    label = "…even if the Wi-Fi disappears",
                    checked = thresholds.offlineWhenWifiLost,
                    helper = "Turn this on for a Starlink Mini, where the router is built into the " +
                        "dish and unplugging it takes the Wi-Fi with it. On a Gen2/Gen3 setup the " +
                        "router keeps running without the dish, so leave it off. Be aware it also " +
                        "means walking out of Wi-Fi range will set off the alarm.",
                ) { value -> onUpdateThresholds { it.copy(offlineWhenWifiLost = value) } }

                SwitchRow(
                    label = "…even on a different Wi-Fi",
                    checked = thresholds.offlineWhenNetworkChanged,
                    helper = "Also for a Mini: when its network dies the phone usually falls back to " +
                        "another one rather than to nothing. With this off, joining any other network " +
                        "is treated as telling you nothing about the dish.",
                ) { value -> onUpdateThresholds { it.copy(offlineWhenNetworkChanged = value) } }
            }
        }

        SettingsSection("Alarm") {
            AlarmSoundRow(
                uriValue = settings.alarmSoundUri,
                onPickRingtone = onPickAlarmSound,
                onPickFile = onPickAlarmSoundFile,
                onUseDefault = { onSetAlarmSound("") },
            )

            SwitchRow(
                label = "Vibrate",
                checked = settings.vibrateOnAlarm,
                helper = null,
                onChange = onSetVibrate,
            )

            SliderRow(
                label = "Stop after",
                value = settings.alarmMaxDurationSec.toFloat(),
                range = 30f..900f,
                format = { "%.0f s".format(it) },
                helper = "Stops the siren by itself so it does not run the battery flat.",
            ) { value -> onSetAlarmMaxDuration(value.roundToInt()) }

            OutlinedButton(onClick = onTestAlarm, modifier = Modifier.fillMaxWidth()) {
                Text(if (alarmSounding) "Stop test" else "Test alarm sound")
            }
            Text(
                "Put the phone on silent first — the alarm should still be audible.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        WebhookSection(settings, onSetWebhook)
    }
}

/** What the chosen sound resolves to right now. */
private sealed interface SoundState {
    data object Loading : SoundState
    data object Default : SoundState
    data class Named(val name: String) : SoundState
    data object Unavailable : SoundState
}

@Composable
private fun AlarmSoundRow(
    uriValue: String,
    onPickRingtone: () -> Unit,
    onPickFile: () -> Unit,
    onUseDefault: () -> Unit,
) {
    val context = LocalContext.current
    val custom = remember(uriValue) { AlarmSound.parse(uriValue) }

    // Resolving a name and checking readability both hit the content resolver, so this is kept
    // off the main thread. Re-runs whenever the stored choice changes.
    val state by produceState<SoundState>(SoundState.Loading, custom) {
        value = withContext(Dispatchers.IO) {
            when {
                custom == null -> SoundState.Default
                !AlarmSound.isReadable(context, custom) -> SoundState.Unavailable
                else -> SoundState.Named(AlarmSound.label(context, custom))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Sound", style = MaterialTheme.typography.bodyLarge)

        when (val current = state) {
            SoundState.Loading -> Text("…", style = MaterialTheme.typography.bodyMedium)
            SoundState.Default -> Text(
                "Default alarm sound",
                style = MaterialTheme.typography.bodyMedium,
            )
            is SoundState.Named -> Text(
                current.name,
                style = MaterialTheme.typography.bodyMedium,
            )
            // Surfaced rather than silently corrected: the user should know their choice is
            // gone, even though the alarm itself will still sound.
            SoundState.Unavailable -> Text(
                "That sound is no longer available — the default alarm will be used.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onPickRingtone, modifier = Modifier.weight(1f)) {
                Text("Choose")
            }
            OutlinedButton(onClick = onPickFile, modifier = Modifier.weight(1f)) {
                Text("From file")
            }
        }
        if (custom != null) {
            OutlinedButton(onClick = onUseDefault, modifier = Modifier.fillMaxWidth()) {
                Text("Use default")
            }
        }
        Text(
            "Plays on the alarm channel whichever sound you pick, so silent mode does not mute it.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun WebhookSection(settings: AppSettings, onSetWebhook: (Boolean, String) -> Unit) {
    var url by remember(settings.webhookUrl) { mutableStateOf(settings.webhookUrl) }

    SettingsSection("Webhook") {
        SwitchRow(
            label = "Notify a URL",
            checked = settings.webhookEnabled,
            helper = "POSTs a JSON description of the event. Sent over whatever network can reach " +
                "the internet, so it still works when the dish's Wi-Fi has gone.",
        ) { enabled -> onSetWebhook(enabled, url) }

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("https://example.com/hook") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { onSetWebhook(settings.webhookEnabled, url) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save URL")
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    helper: String?,
    /** Intermediate stops, so a slider spanning a wide range can still land on round values. */
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    // Track the drag locally so the slider stays smooth while the store round-trips.
    var local by remember(value) { mutableStateOf(value) }

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(format(local), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = local.coerceIn(range),
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps,
        )
        helper?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    helper: String?,
    onChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
        helper?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
