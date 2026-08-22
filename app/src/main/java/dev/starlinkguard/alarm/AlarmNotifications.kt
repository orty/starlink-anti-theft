package dev.starlinkguard.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.starlinkguard.R
import dev.starlinkguard.service.MonitorService
import dev.starlinkguard.ui.MainActivity

object AlarmNotifications {

    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_ALARM = "alarm"

    const val NOTIFICATION_MONITOR = 1
    const val NOTIFICATION_ALARM = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            context.getString(R.string.channel_monitor_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_monitor_description)
            setShowBadge(false)
        }

        val alarm = NotificationChannel(
            CHANNEL_ALARM,
            context.getString(R.string.channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alarm_description)
            // Takes effect only once the user grants notification policy access, and only if
            // they have not edited the channel themselves.
            setBypassDnd(true)
            enableVibration(false)
            // The sound is owned by AlarmPlayer so it can loop and be stopped deliberately;
            // letting the channel play it too would double up.
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannel(monitor)
        manager.createNotificationChannel(alarm)
    }

    fun monitorNotification(context: Context, title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disarm = PendingIntent.getService(
            context,
            1,
            Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_DISARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Disarm", disarm)
            .build()
    }

    fun alarmNotification(context: Context, text: String): Notification {
        val fullScreen = PendingIntent.getActivity(
            context,
            2,
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context,
            3,
            Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Dish moved")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSound(null)
            // The sanctioned way to bring an activity to the front from a service on
            // Android 10+; a bare startActivity() from the background is blocked.
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, "Stop alarm", stop)
            .build()
    }

    /**
     * Whether the OS will honour a full-screen intent. On Android 14+ this permission is not
     * granted automatically, and the user has to allow it in settings.
     */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.canUseFullScreenIntent()
    }
}
