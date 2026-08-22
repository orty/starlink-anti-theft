package dev.starlinkguard.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.starlinkguard.StarlinkGuardApp
import dev.starlinkguard.alarm.AlarmNotifications
import dev.starlinkguard.alarm.AlarmPlayer
import dev.starlinkguard.alarm.AlarmSound
import dev.starlinkguard.alert.WebhookSender
import dev.starlinkguard.core.alert.EventType
import dev.starlinkguard.core.alert.TheftEvent
import dev.starlinkguard.core.detect.DetectorResult
import dev.starlinkguard.core.detect.MonitorState
import dev.starlinkguard.core.detect.Sample
import dev.starlinkguard.core.detect.TheftDetector
import dev.starlinkguard.core.grpc.GrpcException
import dev.starlinkguard.core.grpc.GrpcStatus
import dev.starlinkguard.core.grpc.OkHttpDishClient
import dev.starlinkguard.core.model.DishLocation
import dev.starlinkguard.core.model.DishStatus
import dev.starlinkguard.data.AppSettings
import dev.starlinkguard.data.MonitorRepository
import dev.starlinkguard.net.NetworkProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Watches the dish for as long as the system is armed.
 *
 * Runs as a `connectedDevice` foreground service. That type was picked over `dataSync` for two
 * concrete reasons: `dataSync` is capped at six hours a day on Android 15 and cannot be started
 * from `BOOT_COMPLETED`, and an alarm that quietly stops after six hours or never comes back
 * after a reboot is not an alarm.
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null
    private var alarmTimeoutJob: Job? = null

    private lateinit var networkProvider: NetworkProvider
    private lateinit var alarmPlayer: AlarmPlayer
    private lateinit var webhookSender: WebhookSender

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val detector = TheftDetector()
    private var settings = AppSettings()

    /** Remembered so the "GPS unavailable" banner does not flicker between polls. */
    private var locationSupported = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        networkProvider = NetworkProvider(this)
        alarmPlayer = AlarmPlayer(this)
        webhookSender = WebhookSender(networkProvider)
        networkProvider.start()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification has to go up promptly whatever the action was, or the system kills
        // the service for not calling startForeground() in time.
        startForegroundSafely("Starting…", "Connecting to the dish")

        when (intent?.action) {
            ACTION_DISARM -> {
                disarm()
                return START_NOT_STICKY
            }
            ACTION_STOP_ALARM -> stopAlarm()
            ACTION_TEST_ALARM -> testAlarm()
            else -> arm()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        alarmPlayer.stop()
        networkProvider.stop()
        releaseLocks()
        scope.cancel()
        MonitorRepository.update { it.copy(serviceRunning = false) }
        super.onDestroy()
    }

    private fun arm() {
        if (pollJob?.isActive == true) return

        pollJob = scope.launch {
            val app = application as StarlinkGuardApp
            settings = app.settingsStore.settings.first()
            detector.thresholds = settings.thresholds

            // Resume rather than restart when the process was killed while armed: a fresh
            // baseline would forgive any movement that happened while we were dead.
            val snapshot = app.settingsStore.detectorSnapshot.first()
            if (snapshot != null && snapshot.armed) {
                detector.restore(snapshot)
            } else {
                detector.arm(System.currentTimeMillis())
                app.eventLog.append(EventType.ARMED, "Monitoring started")
            }

            app.settingsStore.setArmed(true)
            MonitorRepository.update { it.copy(serviceRunning = true, state = detector.state) }

            // Settings changes take effect on the next poll without needing a restart.
            launch {
                app.settingsStore.settings.collect { updated ->
                    settings = updated
                    detector.thresholds = updated.thresholds
                }
            }

            pollLoop()
        }
    }

    private suspend fun pollLoop() {
        val app = application as StarlinkGuardApp
        var loggedBaselineAtMs = 0L
        var savedSnapshot: dev.starlinkguard.core.detect.DetectorSnapshot? = null

        while (true) {
            val sample = pollOnce()
            val result = detector.onSample(sample)

            MonitorRepository.update { current ->
                current.copy(
                    state = result.state,
                    serviceRunning = true,
                    lastStatus = sample.status ?: current.lastStatus,
                    lastLocation = sample.location ?: current.lastLocation,
                    lastPollAtMs = sample.atMs,
                    lastSuccessfulPollAtMs = if (sample.reachable) sample.atMs else current.lastSuccessfulPollAtMs,
                    baseline = result.baseline,
                    suppression = result.suppression,
                    activeTriggers = if (result.triggers.isNotEmpty()) result.triggers else current.activeTriggers,
                    alarmSounding = alarmPlayer.isPlaying,
                    locationAvailable = locationSupported,
                )
            }

            // Compared by capture time so a re-baseline after an acknowledged alarm is logged
            // again rather than being swallowed by a one-shot flag.
            val baseline = result.baseline
            if (baseline != null && baseline.capturedAtMs != loggedBaselineAtMs) {
                loggedBaselineAtMs = baseline.capturedAtMs
                app.eventLog.append(
                    EventType.BASELINE_CAPTURED,
                    "Reference position recorded: " + describeBaseline(result),
                    dishSerial = sample.status?.serial,
                )
            }

            if (result.alarmStarted && !alarmPlayer.isPlaying) {
                raiseAlarm(result.triggers.map { it.describe() }, sample.status)
            }

            // Only persist when something actually changed. This loop runs every few seconds
            // for days on end, and an unconditional write would hammer flash for nothing.
            val snapshot = detector.snapshot()
            if (snapshot != savedSnapshot) {
                savedSnapshot = snapshot
                app.settingsStore.saveDetectorSnapshot(snapshot)
            }

            updateNotification(result.state, sample)

            delay(settings.thresholds.pollIntervalSec * 1000L)
        }
    }

    private fun describeBaseline(result: DetectorResult): String {
        val baseline = result.baseline ?: return "unknown"
        val parts = buildList {
            baseline.azimuthDeg?.let { add("azimuth %.1f°".format(it)) }
            baseline.elevationDeg?.let { add("elevation %.1f°".format(it)) }
            baseline.tiltDeg?.let { add("tilt %.1f°".format(it)) }
            if (baseline.latitude != null) add("GPS locked")
        }
        return parts.joinToString(", ")
    }

    private suspend fun pollOnce(): Sample {
        val now = System.currentTimeMillis()
        val client = OkHttpDishClient.create(
            socketFactory = networkProvider.wifiSocketFactory(),
            timeoutMillis = POLL_TIMEOUT_MS,
        )
        var status: DishStatus? = null
        var location: DishLocation? = null
        var error: String? = null

        try {
            status = client.status()
            if (settings.thresholds.gpsEnabled) {
                location = client.location()
                // A dish that answers status but declines location is normal; record it once
                // so the UI can explain rather than looking broken.
                locationSupported = location != null || locationSupported
            }
        } catch (e: GrpcException) {
            error = when (e.status) {
                GrpcStatus.UNAVAILABLE -> "Dish not reachable on the local network"
                else -> e.message
            }
        } catch (e: Exception) {
            error = e.message ?: e::class.java.simpleName
        } finally {
            runCatching { client.close() }
        }

        if (status == null && error != null) {
            Log.d(TAG, "poll failed: $error")
            MonitorRepository.update { it.copy(lastError = error) }
        } else if (status != null) {
            MonitorRepository.update { it.copy(lastError = null) }
        }

        return Sample(
            atMs = now,
            status = status,
            location = location,
            // Without Wi-Fi an unanswered poll says nothing about the dish, only about
            // where the phone is. The detector needs to be able to tell those apart.
            dishNetworkAvailable = networkProvider.wifiNetwork != null,
        )
    }

    private fun raiseAlarm(reasons: List<String>, status: DishStatus?) {
        val app = application as StarlinkGuardApp
        val summary = reasons.joinToString("; ").ifBlank { "The dish moved unexpectedly" }

        alarmPlayer.start(settings.vibrateOnAlarm, AlarmSound.parse(settings.alarmSoundUri))

        val notificationManager = NotificationManagerCompat.from(this)
        runCatching {
            notificationManager.notify(
                AlarmNotifications.NOTIFICATION_ALARM,
                AlarmNotifications.alarmNotification(this, summary),
            )
        }.onFailure { Log.w(TAG, "could not post the alarm notification", it) }

        scope.launch {
            val event = app.eventLog.append(
                EventType.ALARM,
                summary,
                triggers = MonitorRepository.uiState.value.activeTriggers,
                dishSerial = status?.serial,
            )
            deliverWebhook(event)
        }

        // Stop by itself eventually so a dish moved while nobody is home does not run the
        // battery flat.
        alarmTimeoutJob?.cancel()
        val maxDuration = settings.alarmMaxDurationSec
        if (maxDuration > 0) {
            alarmTimeoutJob = scope.launch {
                delay(maxDuration * 1000L)
                if (alarmPlayer.isPlaying) stopAlarm()
            }
        }

        MonitorRepository.update { it.copy(alarmSounding = true, state = MonitorState.ALARMING) }
    }

    private suspend fun deliverWebhook(event: TheftEvent) {
        if (!settings.webhookEnabled || settings.webhookUrl.isBlank()) return
        val app = application as StarlinkGuardApp
        webhookSender.send(settings.webhookUrl, event).onFailure {
            app.eventLog.append(
                EventType.WEBHOOK_FAILED,
                "Webhook delivery failed: ${it.message ?: "unknown error"}",
            )
        }
    }

    private fun stopAlarm() {
        alarmTimeoutJob?.cancel()
        alarmPlayer.stop()
        NotificationManagerCompat.from(this).cancel(AlarmNotifications.NOTIFICATION_ALARM)
        detector.acknowledgeAlarm(System.currentTimeMillis())

        val app = application as StarlinkGuardApp
        scope.launch {
            app.eventLog.append(EventType.ALARM_STOPPED, "Alarm stopped; re-arming from the dish's current position")
            app.settingsStore.saveDetectorSnapshot(detector.snapshot())
        }
        MonitorRepository.update {
            it.copy(alarmSounding = false, state = detector.state, activeTriggers = emptyList())
        }
        stopIfIdle()
    }

    /** Sounds the alarm for a few seconds so the user can check audio actually works. */
    private fun testAlarm() {
        if (alarmPlayer.isPlaying) {
            alarmPlayer.stop()
            alarmTimeoutJob?.cancel()
            MonitorRepository.update { it.copy(alarmSounding = false) }
            stopIfIdle()
            return
        }

        scope.launch {
            // The service may have been started purely to run this test, in which case the
            // settings have not been read yet.
            settings = (application as StarlinkGuardApp).settingsStore.settings.first()
            alarmPlayer.start(settings.vibrateOnAlarm, AlarmSound.parse(settings.alarmSoundUri))
            MonitorRepository.update { it.copy(alarmSounding = true) }

            alarmTimeoutJob?.cancel()
            alarmTimeoutJob = scope.launch {
                delay(TEST_ALARM_MS)
                alarmPlayer.stop()
                MonitorRepository.update { it.copy(alarmSounding = false) }
                stopIfIdle()
            }
        }
    }

    /**
     * Shuts the service down when it is only alive to have played a test, so a "Starting…"
     * notification is not left sitting in the shade.
     */
    private fun stopIfIdle() {
        if (pollJob?.isActive == true) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun disarm() {
        pollJob?.cancel()
        pollJob = null
        alarmTimeoutJob?.cancel()
        alarmPlayer.stop()
        detector.disarm()

        val app = application as StarlinkGuardApp
        // The service is about to stop, so this work cannot live on the service scope.
        app.applicationScope.launch {
            app.settingsStore.setArmed(false)
            app.settingsStore.saveDetectorSnapshot(null)
            app.eventLog.append(EventType.DISARMED, "Monitoring stopped")
        }

        MonitorRepository.reset()
        NotificationManagerCompat.from(this).cancel(AlarmNotifications.NOTIFICATION_ALARM)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(state: MonitorState, sample: Sample) {
        val title = when (state) {
            MonitorState.DISARMED -> "Disarmed"
            MonitorState.ARMING -> "Arming — settling"
            MonitorState.ARMED -> "Watching the dish"
            MonitorState.SUSPECT -> "Possible movement"
            MonitorState.ALARMING -> "Dish moved"
            MonitorState.STALE -> "Dish not responding"
        }
        val text = sample.status?.let { status ->
            buildString {
                status.azimuthDeg?.let { append("Az %.1f°".format(it)) }
                status.elevationDeg?.let {
                    if (isNotEmpty()) append("  ")
                    append("El %.1f°".format(it))
                }
                status.tiltDeg?.let {
                    if (isNotEmpty()) append("  ")
                    append("Tilt %.1f°".format(it))
                }
                if (isEmpty()) append("Connected")
            }
        } ?: "No reply from 192.168.100.1"

        runCatching {
            NotificationManagerCompat.from(this)
                .notify(AlarmNotifications.NOTIFICATION_MONITOR, AlarmNotifications.monitorNotification(this, title, text))
        }.onFailure { Log.w(TAG, "could not update the monitor notification", it) }
    }

    private fun startForegroundSafely(title: String, text: String) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                AlarmNotifications.NOTIFICATION_MONITOR,
                AlarmNotifications.monitorNotification(this, title, text),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
        }.onFailure { Log.e(TAG, "could not enter the foreground", it) }
    }

    private fun acquireLocks() {
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "starlinkguard:monitor").apply {
                setReferenceCounted(false)
                acquire()
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(mode, "starlinkguard:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "could not acquire wake locks", it) }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val POLL_TIMEOUT_MS = 5_000L
        private const val TEST_ALARM_MS = 5_000L

        const val ACTION_ARM = "dev.starlinkguard.ARM"
        const val ACTION_DISARM = "dev.starlinkguard.DISARM"
        const val ACTION_STOP_ALARM = "dev.starlinkguard.STOP_ALARM"
        const val ACTION_TEST_ALARM = "dev.starlinkguard.TEST_ALARM"

        fun start(context: Context, action: String = ACTION_ARM) {
            val intent = Intent(context, MonitorService::class.java).setAction(action)
            runCatching {
                context.startForegroundService(intent)
            }.onFailure {
                // Android 12+ refuses background foreground-service starts in some states.
                Log.w(TAG, "could not start the monitor service", it)
            }
        }

        fun sendAction(context: Context, action: String) {
            val intent = Intent(context, MonitorService::class.java).setAction(action)
            runCatching { context.startService(intent) }
                .onFailure { runCatching { context.startForegroundService(intent) } }
        }
    }
}
