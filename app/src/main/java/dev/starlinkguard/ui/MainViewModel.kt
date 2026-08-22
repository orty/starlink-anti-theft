package dev.starlinkguard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.starlinkguard.StarlinkGuardApp
import dev.starlinkguard.core.alert.TheftEvent
import dev.starlinkguard.core.detect.Thresholds
import dev.starlinkguard.data.AppSettings
import dev.starlinkguard.data.MonitorRepository
import dev.starlinkguard.data.MonitorUiState
import dev.starlinkguard.service.MonitorService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StarlinkGuardApp

    val monitorState: StateFlow<MonitorUiState> = MonitorRepository.uiState

    val settings: StateFlow<AppSettings> = app.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val events: StateFlow<List<TheftEvent>> = app.eventLog.events

    init {
        viewModelScope.launch { app.eventLog.load() }
    }

    fun arm() = MonitorService.start(app, MonitorService.ACTION_ARM)

    fun disarm() = MonitorService.sendAction(app, MonitorService.ACTION_DISARM)

    fun stopAlarm() = MonitorService.sendAction(app, MonitorService.ACTION_STOP_ALARM)

    fun testAlarm() {
        // Routed through the service so the same audio path the real alarm uses is exercised.
        MonitorService.start(app, MonitorService.ACTION_TEST_ALARM)
    }

    fun updateThresholds(transform: (Thresholds) -> Thresholds) {
        viewModelScope.launch { app.settingsStore.updateThresholds(transform) }
    }

    fun setWebhook(enabled: Boolean, url: String) {
        viewModelScope.launch { app.settingsStore.setWebhook(enabled, url) }
    }

    fun setAlarmMaxDuration(seconds: Int) {
        viewModelScope.launch { app.settingsStore.setAlarmMaxDuration(seconds) }
    }

    fun setAlarmSound(uri: String) {
        viewModelScope.launch { app.settingsStore.setAlarmSound(uri) }
    }

    fun setVibrate(enabled: Boolean) {
        viewModelScope.launch { app.settingsStore.setVibrate(enabled) }
    }

    fun clearEvents() {
        viewModelScope.launch { app.eventLog.clear() }
    }
}
