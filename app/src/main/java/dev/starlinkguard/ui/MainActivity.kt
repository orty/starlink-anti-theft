package dev.starlinkguard.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.starlinkguard.ui.theme.StarlinkGuardTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StarlinkGuardTheme {
                AppRoot()
            }
        }
    }
}

private enum class Tab(val label: String) {
    DASHBOARD("Dish"),
    SETTINGS("Settings"),
    LOG("History"),
}

@Composable
private fun AppRoot(viewModel: MainViewModel = viewModel()) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember { Tab.entries.toTypedArray() }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The dashboard shows what is still missing either way. */ }

    LaunchedEffect(Unit) {
        // Without this the foreground-service notification is invisible, which makes the app
        // look dead while it is actually running.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val monitorState by viewModel.monitorState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val events by viewModel.events.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    Tab.DASHBOARD -> Icons.Filled.Radar
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                    Tab.LOG -> Icons.Filled.History
                                },
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (tabs[selected]) {
            Tab.DASHBOARD -> DashboardScreen(
                state = monitorState,
                settings = settings,
                onArm = viewModel::arm,
                onDisarm = viewModel::disarm,
                onStopAlarm = viewModel::stopAlarm,
                modifier = Modifier.padding(padding),
            )
            Tab.SETTINGS -> SettingsScreen(
                settings = settings,
                onUpdateThresholds = viewModel::updateThresholds,
                onSetWebhook = viewModel::setWebhook,
                onSetAlarmMaxDuration = viewModel::setAlarmMaxDuration,
                onSetVibrate = viewModel::setVibrate,
                onTestAlarm = viewModel::testAlarm,
                alarmSounding = monitorState.alarmSounding,
                modifier = Modifier.padding(padding),
            )
            Tab.LOG -> EventLogScreen(
                events = events,
                onClear = viewModel::clearEvents,
                modifier = Modifier.padding(padding),
            )
        }
    }
}
