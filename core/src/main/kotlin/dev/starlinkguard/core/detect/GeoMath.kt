package dev.starlinkguard.core.detect

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {

    private const val EARTH_RADIUS_METERS = 6_371_008.8

    /**
     * Smallest angle between two compass bearings, in degrees, always in `0..180`.
     *
     * Azimuth wraps, so a dish sitting at 359° that drifts to 1° has moved two degrees, not
     * 358. Comparing raw differences would make every dish pointing near north look stolen.
     */
    fun circularDeltaDeg(a: Float, b: Float): Float {
        val diff = ((a - b) % 360f + 540f) % 360f - 180f
        return abs(diff)
    }

    /** Plain absolute difference, for axes that do not wrap (elevation and tilt). */
    fun linearDeltaDeg(a: Float, b: Float): Float = abs(a - b)

    /** Great-circle distance in metres. */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }
}
