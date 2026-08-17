package dev.starlinkguard.core.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {

    @Test
    fun `azimuth delta takes the short way round the compass`() {
        // The whole point: a dish pointing just west of north must not look like it swung 358°.
        assertEquals(2f, GeoMath.circularDeltaDeg(359f, 1f), 0.001f)
        assertEquals(2f, GeoMath.circularDeltaDeg(1f, 359f), 0.001f)
        assertEquals(10f, GeoMath.circularDeltaDeg(5f, 355f), 0.001f)
        assertEquals(0f, GeoMath.circularDeltaDeg(0f, 360f), 0.001f)
        assertEquals(180f, GeoMath.circularDeltaDeg(0f, 180f), 0.001f)
        assertEquals(90f, GeoMath.circularDeltaDeg(350f, 80f), 0.001f)
    }

    @Test
    fun `azimuth delta handles values outside 0 to 360`() {
        assertEquals(2f, GeoMath.circularDeltaDeg(-1f, 1f), 0.001f)
        assertEquals(5f, GeoMath.circularDeltaDeg(725f, 0f), 0.001f)
    }

    @Test
    fun `linear delta is a plain absolute difference`() {
        assertEquals(7.5f, GeoMath.linearDeltaDeg(62f, 54.5f), 0.001f)
        assertEquals(7.5f, GeoMath.linearDeltaDeg(54.5f, 62f), 0.001f)
    }

    @Test
    fun `haversine matches known distances`() {
        // One degree of latitude is about 111 km anywhere on Earth.
        assertEquals(111_195.0, GeoMath.haversineMeters(0.0, 0.0, 1.0, 0.0), 500.0)
        // Seattle to Portland, roughly 233 km.
        assertEquals(233_000.0, GeoMath.haversineMeters(47.6062, -122.3321, 45.5152, -122.6784), 3_000.0)
        assertEquals(0.0, GeoMath.haversineMeters(47.6062, -122.3321, 47.6062, -122.3321), 0.001)
    }

    @Test
    fun `haversine resolves the small distances the alarm cares about`() {
        // ~100 m north at this latitude; the default position threshold is 50 m.
        val meters = GeoMath.haversineMeters(47.6062, -122.3321, 47.6071, -122.3321)
        assertEquals(100.0, meters, 5.0)
    }

    @Test
    fun `haversine works across the antimeridian`() {
        val meters = GeoMath.haversineMeters(0.0, 179.999, 0.0, -179.999)
        assertEquals(222.0, meters, 20.0)
    }
}
