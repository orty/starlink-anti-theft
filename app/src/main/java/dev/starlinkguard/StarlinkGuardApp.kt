package dev.starlinkguard

import android.app.Application
import dev.starlinkguard.alarm.AlarmNotifications
import dev.starlinkguard.data.EventLog
import dev.starlinkguard.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StarlinkGuardApp : Application() {

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var eventLog: EventLog
        private set

    /** For work that has to outlive the service, such as logging that it was disarmed. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        eventLog = EventLog(this)
        AlarmNotifications.createChannels(this)
        applicationScope.launch { eventLog.load() }
    }
}
