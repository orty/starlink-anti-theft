package dev.starlinkguard.core

import dev.starlinkguard.core.starlink.StarlinkFields
import java.io.ByteArrayOutputStream

/**
 * A tiny protobuf builder used to synthesise dish replies.
 *
 * There is no live dish in CI, so every decoder test is driven by bytes assembled here. The
 * builder is deliberately independent of the production writer: if both sides shared an
 * encoder, a bug in it would cancel itself out and the tests would still pass.
 */
class Msg {
    private val out = ByteArrayOutputStream()

    private fun putVarint(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    private fun key(field: Int, wireType: Int) = putVarint((field.toLong() shl 3) or wireType.toLong())

    fun varint(field: Int, value: Long): Msg {
        key(field, 0); putVarint(value); return this
    }

    fun bool(field: Int, value: Boolean): Msg = varint(field, if (value) 1L else 0L)

    fun fixed64(field: Int, bits: Long): Msg {
        key(field, 1)
        for (i in 0 until 8) out.write(((bits ushr (8 * i)) and 0xFF).toInt())
        return this
    }

    fun double(field: Int, value: Double): Msg = fixed64(field, value.toRawBits())

    fun fixed32(field: Int, bits: Int): Msg {
        key(field, 5)
        for (i in 0 until 4) out.write((bits ushr (8 * i)) and 0xFF)
        return this
    }

    fun float(field: Int, value: Float): Msg = fixed32(field, value.toRawBits())

    fun bytes(field: Int, value: ByteArray): Msg {
        key(field, 2); putVarint(value.size.toLong()); out.write(value); return this
    }

    fun string(field: Int, value: String): Msg = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, value: Msg): Msg = bytes(field, value.build())

    fun build(): ByteArray = out.toByteArray()
}

object Fixtures {

    /** A dish that is up, converged, motors idle, with a GPS fix. */
    fun statusResponse(
        serial: String? = "ut01000000-00000000-00000000",
        azimuthDeg: Float? = 12.5f,
        elevationDeg: Float? = 62.0f,
        tiltDeg: Float? = 3.25f,
        attitudeState: Int = 2, // FILTER_CONVERGED
        actuatorState: Int = 0, // IDLE
        includeAlignmentStats: Boolean = true,
        topLevelAzimuth: Float? = null,
        topLevelElevation: Float? = null,
        gpsValid: Boolean = true,
        gpsSats: Int = 11,
        unexpectedLocation: Boolean = false,
        mastNotNearVertical: Boolean = false,
        stowRequested: Boolean = false,
        includeUnknownFields: Boolean = true,
    ): ByteArray {
        val status = Msg()

        if (serial != null) {
            status.message(StarlinkFields.STATUS_DEVICE_INFO, Msg().string(StarlinkFields.DEVICE_INFO_ID, serial))
        }

        if (includeUnknownFields) {
            // Fields the app does not model, in every wire type, to prove they are skipped.
            status.float(1009, 27.5f)               // pop_ping_latency_ms
            status.varint(1016, 1000)               // eth_speed_mbps
            status.message(1004, Msg().float(1, 0.01f)) // obstruction_stats
        }

        status.bool(StarlinkFields.STATUS_STOW_REQUESTED, stowRequested)
        topLevelAzimuth?.let { status.float(StarlinkFields.STATUS_BORESIGHT_AZIMUTH_DEG, it) }
        topLevelElevation?.let { status.float(StarlinkFields.STATUS_BORESIGHT_ELEVATION_DEG, it) }

        status.message(
            StarlinkFields.STATUS_GPS_STATS,
            Msg().bool(StarlinkFields.GPS_VALID, gpsValid).varint(StarlinkFields.GPS_SATS, gpsSats.toLong()),
        )

        status.message(
            StarlinkFields.STATUS_ALERTS,
            Msg()
                .bool(StarlinkFields.ALERT_UNEXPECTED_LOCATION, unexpectedLocation)
                .bool(StarlinkFields.ALERT_MAST_NOT_NEAR_VERTICAL, mastNotNearVertical),
        )

        status.varint(StarlinkFields.STATUS_MOBILITY_CLASS, 0) // STATIONARY

        if (includeAlignmentStats) {
            val align = Msg()
                .varint(StarlinkFields.ALIGN_HAS_ACTUATORS, 1)
                .varint(StarlinkFields.ALIGN_ACTUATOR_STATE, actuatorState.toLong())
            tiltDeg?.let { align.float(StarlinkFields.ALIGN_TILT_ANGLE_DEG, it) }
            azimuthDeg?.let { align.float(StarlinkFields.ALIGN_BORESIGHT_AZIMUTH_DEG, it) }
            elevationDeg?.let { align.float(StarlinkFields.ALIGN_BORESIGHT_ELEVATION_DEG, it) }
            align.varint(StarlinkFields.ALIGN_ATTITUDE_ESTIMATION_STATE, attitudeState.toLong())
            align.float(StarlinkFields.ALIGN_ATTITUDE_UNCERTAINTY_DEG, 0.4f)
            status.message(StarlinkFields.STATUS_ALIGNMENT_STATS, align)
        }

        return Msg().message(StarlinkFields.RESPONSE_DISH_GET_STATUS, status).build()
    }

    fun locationResponse(
        lat: Double = 47.6062,
        lon: Double = -122.3321,
        alt: Double = 56.0,
        sigmaM: Double = 3.5,
        source: Int = 4, // GPS
    ): ByteArray {
        val location = Msg()
            .message(
                StarlinkFields.LOCATION_LLA,
                Msg().double(StarlinkFields.LLA_LAT, lat)
                    .double(StarlinkFields.LLA_LON, lon)
                    .double(StarlinkFields.LLA_ALT, alt),
            )
            .varint(StarlinkFields.LOCATION_SOURCE, source.toLong())
            .double(StarlinkFields.LOCATION_SIGMA_M, sigmaM)
        return Msg().message(StarlinkFields.RESPONSE_GET_LOCATION, location).build()
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
