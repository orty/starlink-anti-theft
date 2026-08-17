package dev.starlinkguard.core.detect

import dev.starlinkguard.core.model.DishLocation
import dev.starlinkguard.core.model.DishStatus
import kotlinx.serialization.Serializable

/** What the monitor is doing right now. */
enum class MonitorState {
    /** Not watching. */
    DISARMED,

    /** Watching, but still settling — no baseline captured yet, nothing can trigger. */
    ARMING,

    /** Watching, dish is where it was left. */
    ARMED,

    /** At least one poll breached a threshold, but not yet enough to be believed. */
    SUSPECT,

    /** Confirmed movement. The siren is sounding. */
    ALARMING,

    /** Watching, but the dish is not answering. */
    STALE,
}

/** Which measurement moved. */
enum class TriggerAxis { AZIMUTH, ELEVATION, TILT, POSITION, DISH_ALERT }

/** How the movement was spotted. */
enum class TriggerKind {
    /** Compared against the position recorded when the system was armed. */
    DRIFT,

    /** Compared against where the dish was a short window ago. */
    SUDDEN,

    /** The dish raised its own movement alert. */
    ALERT,
}

/** Why a poll was not evaluated. */
enum class SuppressionReason {
    NONE,
    DISARMED,
    GRACE_PERIOD,
    DISH_UNREACHABLE,
    NO_ORIENTATION_DATA,
    ATTITUDE_UNCONVERGED,
    ACTUATORS_MOVING,
}

@Serializable
data class Trigger(
    val axis: TriggerAxis,
    val kind: TriggerKind,
    val referenceValue: Double,
    val currentValue: Double,
    val delta: Double,
    val threshold: Double,
    val unit: String,
) {
    fun describe(): String = when (axis) {
        TriggerAxis.DISH_ALERT -> "Dish reported it has been moved"
        TriggerAxis.POSITION ->
            "Position moved %.0f %s (limit %.0f %s)".format(delta, unit, threshold, unit)
        else -> {
            val what = axis.name.lowercase().replaceFirstChar { it.uppercase() }
            val how = if (kind == TriggerKind.SUDDEN) "changed suddenly by" else "drifted"
            "%s %s %.1f%s (limit %.1f%s)".format(what, how, delta, unit, threshold, unit)
        }
    }
}

/** The dish position recorded when monitoring started. Everything is compared against this. */
@Serializable
data class Baseline(
    val azimuthDeg: Float? = null,
    val elevationDeg: Float? = null,
    val tiltDeg: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val serial: String? = null,
    val capturedAtMs: Long = 0L,
)

/** One poll of the dish. */
data class Sample(
    val atMs: Long,
    val status: DishStatus?,
    val location: DishLocation? = null,
) {
    val reachable: Boolean get() = status != null
}

/** The outcome of feeding one [Sample] to the detector. */
data class DetectorResult(
    val state: MonitorState,
    val triggers: List<Trigger> = emptyList(),
    val suppression: SuppressionReason = SuppressionReason.NONE,
    val baseline: Baseline? = null,
    val consecutiveBreaches: Int = 0,
) {
    val alarmStarted: Boolean get() = state == MonitorState.ALARMING && triggers.isNotEmpty()
}

/** Serialisable detector state, so monitoring survives the process being killed. */
@Serializable
data class DetectorSnapshot(
    val armed: Boolean,
    val alarming: Boolean,
    val baseline: Baseline?,
    val graceUntilMs: Long,
    val consecutiveBreaches: Int,
)

/**
 * Decides whether the dish has been moved.
 *
 * Deliberately free of Android and of any I/O: it is fed samples and returns decisions, which
 * is what makes every rule below testable on the JVM.
 *
 * The shape of the logic is driven by one awkward fact — motorised dishes re-aim themselves.
 * A naive "angle changed, sound the alarm" rule fires every time the dish tracks a satellite
 * handover. So orientation is only judged when the dish says its attitude filter has converged
 * and its motors are idle, and a breach has to repeat before it is believed. GPS and the
 * dish's own movement alerts are judged separately, because those stay meaningful even while
 * the motors are running.
 */
class TheftDetector(thresholds: Thresholds = Thresholds()) {

    var thresholds: Thresholds = thresholds
        set(value) {
            field = value
            pruneHistory(lastSampleAtMs)
        }

    private var armed = false
    private var alarming = false
    private var baseline: Baseline? = null
    private var graceUntilMs = 0L
    private var consecutiveBreaches = 0
    private var lastReachable = true
    private var lastSampleAtMs = 0L

    /** Trusted orientation samples, oldest first, used for the "sudden change" comparison. */
    private val history = ArrayDeque<HistoryEntry>()

    private data class HistoryEntry(
        val atMs: Long,
        val azimuthDeg: Float?,
        val elevationDeg: Float?,
        val tiltDeg: Float?,
    )

    val state: MonitorState
        get() = when {
            !armed -> MonitorState.DISARMED
            alarming -> MonitorState.ALARMING
            baseline == null -> MonitorState.ARMING
            !lastReachable -> MonitorState.STALE
            consecutiveBreaches > 0 -> MonitorState.SUSPECT
            else -> MonitorState.ARMED
        }

    val currentBaseline: Baseline? get() = baseline

    /** Starts monitoring. The baseline is captured once the settling window has elapsed. */
    fun arm(nowMs: Long) {
        armed = true
        alarming = false
        baseline = null
        consecutiveBreaches = 0
        lastReachable = true
        graceUntilMs = nowMs + thresholds.armingGraceSec * 1000L
        history.clear()
    }

    fun disarm() {
        armed = false
        alarming = false
        baseline = null
        consecutiveBreaches = 0
        history.clear()
    }

    /**
     * Silences the alarm and re-baselines at wherever the dish is now.
     *
     * Keeping the old baseline would re-trigger on the very next poll, which would make the
     * stop button useless if the dish really has been repositioned.
     */
    fun acknowledgeAlarm(nowMs: Long) {
        if (!armed) return
        alarming = false
        baseline = null
        consecutiveBreaches = 0
        graceUntilMs = nowMs + thresholds.armingGraceSec * 1000L
        history.clear()
    }

    fun snapshot(): DetectorSnapshot =
        DetectorSnapshot(armed, alarming, baseline, graceUntilMs, consecutiveBreaches)

    fun restore(snapshot: DetectorSnapshot) {
        armed = snapshot.armed
        alarming = snapshot.alarming
        baseline = snapshot.baseline
        graceUntilMs = snapshot.graceUntilMs
        consecutiveBreaches = snapshot.consecutiveBreaches
        lastReachable = true
        history.clear()
    }

    fun onSample(sample: Sample): DetectorResult {
        lastSampleAtMs = sample.atMs

        if (!armed) {
            return DetectorResult(MonitorState.DISARMED, suppression = SuppressionReason.DISARMED)
        }

        val status = sample.status
        if (status == null) {
            lastReachable = false
            // The user did not ask for an offline alarm, so an unreachable dish is reported
            // but never sounds the siren. Breach counting pauses rather than resetting: a
            // dropped poll in the middle of a theft should not undo the evidence so far.
            return result(SuppressionReason.DISH_UNREACHABLE)
        }
        lastReachable = true

        if (alarming) {
            // Latched until the user acknowledges.
            return result(SuppressionReason.NONE)
        }

        val orientationTrusted = orientationSuppression(status)

        if (orientationTrusted == SuppressionReason.NONE) {
            history.addLast(HistoryEntry(sample.atMs, status.azimuthDeg, status.elevationDeg, status.tiltDeg))
            pruneHistory(sample.atMs)
        }

        if (sample.atMs < graceUntilMs) {
            return result(SuppressionReason.GRACE_PERIOD)
        }

        val currentBaseline = baseline
        if (currentBaseline == null) {
            // Only anchor to a reading we actually trust, otherwise the whole session is
            // measured against a guess.
            if (orientationTrusted == SuppressionReason.NONE) {
                baseline = Baseline(
                    azimuthDeg = status.azimuthDeg,
                    elevationDeg = status.elevationDeg,
                    tiltDeg = status.tiltDeg,
                    latitude = sample.location?.latitude,
                    longitude = sample.location?.longitude,
                    serial = status.serial,
                    capturedAtMs = sample.atMs,
                )
            }
            return result(if (baseline == null) orientationTrusted else SuppressionReason.NONE)
        }

        // The dish often takes a while to hand out a GPS fix — or never does. If one turns up
        // later while everything is still quiet, anchor to it rather than leaving the position
        // check permanently disabled.
        if (currentBaseline.latitude == null && sample.location != null && consecutiveBreaches == 0) {
            baseline = currentBaseline.copy(
                latitude = sample.location.latitude,
                longitude = sample.location.longitude,
            )
        }

        val triggers = buildList {
            if (orientationTrusted == SuppressionReason.NONE) {
                addAll(orientationTriggers(status, baseline!!, sample.atMs))
            }
            // Position and the dish's own alerts stay meaningful even while the motors run,
            // so they are judged regardless of orientation suppression.
            addAll(positionTriggers(sample.location, baseline!!))
            addAll(alertTriggers(status))
        }

        if (triggers.isEmpty()) {
            consecutiveBreaches = 0
            return result(orientationTrusted)
        }

        consecutiveBreaches++
        if (consecutiveBreaches >= thresholds.confirmSamples) {
            alarming = true
            return DetectorResult(
                state = MonitorState.ALARMING,
                triggers = triggers,
                suppression = SuppressionReason.NONE,
                baseline = baseline,
                consecutiveBreaches = consecutiveBreaches,
            )
        }
        return DetectorResult(
            state = MonitorState.SUSPECT,
            triggers = triggers,
            suppression = SuppressionReason.NONE,
            baseline = baseline,
            consecutiveBreaches = consecutiveBreaches,
        )
    }

    private fun result(suppression: SuppressionReason) = DetectorResult(
        state = state,
        suppression = suppression,
        baseline = baseline,
        consecutiveBreaches = consecutiveBreaches,
    )

    private fun orientationSuppression(status: DishStatus): SuppressionReason = when {
        !status.hasOrientation -> SuppressionReason.NO_ORIENTATION_DATA
        thresholds.requireConvergedAttitude &&
            status.attitudeState != dev.starlinkguard.core.model.AttitudeEstimationState.FILTER_CONVERGED ->
            SuppressionReason.ATTITUDE_UNCONVERGED
        thresholds.suppressWhileActuating && status.actuatorState.isMoving ->
            SuppressionReason.ACTUATORS_MOVING
        else -> SuppressionReason.NONE
    }

    private fun orientationTriggers(
        status: DishStatus,
        baseline: Baseline,
        nowMs: Long,
    ): List<Trigger> = buildList {
        checkAngle(
            TriggerAxis.AZIMUTH, TriggerKind.DRIFT, status.azimuthDeg, baseline.azimuthDeg,
            thresholds.azimuthDeg, circular = true,
        )?.let(::add)
        checkAngle(
            TriggerAxis.ELEVATION, TriggerKind.DRIFT, status.elevationDeg, baseline.elevationDeg,
            thresholds.elevationDeg, circular = false,
        )?.let(::add)
        checkAngle(
            TriggerAxis.TILT, TriggerKind.DRIFT, status.tiltDeg, baseline.tiltDeg,
            thresholds.tiltDeg, circular = false,
        )?.let(::add)

        val reference = suddenReference(nowMs)
        if (reference != null) {
            checkAngle(
                TriggerAxis.AZIMUTH, TriggerKind.SUDDEN, status.azimuthDeg, reference.azimuthDeg,
                thresholds.azimuthDeg, circular = true,
            )?.let(::add)
            checkAngle(
                TriggerAxis.ELEVATION, TriggerKind.SUDDEN, status.elevationDeg, reference.elevationDeg,
                thresholds.elevationDeg, circular = false,
            )?.let(::add)
            checkAngle(
                TriggerAxis.TILT, TriggerKind.SUDDEN, status.tiltDeg, reference.tiltDeg,
                thresholds.tiltDeg, circular = false,
            )?.let(::add)
        }
    }

    private fun checkAngle(
        axis: TriggerAxis,
        kind: TriggerKind,
        current: Float?,
        reference: Float?,
        threshold: Float,
        circular: Boolean,
    ): Trigger? {
        if (current == null || reference == null) return null
        val delta = if (circular) {
            GeoMath.circularDeltaDeg(current, reference)
        } else {
            GeoMath.linearDeltaDeg(current, reference)
        }
        if (delta <= threshold) return null
        return Trigger(
            axis = axis,
            kind = kind,
            referenceValue = reference.toDouble(),
            currentValue = current.toDouble(),
            delta = delta.toDouble(),
            threshold = threshold.toDouble(),
            unit = "°",
        )
    }

    private fun positionTriggers(location: DishLocation?, baseline: Baseline): List<Trigger> {
        if (!thresholds.gpsEnabled) return emptyList()
        if (location == null) return emptyList()
        val baseLat = baseline.latitude ?: return emptyList()
        val baseLon = baseline.longitude ?: return emptyList()
        val meters = GeoMath.haversineMeters(baseLat, baseLon, location.latitude, location.longitude)
        if (meters <= thresholds.gpsMeters) return emptyList()
        return listOf(
            Trigger(
                axis = TriggerAxis.POSITION,
                kind = TriggerKind.DRIFT,
                referenceValue = 0.0,
                currentValue = meters,
                delta = meters,
                threshold = thresholds.gpsMeters,
                unit = "m",
            ),
        )
    }

    private fun alertTriggers(status: DishStatus): List<Trigger> {
        if (!thresholds.useDishMovedAlerts) return emptyList()
        if (!status.alerts.anyMovementRelated) return emptyList()
        return listOf(
            Trigger(
                axis = TriggerAxis.DISH_ALERT,
                kind = TriggerKind.ALERT,
                referenceValue = 0.0,
                currentValue = 1.0,
                delta = 1.0,
                threshold = 0.0,
                unit = "",
            ),
        )
    }

    /** Newest trusted sample that is at least one "sudden" window old, if there is one. */
    private fun suddenReference(nowMs: Long): HistoryEntry? {
        val cutoff = nowMs - thresholds.suddenWindowSec * 1000L
        return history.lastOrNull { it.atMs <= cutoff }
    }

    private fun pruneHistory(nowMs: Long) {
        // Keep a little more than one window so there is always a reference to compare with.
        val keepFrom = nowMs - thresholds.suddenWindowSec * 1000L * 2 - thresholds.pollIntervalSec * 1000L
        while (history.size > 1 && history.first().atMs < keepFrom) {
            history.removeFirst()
        }
    }
}
