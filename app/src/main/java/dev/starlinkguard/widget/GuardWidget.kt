package dev.starlinkguard.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.starlinkguard.R
import dev.starlinkguard.core.detect.MonitorState

/**
 * How the home-screen button looks and what tapping it does.
 *
 * Kept apart from the [GuardWidgetProvider] broadcast plumbing so the mapping from state to
 * appearance and to action is one readable table.
 */
object GuardWidget {

    const val ACTION_TAP = "dev.starlinkguard.widget.TAP"

    /** Appearance and behaviour for one monitor state. */
    private data class Face(
        val label: String,
        val iconRes: Int,
        val backgroundRes: Int,
    )

    private fun face(state: MonitorState, alarmSounding: Boolean): Face = when {
        // The alarm outranks everything: whatever else is going on, the button must read as
        // "press me to make it stop".
        alarmSounding || state == MonitorState.ALARMING ->
            Face("SILENCE", R.drawable.ic_widget_alert, R.drawable.widget_bg_red)

        state == MonitorState.DISARMED ->
            Face("OFF", R.drawable.ic_widget_unlock, R.drawable.widget_bg_grey)

        state == MonitorState.ARMED ->
            Face("ARMED", R.drawable.ic_widget_lock, R.drawable.widget_bg_green)

        state == MonitorState.ARMING ->
            Face("SETTLING", R.drawable.ic_widget_lock, R.drawable.widget_bg_amber)

        state == MonitorState.SUSPECT ->
            Face("CHECKING", R.drawable.ic_widget_lock, R.drawable.widget_bg_amber)

        // STALE: armed, but the dish is not answering.
        else -> Face("NO LINK", R.drawable.ic_widget_lock, R.drawable.widget_bg_amber)
    }

    /**
     * What a tap should do from a given state.
     *
     * Silencing wins over disarming while the alarm sounds — reaching for the widget mid-siren
     * is overwhelmingly a request for quiet, and disarming as a side effect would leave the
     * dish unwatched without the user realising.
     */
    fun actionFor(state: MonitorState, alarmSounding: Boolean): WidgetAction = when {
        alarmSounding || state == MonitorState.ALARMING -> WidgetAction.SILENCE
        state == MonitorState.DISARMED -> WidgetAction.ARM
        else -> WidgetAction.DISARM
    }

    fun render(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
        state: MonitorState,
        alarmSounding: Boolean,
    ) {
        if (widgetIds.isEmpty()) return
        val (label, iconRes, backgroundRes) = face(state, alarmSounding)

        val views = RemoteViews(context.packageName, R.layout.widget_guard).apply {
            setTextViewText(R.id.widget_label, label)
            setImageViewResource(R.id.widget_icon, iconRes)
            setInt(R.id.widget_root, "setBackgroundResource", backgroundRes)
            setOnClickPendingIntent(R.id.widget_root, tapIntent(context))
            setContentDescription(R.id.widget_root, contentDescription(state, alarmSounding))
        }

        widgetIds.forEach { manager.updateAppWidget(it, views) }
    }

    private fun contentDescription(state: MonitorState, alarmSounding: Boolean): String =
        when (actionFor(state, alarmSounding)) {
            WidgetAction.SILENCE -> "Alarm sounding. Tap to silence."
            WidgetAction.ARM -> "Not watching the dish. Tap to arm."
            WidgetAction.DISARM -> "Watching the dish. Tap to disarm."
        }

    private fun tapIntent(context: Context): PendingIntent {
        val intent = Intent(context, GuardWidgetProvider::class.java).setAction(ACTION_TAP)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Redraws every placed instance of the widget. */
    fun refresh(context: Context, state: MonitorState, alarmSounding: Boolean) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, GuardWidgetProvider::class.java))
        render(context, manager, ids, state, alarmSounding)
    }
}

enum class WidgetAction { ARM, DISARM, SILENCE }
