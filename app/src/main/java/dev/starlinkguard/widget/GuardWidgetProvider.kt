package dev.starlinkguard.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.starlinkguard.StarlinkGuardApp
import dev.starlinkguard.core.detect.MonitorState
import dev.starlinkguard.data.MonitorRepository
import dev.starlinkguard.service.MonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The home-screen button.
 *
 * Widgets are redrawn in whatever process the system happens to start, which may be a fresh one
 * where the in-memory [MonitorRepository] is still at its defaults. So the armed state is also
 * persisted, and a cold redraw falls back to that rather than confidently claiming the dish is
 * unwatched when it is not.
 */
class GuardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (state, alarming) = currentState(context)
                GuardWidget.render(context, appWidgetManager, appWidgetIds, state, alarming)
            } catch (e: Throwable) {
                Log.w(TAG, "could not draw the widget", e)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GuardWidget.ACTION_TAP) {
            super.onReceive(context, intent)
            return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (state, alarming) = currentState(context)
                when (GuardWidget.actionFor(state, alarming)) {
                    // Tapping a widget is one of the documented exemptions from the Android 12+
                    // ban on starting a foreground service from the background, so arming from
                    // the home screen is allowed even with the app closed.
                    WidgetAction.ARM -> MonitorService.start(context, MonitorService.ACTION_ARM)
                    WidgetAction.DISARM -> MonitorService.sendAction(context, MonitorService.ACTION_DISARM)
                    WidgetAction.SILENCE -> MonitorService.sendAction(context, MonitorService.ACTION_STOP_ALARM)
                }
                // Optimistic redraw so the button responds immediately; the service publishes
                // the settled state a moment later.
                val next = when (GuardWidget.actionFor(state, alarming)) {
                    WidgetAction.ARM -> MonitorState.ARMING to false
                    WidgetAction.DISARM -> MonitorState.DISARMED to false
                    WidgetAction.SILENCE -> MonitorState.ARMING to false
                }
                GuardWidget.refresh(context, next.first, next.second)
            } catch (e: Throwable) {
                Log.w(TAG, "widget tap failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun currentState(context: Context): Pair<MonitorState, Boolean> {
        val live = MonitorRepository.uiState.value
        if (live.serviceRunning) return live.state to live.alarmSounding

        // The service is not running in this process, so fall back to the armed flag it
        // persists. That cannot tell ARMED from SETTLING or NO LINK, but it does answer the
        // question the button actually asks, and it avoids a second stored copy of the state.
        val app = context.applicationContext as? StarlinkGuardApp
            ?: return MonitorState.DISARMED to false
        val armed = runCatching { app.settingsStore.settings.first().armed }.getOrDefault(false)
        return (if (armed) MonitorState.ARMED else MonitorState.DISARMED) to false
    }

    private companion object {
        const val TAG = "GuardWidget"
    }
}
