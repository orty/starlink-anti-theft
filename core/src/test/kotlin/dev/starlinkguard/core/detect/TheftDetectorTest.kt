package dev.starlinkguard.core.detect

import dev.starlinkguard.core.model.ActuatorState
import dev.starlinkguard.core.model.AttitudeEstimationState
import dev.starlinkguard.core.model.DishAlerts
import dev.starlinkguard.core.model.DishLocation
import dev.starlinkguard.core.model.DishStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TheftDetectorTest {

    private val start = 1_700_000_000_000L

    private fun status(
        azimuth: Float? = 100f,
        elevation: Float? = 60f,
        tilt: Float? = 5f,
        attitude: AttitudeEstimationState = AttitudeEstimationState.FILTER_CONVERGED,
        actuator: ActuatorState = ActuatorState.ACTUATOR_STATE_IDLE,
        alerts: DishAlerts = DishAlerts(),
    ) = DishStatus(
        serial = "ut-test",
        azimuthDeg = azimuth,
        elevationDeg = elevation,
        tiltDeg = tilt,
        attitudeState = attitude,
        actuatorState = actuator,
        alerts = alerts,
    )

    /** Arms the detector and feeds enough quiet polls to get past the grace window. */
    private fun armed(
        thresholds: Thresholds = Thresholds(),
        location: DishLocation? = null,
    ): Pair<TheftDetector, Long> {
        val detector = TheftDetector(thresholds)
        detector.arm(start)
        var t = start
        // One poll inside the grace window, then one after it to capture the baseline.
        detector.onSample(Sample(t, status(), location))
        t = start + thresholds.armingGraceSec * 1000L + 1000L
        detector.onSample(Sample(t, status(), location))
        assertEquals(MonitorState.ARMED, detector.state)
        assertNotNull(detector.currentBaseline)
        return detector to t
    }

    @Test
    fun `starts disarmed and ignores samples`() {
        val detector = TheftDetector()
        val result = detector.onSample(Sample(start, status()))
        assertEquals(MonitorState.DISARMED, result.state)
        assertEquals(SuppressionReason.DISARMED, result.suppression)
        assertTrue(result.triggers.isEmpty())
    }

    @Test
    fun `nothing can trigger during the arming grace window`() {
        val detector = TheftDetector(Thresholds(armingGraceSec = 30))
        detector.arm(start)

        // A wild swing one second in — still settling, so it must be ignored.
        val result = detector.onSample(Sample(start + 1_000, status(azimuth = 300f)))

        assertEquals(MonitorState.ARMING, result.state)
        assertEquals(SuppressionReason.GRACE_PERIOD, result.suppression)
        assertTrue(result.triggers.isEmpty())
        assertNull(detector.currentBaseline)
    }

    @Test
    fun `baseline is captured from the first trusted sample after the grace window`() {
        val (detector, _) = armed()
        val baseline = detector.currentBaseline!!
        assertEquals(100f, baseline.azimuthDeg!!, 0.001f)
        assertEquals(60f, baseline.elevationDeg!!, 0.001f)
        assertEquals(5f, baseline.tiltDeg!!, 0.001f)
        assertEquals("ut-test", baseline.serial)
    }

    @Test
    fun `a dish sitting still never triggers`() {
        val (detector, t0) = armed()
        var t = t0
        repeat(20) {
            t += 10_000
            // Sub-degree jitter, which is what a bolted-down dish actually reports.
            val result = detector.onSample(Sample(t, status(azimuth = 100.2f, elevation = 59.9f, tilt = 5.05f)))
            assertTrue("unexpected trigger: ${result.triggers}", result.triggers.isEmpty())
        }
        assertEquals(MonitorState.ARMED, detector.state)
    }

    @Test
    fun `a single breach is suspect, a confirmed breach alarms`() {
        val (detector, t0) = armed(Thresholds(confirmSamples = 2))

        val first = detector.onSample(Sample(t0 + 10_000, status(azimuth = 140f)))
        assertEquals(MonitorState.SUSPECT, first.state)
        assertFalse(first.alarmStarted)
        assertTrue(first.triggers.isNotEmpty())

        val second = detector.onSample(Sample(t0 + 20_000, status(azimuth = 140f)))
        assertEquals(MonitorState.ALARMING, second.state)
        assertTrue(second.alarmStarted)
    }

    @Test
    fun `a lone bad reading does not alarm and clears the suspicion`() {
        val (detector, t0) = armed(Thresholds(confirmSamples = 2))

        detector.onSample(Sample(t0 + 10_000, status(azimuth = 140f)))
        val recovered = detector.onSample(Sample(t0 + 20_000, status(azimuth = 100f)))

        assertEquals(MonitorState.ARMED, recovered.state)
        assertEquals(0, recovered.consecutiveBreaches)
    }

    @Test
    fun `elevation and tilt each trigger on their own`() {
        val (byElevation, e0) = armed(Thresholds(confirmSamples = 1))
        val elevationResult = byElevation.onSample(Sample(e0 + 10_000, status(elevation = 40f)))
        assertEquals(MonitorState.ALARMING, elevationResult.state)
        assertTrue(elevationResult.triggers.any { it.axis == TriggerAxis.ELEVATION })

        val (byTilt, t0) = armed(Thresholds(confirmSamples = 1))
        val tiltResult = byTilt.onSample(Sample(t0 + 10_000, status(tilt = 45f)))
        assertEquals(MonitorState.ALARMING, tiltResult.state)
        assertTrue(tiltResult.triggers.any { it.axis == TriggerAxis.TILT })
    }

    @Test
    fun `azimuth wraparound does not cause a false alarm`() {
        // Dish parked pointing just east of north, wobbling across the 0 degree line.
        val detector = TheftDetector(Thresholds(confirmSamples = 1, armingGraceSec = 0))
        detector.arm(start)
        detector.onSample(Sample(start, status(azimuth = 1f)))
        assertNotNull(detector.currentBaseline)

        val result = detector.onSample(Sample(start + 10_000, status(azimuth = 357f)))

        assertTrue("4 degrees across north must not trigger: ${result.triggers}", result.triggers.isEmpty())
    }

    @Test
    fun `orientation is ignored while the dish is re-aiming itself`() {
        // The main false-alarm defence: motorised dishes move on their own.
        val (detector, t0) = armed(Thresholds(confirmSamples = 1, suppressWhileActuating = true))

        val result = detector.onSample(
            Sample(t0 + 10_000, status(azimuth = 200f, actuator = ActuatorState.ACTUATOR_STATE_ROTATE)),
        )

        assertEquals(SuppressionReason.ACTUATORS_MOVING, result.suppression)
        assertTrue(result.triggers.isEmpty())
        assertEquals(MonitorState.ARMED, result.state)
    }

    @Test
    fun `motor suppression can be turned off`() {
        val (detector, t0) = armed(Thresholds(confirmSamples = 1, suppressWhileActuating = false))

        val result = detector.onSample(
            Sample(t0 + 10_000, status(azimuth = 200f, actuator = ActuatorState.ACTUATOR_STATE_ROTATE)),
        )

        assertEquals(MonitorState.ALARMING, result.state)
    }

    @Test
    fun `orientation is ignored until the attitude filter converges`() {
        val (detector, t0) = armed(Thresholds(confirmSamples = 1))

        val result = detector.onSample(
            Sample(t0 + 10_000, status(azimuth = 200f, attitude = AttitudeEstimationState.FILTER_UNCONVERGED)),
        )

        assertEquals(SuppressionReason.ATTITUDE_UNCONVERGED, result.suppression)
        assertTrue(result.triggers.isEmpty())
    }

    @Test
    fun `an unconverged dish never produces a baseline`() {
        val detector = TheftDetector(Thresholds(armingGraceSec = 0))
        detector.arm(start)
        val result = detector.onSample(
            Sample(start, status(attitude = AttitudeEstimationState.FILTER_UNCONVERGED)),
        )
        assertNull(detector.currentBaseline)
        assertEquals(MonitorState.ARMING, result.state)
        assertEquals(SuppressionReason.ATTITUDE_UNCONVERGED, result.suppression)
    }

    @Test
    fun `an unreachable dish is reported without sounding the alarm`() {
        val (detector, t0) = armed()

        val result = detector.onSample(Sample(t0 + 10_000, status = null))

        assertEquals(MonitorState.STALE, result.state)
        assertEquals(SuppressionReason.DISH_UNREACHABLE, result.suppression)
        assertTrue(result.triggers.isEmpty())
    }

    @Test
    fun `a dropped poll mid theft does not wipe the accumulated evidence`() {
        val (detector, t0) = armed(Thresholds(confirmSamples = 2))

        detector.onSample(Sample(t0 + 10_000, status(azimuth = 140f)))   // breach 1
        detector.onSample(Sample(t0 + 20_000, status = null))            // dish drops out
        val result = detector.onSample(Sample(t0 + 30_000, status(azimuth = 140f))) // breach 2

        assertEquals(MonitorState.ALARMING, result.state)
    }

    @Test
    fun `a fast swing is reported as a sudden change, not just as drift`() {
        val thresholds = Thresholds(
            confirmSamples = 1,
            pollIntervalSec = 10,
            suddenWindowSec = 30,
            azimuthDeg = 10f,
        )
        val (detector, t0) = armed(thresholds)

        // Sit still long enough that the look-back window has a reference to compare against.
        var t = t0
        repeat(3) {
            t += 10_000
            assertTrue(detector.onSample(Sample(t, status())).triggers.isEmpty())
        }

        // Someone swings the dish round.
        t += 10_000
        val result = detector.onSample(Sample(t, status(azimuth = 150f)))

        assertEquals(MonitorState.ALARMING, result.state)
        assertTrue(
            "expected a SUDDEN trigger, got ${result.triggers}",
            result.triggers.any { it.axis == TriggerAxis.AZIMUTH && it.kind == TriggerKind.SUDDEN },
        )
        assertTrue(
            "expected a DRIFT trigger too, got ${result.triggers}",
            result.triggers.any { it.axis == TriggerAxis.AZIMUTH && it.kind == TriggerKind.DRIFT },
        )
    }

    @Test
    fun `the look back reference is ignored until the window has actually elapsed`() {
        // Before enough history exists, only the baseline comparison should be in play,
        // otherwise the first poll after arming would compare against itself.
        val thresholds = Thresholds(confirmSamples = 1, pollIntervalSec = 10, suddenWindowSec = 300)
        val (detector, t0) = armed(thresholds)

        val result = detector.onSample(Sample(t0 + 10_000, status(azimuth = 150f)))

        assertTrue(result.triggers.all { it.kind == TriggerKind.DRIFT })
    }

    @Test
    fun `gps movement beyond the radius triggers`() {
        val here = DishLocation(47.6062, -122.3321)
        val (detector, t0) = armed(Thresholds(confirmSamples = 1, gpsMeters = 50.0), location = here)
        assertEquals(47.6062, detector.currentBaseline!!.latitude!!, 0.000001)

        // ~1 km north.
        val moved = DishLocation(47.6152, -122.3321)
        val result = detector.onSample(Sample(t0 + 10_000, status(), moved))

        val trigger = result.triggers.single { it.axis == TriggerAxis.POSITION }
        assertEquals(MonitorState.ALARMING, result.state)
        assertTrue(trigger.delta > 900.0)
        assertEquals("m", trigger.unit)
    }

    @Test
    fun `gps jitter inside the radius does not trigger`() {
        val here = DishLocation(47.6062, -122.3321)
        val (detector, t0) = armed(Thresholds(confirmSamples = 1, gpsMeters = 50.0), location = here)

        // ~11 m of consumer-GPS wander.
        val jittered = DishLocation(47.60630, -122.33210)
        val result = detector.onSample(Sample(t0 + 10_000, status(), jittered))

        assertTrue(result.triggers.isEmpty())
    }

    @Test
    fun `position is still judged while the motors are running`() {
        // Suppression protects orientation only. A dish driving away is still a dish leaving.
        val here = DishLocation(47.6062, -122.3321)
        val (detector, t0) = armed(Thresholds(confirmSamples = 1, gpsMeters = 50.0), location = here)

        val result = detector.onSample(
            Sample(
                t0 + 10_000,
                status(azimuth = 200f, actuator = ActuatorState.ACTUATOR_STATE_ROTATE),
                DishLocation(47.6152, -122.3321),
            ),
        )

        assertEquals(MonitorState.ALARMING, result.state)
        assertTrue(result.triggers.all { it.axis == TriggerAxis.POSITION })
    }

    @Test
    fun `a fix that only arrives later still anchors the position check`() {
        val (detector, t0) = armed(Thresholds(confirmSamples = 1, gpsMeters = 50.0), location = null)
        assertNull(detector.currentBaseline!!.latitude)

        // GPS shows up a few polls in.
        detector.onSample(Sample(t0 + 10_000, status(), DishLocation(47.6062, -122.3321)))
        assertEquals(47.6062, detector.currentBaseline!!.latitude!!, 0.000001)

        val result = detector.onSample(Sample(t0 + 20_000, status(), DishLocation(47.6152, -122.3321)))
        assertTrue(result.triggers.any { it.axis == TriggerAxis.POSITION })
    }

    @Test
    fun `no gps means no position triggers and no crash`() {
        val (detector, t0) = armed(Thresholds(confirmSamples = 1))
        val result = detector.onSample(Sample(t0 + 10_000, status(), location = null))
        assertTrue(result.triggers.isEmpty())
        assertEquals(MonitorState.ARMED, result.state)
    }

    @Test
    fun `gps checking can be disabled entirely`() {
        val here = DishLocation(47.6062, -122.3321)
        val (detector, t0) = armed(Thresholds(confirmSamples = 1, gpsEnabled = false), location = here)

        val result = detector.onSample(Sample(t0 + 10_000, status(), DishLocation(47.7, -122.3321)))

        assertTrue(result.triggers.isEmpty())
    }

    @Test
    fun `dish movement alerts are off by default and can be opted into`() {
        val moved = DishAlerts(unexpectedLocation = true)

        val (quiet, q0) = armed(Thresholds(confirmSamples = 1))
        assertTrue(quiet.onSample(Sample(q0 + 10_000, status(alerts = moved))).triggers.isEmpty())

        val (loud, l0) = armed(Thresholds(confirmSamples = 1, useDishMovedAlerts = true))
        val result = loud.onSample(Sample(l0 + 10_000, status(alerts = moved)))
        assertEquals(MonitorState.ALARMING, result.state)
        assertTrue(result.triggers.any { it.axis == TriggerAxis.DISH_ALERT })
    }

    @Test
    fun `the alarm latches until it is acknowledged`() {
        val (detector, t0) = armed(Thresholds(confirmSamples = 1))
        detector.onSample(Sample(t0 + 10_000, status(azimuth = 200f)))
        assertEquals(MonitorState.ALARMING, detector.state)

        // Even if the thief puts it back, the alarm keeps sounding.
        val stillAlarming = detector.onSample(Sample(t0 + 20_000, status(azimuth = 100f)))
        assertEquals(MonitorState.ALARMING, stillAlarming.state)
    }

    @Test
    fun `acknowledging re-baselines at the new position instead of instantly re-alarming`() {
        val thresholds = Thresholds(confirmSamples = 1, armingGraceSec = 30)
        val (detector, t0) = armed(thresholds)
        detector.onSample(Sample(t0 + 10_000, status(azimuth = 200f)))
        assertEquals(MonitorState.ALARMING, detector.state)

        val ackAt = t0 + 20_000
        detector.acknowledgeAlarm(ackAt)
        assertEquals(MonitorState.ARMING, detector.state)

        // After the settling window it anchors to where the dish now points, and stays quiet.
        val settled = ackAt + 31_000
        detector.onSample(Sample(settled, status(azimuth = 200f)))
        assertEquals(200f, detector.currentBaseline!!.azimuthDeg!!, 0.001f)
        val result = detector.onSample(Sample(settled + 10_000, status(azimuth = 200f)))
        assertEquals(MonitorState.ARMED, result.state)
        assertTrue(result.triggers.isEmpty())
    }

    @Test
    fun `disarming stops everything`() {
        val (detector, t0) = armed(Thresholds(confirmSamples = 1))
        detector.disarm()
        assertEquals(MonitorState.DISARMED, detector.state)
        assertNull(detector.currentBaseline)
        assertTrue(detector.onSample(Sample(t0 + 10_000, status(azimuth = 200f))).triggers.isEmpty())
    }

    @Test
    fun `state survives a snapshot and restore`() {
        val (detector, t0) = armed(Thresholds(confirmSamples = 1))
        val snapshot = detector.snapshot()

        // Stand in for the service being killed and restarted.
        val revived = TheftDetector(Thresholds(confirmSamples = 1))
        revived.restore(snapshot)

        assertEquals(MonitorState.ARMED, revived.state)
        assertEquals(100f, revived.currentBaseline!!.azimuthDeg!!, 0.001f)
        val result = revived.onSample(Sample(t0 + 10_000, status(azimuth = 200f)))
        assertEquals(MonitorState.ALARMING, result.state)
    }

    @Test
    fun `a dish that reports no orientation at all is reported as such`() {
        val detector = TheftDetector(Thresholds(armingGraceSec = 0))
        detector.arm(start)
        val result = detector.onSample(
            Sample(start, status(azimuth = null, elevation = null, tilt = null)),
        )
        assertEquals(SuppressionReason.NO_ORIENTATION_DATA, result.suppression)
        assertNull(detector.currentBaseline)
    }

    @Test
    fun `trigger descriptions are human readable`() {
        val (detector, t0) = armed(Thresholds(confirmSamples = 1))
        val result = detector.onSample(Sample(t0 + 10_000, status(azimuth = 140f)))
        val text = result.triggers.first().describe()
        assertTrue(text, text.contains("Azimuth"))
        assertTrue(text, text.contains("40.0°"))
    }

    // --- Offline / lost-signal trigger --------------------------------------------------

    private val offline = Thresholds(offlineEnabled = true, offlineGraceSec = 180)

    @Test
    fun `an unreachable dish does not alarm when the offline trigger is off`() {
        val (detector, t0) = armed()
        var result = detector.onSample(Sample(t0 + 10_000, status = null))
        result = detector.onSample(Sample(t0 + 3_600_000, status = null))
        assertTrue(result.triggers.isEmpty())
        assertEquals(SuppressionReason.DISH_UNREACHABLE, result.suppression)
        assertEquals(MonitorState.STALE, result.state)
    }

    @Test
    fun `an unreachable dish alarms once the outage outlasts the grace period`() {
        val (detector, t0) = armed(offline)

        val duringGrace = detector.onSample(Sample(t0 + 10_000, status = null))
        assertTrue(duringGrace.triggers.isEmpty())
        assertEquals(MonitorState.STALE, duringGrace.state)

        val justBefore = detector.onSample(Sample(t0 + 10_000 + 179_000, status = null))
        assertTrue("179s of outage is still inside the grace period", justBefore.triggers.isEmpty())

        val past = detector.onSample(Sample(t0 + 10_000 + 181_000, status = null))
        assertEquals(MonitorState.ALARMING, past.state)
        assertEquals(1, past.triggers.size)
        assertEquals(TriggerAxis.CONNECTION, past.triggers[0].axis)
        assertEquals(TriggerKind.OFFLINE, past.triggers[0].kind)
    }

    @Test
    fun `a brief outage does not alarm and the clock restarts when the dish returns`() {
        val (detector, t0) = armed(offline)

        detector.onSample(Sample(t0 + 10_000, status = null))
        detector.onSample(Sample(t0 + 100_000, status = null))
        // Dish comes back before the grace period expires.
        val back = detector.onSample(Sample(t0 + 120_000, status()))
        assertTrue(back.triggers.isEmpty())

        // A second outage must be timed from scratch, not from the first one.
        detector.onSample(Sample(t0 + 130_000, status = null))
        val result = detector.onSample(Sample(t0 + 130_000 + 179_000, status = null))
        assertTrue("the outage clock should have restarted", result.triggers.isEmpty())
    }

    @Test
    fun `losing wifi never alarms, however long it lasts`() {
        val (detector, t0) = armed(offline)

        var result = detector.onSample(
            Sample(t0 + 10_000, status = null, dishNetworkAvailable = false),
        )
        assertEquals(SuppressionReason.NO_DISH_NETWORK, result.suppression)

        // The owner is away for an hour. This must never be read as a theft.
        result = detector.onSample(
            Sample(t0 + 3_600_000, status = null, dishNetworkAvailable = false),
        )
        assertTrue(result.triggers.isEmpty())
        assertEquals(SuppressionReason.NO_DISH_NETWORK, result.suppression)
    }

    @Test
    fun `an outage is timed from the return of wifi, not from when it was lost`() {
        val (detector, t0) = armed(offline)

        // Off-network for an hour...
        detector.onSample(Sample(t0 + 10_000, status = null, dishNetworkAvailable = false))
        detector.onSample(Sample(t0 + 3_600_000, status = null, dishNetworkAvailable = false))

        // ...then back on Wi-Fi with the dish still silent. The hour away must not count.
        val justBack = detector.onSample(Sample(t0 + 3_610_000, status = null))
        assertTrue(justBack.triggers.isEmpty())

        val later = detector.onSample(Sample(t0 + 3_610_000 + 181_000, status = null))
        assertEquals(MonitorState.ALARMING, later.state)
    }

    @Test
    fun `acknowledging an offline alarm restarts the outage clock`() {
        val (detector, t0) = armed(offline)
        detector.onSample(Sample(t0 + 10_000, status = null))
        val alarm = detector.onSample(Sample(t0 + 200_000, status = null))
        assertEquals(MonitorState.ALARMING, alarm.state)

        detector.acknowledgeAlarm(t0 + 210_000)

        // Still offline, but the alarm must not immediately fire again.
        val after = detector.onSample(Sample(t0 + 220_000, status = null))
        assertTrue(after.triggers.isEmpty())
        assertEquals(MonitorState.STALE, after.state)
    }

    @Test
    fun `a restored detector does not count downtime it never observed`() {
        val (detector, t0) = armed(offline)
        detector.onSample(Sample(t0 + 10_000, status = null))
        val snapshot = detector.snapshot()

        val revived = TheftDetector(offline)
        revived.restore(snapshot)

        // Process was dead for a day; that is not evidence of a theft.
        val result = revived.onSample(Sample(t0 + 86_400_000, status = null))
        assertTrue(result.triggers.isEmpty())
    }

    @Test
    fun `offlineGraceSec cannot be negative`() {
        try {
            Thresholds(offlineGraceSec = -1)
            throw AssertionError("expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // as intended
        }
    }

    // --- Which network the outage happened on -------------------------------------------

    /** Arms, then establishes that the dish lives on network "home". */
    private fun armedOnHome(): Pair<TheftDetector, Long> {
        val detector = TheftDetector(offline)
        detector.arm(start)
        var t = start
        detector.onSample(Sample(t, status(), networkId = HOME))
        t += Thresholds().armingGraceSec * 1000L + 1_000L
        detector.onSample(Sample(t, status(), networkId = HOME))
        return detector to t
    }

    @Test
    fun `a silent dish on the network it lives on alarms`() {
        val (detector, t0) = armedOnHome()
        detector.onSample(Sample(t0 + 10_000, status = null, networkId = HOME))
        val result = detector.onSample(Sample(t0 + 10_000 + 181_000, status = null, networkId = HOME))
        assertEquals(MonitorState.ALARMING, result.state)
        assertEquals(TriggerAxis.CONNECTION, result.triggers.single().axis)
    }

    @Test
    fun `another wifi never alarms, because the dish was never reachable there`() {
        val (detector, t0) = armedOnHome()

        // The owner is at a cafe. Wi-Fi is up, the dish is silent, and neither fact is related.
        var result = detector.onSample(Sample(t0 + 10_000, status = null, networkId = CAFE))
        assertEquals(SuppressionReason.UNFAMILIAR_NETWORK, result.suppression)

        result = detector.onSample(Sample(t0 + 3_600_000, status = null, networkId = CAFE))
        assertTrue(result.triggers.isEmpty())
        assertEquals(SuppressionReason.UNFAMILIAR_NETWORK, result.suppression)
    }

    @Test
    fun `time spent on another wifi does not count towards the outage`() {
        val (detector, t0) = armedOnHome()

        detector.onSample(Sample(t0 + 10_000, status = null, networkId = CAFE))
        detector.onSample(Sample(t0 + 3_600_000, status = null, networkId = CAFE))

        // Home again, dish still silent: the hour away must not count.
        val justBack = detector.onSample(Sample(t0 + 3_610_000, status = null, networkId = HOME))
        assertTrue(justBack.triggers.isEmpty())

        val later = detector.onSample(Sample(t0 + 3_610_000 + 181_000, status = null, networkId = HOME))
        assertEquals(MonitorState.ALARMING, later.state)
    }

    @Test
    fun `an outage seen before the dish ever answered is not judged`() {
        // Armed while already away from home: the dish has never been reached on this network.
        val detector = TheftDetector(offline)
        detector.arm(start)
        val t = start + Thresholds().armingGraceSec * 1000L + 1_000L

        detector.onSample(Sample(t, status = null, networkId = CAFE))
        val result = detector.onSample(Sample(t + 3_600_000, status = null, networkId = CAFE))
        assertTrue(result.triggers.isEmpty())
    }

    @Test
    fun `acknowledging keeps the dish's home network, so the dashboard stays truthful`() {
        val (detector, t0) = armedOnHome()
        detector.onSample(Sample(t0 + 10_000, status = null, networkId = HOME))
        assertEquals(
            MonitorState.ALARMING,
            detector.onSample(Sample(t0 + 200_000, status = null, networkId = HOME)).state,
        )

        detector.acknowledgeAlarm(t0 + 210_000)

        // Acknowledging opens a fresh settling window first.
        val settling = detector.onSample(Sample(t0 + 220_000, status = null, networkId = HOME))
        assertEquals(SuppressionReason.GRACE_PERIOD, settling.suppression)

        // Past it, the dish is still known to live on this network — so this reads as a plain
        // outage rather than as an unfamiliar network, and it has not re-alarmed.
        val after = detector.onSample(
            Sample(t0 + 210_000 + Thresholds().armingGraceSec * 1000L + 1_000L, status = null, networkId = HOME),
        )
        assertEquals(SuppressionReason.DISH_UNREACHABLE, after.suppression)
        assertTrue(after.triggers.isEmpty())
    }

    // --- Router-in-dish hardware (Starlink Mini) -----------------------------------------

    private val mini = Thresholds(
        offlineEnabled = true,
        offlineGraceSec = 180,
        offlineWhenWifiLost = true,
        offlineWhenNetworkChanged = true,
    )

    private fun armedOn(thresholds: Thresholds, networkId: String?): Pair<TheftDetector, Long> {
        val detector = TheftDetector(thresholds)
        detector.arm(start)
        var t = start
        detector.onSample(Sample(t, status(), networkId = networkId))
        t += Thresholds().armingGraceSec * 1000L + 1_000L
        detector.onSample(Sample(t, status(), networkId = networkId))
        return detector to t
    }

    @Test
    fun `losing the wifi alarms when the dish is the router`() {
        val (detector, t0) = armedOn(mini, HOME)

        // The Mini is unplugged, so its network disappears with it.
        detector.onSample(Sample(t0 + 10_000, status = null, dishNetworkAvailable = false))
        val result = detector.onSample(
            Sample(t0 + 10_000 + 181_000, status = null, dishNetworkAvailable = false),
        )
        assertEquals(MonitorState.ALARMING, result.state)
        assertEquals(TriggerKind.OFFLINE, result.triggers.single().kind)
    }

    @Test
    fun `falling back to another wifi alarms when the dish is the router`() {
        val (detector, t0) = armedOn(mini, HOME)

        // The Mini dies and the phone drops onto household broadband instead.
        detector.onSample(Sample(t0 + 10_000, status = null, networkId = CAFE))
        val result = detector.onSample(Sample(t0 + 10_000 + 181_000, status = null, networkId = CAFE))
        assertEquals(MonitorState.ALARMING, result.state)
    }

    @Test
    fun `the same events stay quiet on a separate-router setup`() {
        val separateRouter = Thresholds(offlineEnabled = true, offlineGraceSec = 180)
        val (a, ta) = armedOn(separateRouter, HOME)
        a.onSample(Sample(ta + 10_000, status = null, dishNetworkAvailable = false))
        assertTrue(
            a.onSample(Sample(ta + 3_600_000, status = null, dishNetworkAvailable = false)).triggers.isEmpty(),
        )

        val (b, tb) = armedOn(separateRouter, HOME)
        b.onSample(Sample(tb + 10_000, status = null, networkId = CAFE))
        assertTrue(b.onSample(Sample(tb + 3_600_000, status = null, networkId = CAFE)).triggers.isEmpty())
    }

    @Test
    fun `each relaxation is independent of the other`() {
        val wifiLossOnly = Thresholds(
            offlineEnabled = true,
            offlineGraceSec = 180,
            offlineWhenWifiLost = true,
        )
        val (detector, t0) = armedOn(wifiLossOnly, HOME)

        // Another Wi-Fi is still not grounds to alarm...
        detector.onSample(Sample(t0 + 10_000, status = null, networkId = CAFE))
        val other = detector.onSample(Sample(t0 + 3_600_000, status = null, networkId = CAFE))
        assertEquals(SuppressionReason.UNFAMILIAR_NETWORK, other.suppression)
        assertTrue(other.triggers.isEmpty())

        // ...but losing Wi-Fi entirely is.
        detector.onSample(Sample(t0 + 3_610_000, status = null, dishNetworkAvailable = false))
        val lost = detector.onSample(
            Sample(t0 + 3_610_000 + 181_000, status = null, dishNetworkAvailable = false),
        )
        assertEquals(MonitorState.ALARMING, lost.state)
    }

    @Test
    fun `a dish that never answered is never judged, even in mini mode`() {
        val detector = TheftDetector(mini)
        detector.arm(start)
        val t = start + Thresholds().armingGraceSec * 1000L + 1_000L

        detector.onSample(Sample(t, status = null, dishNetworkAvailable = false))
        val result = detector.onSample(Sample(t + 3_600_000, status = null, dishNetworkAvailable = false))
        assertTrue("nothing to compare against without a first contact", result.triggers.isEmpty())
    }

    private companion object {
        const val HOME = "wifi-home"
        const val CAFE = "wifi-cafe"
    }
}