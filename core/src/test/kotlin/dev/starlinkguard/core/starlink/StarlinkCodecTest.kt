package dev.starlinkguard.core.starlink

import dev.starlinkguard.core.Fixtures
import dev.starlinkguard.core.Msg
import dev.starlinkguard.core.model.ActuatorState
import dev.starlinkguard.core.model.AttitudeEstimationState
import dev.starlinkguard.core.model.MobilityClass
import dev.starlinkguard.core.model.PositionSource
import dev.starlinkguard.core.proto.ProtoParseException
import dev.starlinkguard.core.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarlinkCodecTest {

    @Test
    fun `get_status request matches the documented wire bytes`() {
        assertEquals("E2 3E 00", StarlinkCodec.encodeGetStatusRequest().toHex())
    }

    @Test
    fun `get_location request matches the documented wire bytes`() {
        assertEquals("CA 3F 00", StarlinkCodec.encodeGetLocationRequest().toHex())
    }

    @Test
    fun `decodes a full dish status`() {
        val status = StarlinkCodec.decodeStatusResponse(Fixtures.statusResponse())

        assertEquals("ut01000000-00000000-00000000", status.serial)
        assertEquals(12.5f, status.azimuthDeg!!, 0.001f)
        assertEquals(62.0f, status.elevationDeg!!, 0.001f)
        assertEquals(3.25f, status.tiltDeg!!, 0.001f)
        assertEquals(AttitudeEstimationState.FILTER_CONVERGED, status.attitudeState)
        assertEquals(ActuatorState.ACTUATOR_STATE_IDLE, status.actuatorState)
        assertEquals(MobilityClass.STATIONARY, status.mobilityClass)
        assertEquals(true, status.hasActuators)
        assertEquals(true, status.gpsValid)
        assertEquals(11, status.gpsSats)
        assertEquals(false, status.stowRequested)
        assertTrue(status.hasOrientation)
    }

    @Test
    fun `alignment stats win over the top level pointing fields`() {
        // Real dishes report both; AlignmentStats is the authoritative pair.
        val bytes = Fixtures.statusResponse(
            azimuthDeg = 100f,
            elevationDeg = 50f,
            topLevelAzimuth = 1f,
            topLevelElevation = 2f,
        )
        val status = StarlinkCodec.decodeStatusResponse(bytes)
        assertEquals(100f, status.azimuthDeg!!, 0.001f)
        assertEquals(50f, status.elevationDeg!!, 0.001f)
    }

    @Test
    fun `falls back to the top level pointing fields when alignment stats are absent`() {
        val bytes = Fixtures.statusResponse(
            includeAlignmentStats = false,
            topLevelAzimuth = 33.5f,
            topLevelElevation = 71.25f,
        )
        val status = StarlinkCodec.decodeStatusResponse(bytes)
        assertEquals(33.5f, status.azimuthDeg!!, 0.001f)
        assertEquals(71.25f, status.elevationDeg!!, 0.001f)
        assertNull(status.tiltDeg)
        assertEquals(AttitudeEstimationState.UNKNOWN, status.attitudeState)
    }

    @Test
    fun `decodes the movement related alerts`() {
        val status = StarlinkCodec.decodeStatusResponse(
            Fixtures.statusResponse(unexpectedLocation = true, mastNotNearVertical = true),
        )
        assertTrue(status.alerts.unexpectedLocation)
        assertTrue(status.alerts.mastNotNearVertical)
        assertTrue(status.alerts.anyMovementRelated)
    }

    @Test
    fun `unrecognised enum values decode to UNKNOWN rather than throwing`() {
        // Guards against a firmware update adding a new actuator state.
        val status = StarlinkCodec.decodeStatusResponse(
            Fixtures.statusResponse(actuatorState = 99, attitudeState = 77),
        )
        assertEquals(ActuatorState.UNKNOWN, status.actuatorState)
        assertEquals(AttitudeEstimationState.UNKNOWN, status.attitudeState)
    }

    @Test(expected = ProtoParseException::class)
    fun `a response with no dish status is an error`() {
        // A well-formed envelope carrying some other oneof member.
        val bytes = Msg().message(3004, Msg().varint(1, 1)).build()
        StarlinkCodec.decodeStatusResponse(bytes)
    }

    @Test
    fun `decodes a location response`() {
        val location = StarlinkCodec.decodeLocationResponse(Fixtures.locationResponse())!!
        assertEquals(47.6062, location.latitude, 0.000001)
        assertEquals(-122.3321, location.longitude, 0.000001)
        assertEquals(56.0, location.altitudeMeters!!, 0.001)
        assertEquals(3.5, location.sigmaMeters!!, 0.001)
        assertEquals(PositionSource.GPS, location.source)
    }

    @Test
    fun `a null island fix is treated as no fix`() {
        // A dish without a fix reports 0,0 rather than omitting the field.
        assertNull(StarlinkCodec.decodeLocationResponse(Fixtures.locationResponse(lat = 0.0, lon = 0.0)))
    }

    @Test
    fun `a location response with no lla is treated as no fix`() {
        val bytes = Msg().message(1017, Msg().varint(3, 1)).build()
        assertNull(StarlinkCodec.decodeLocationResponse(bytes))
    }
}
