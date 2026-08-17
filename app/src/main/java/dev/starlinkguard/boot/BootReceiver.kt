package dev.starlinkguard.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.starlinkguard.StarlinkGuardApp
import dev.starlinkguard.service.MonitorService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Puts monitoring back after a reboot or an app update.
 *
 * This works because the service is a `connectedDevice` foreground service — Android 15
 * forbids starting a `dataSync` service from `BOOT_COMPLETED`, which would have left the dish
 * unwatched from every reboot until the user happened to open the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val app = context.applicationContext as? StarlinkGuardApp ?: return
        val pendingResult = goAsync()

        app.applicationScope.launch {
            try {
                if (app.settingsStore.settings.first().armed) {
                    MonitorService.start(context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not restart monitoring after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
