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
}
