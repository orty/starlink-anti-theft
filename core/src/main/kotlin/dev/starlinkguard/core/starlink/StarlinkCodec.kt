package dev.starlinkguard.core.starlink

import dev.starlinkguard.core.model.ActuatorState
import dev.starlinkguard.core.model.AttitudeEstimationState
import dev.starlinkguard.core.model.DishAlerts
import dev.starlinkguard.core.model.DishLocation
import dev.starlinkguard.core.model.DishStatus
import dev.starlinkguard.core.model.MobilityClass
import dev.starlinkguard.core.model.PositionSource
import dev.starlinkguard.core.proto.ProtoParseException
import dev.starlinkguard.core.proto.WIRETYPE_FIXED32
import dev.starlinkguard.core.proto.WIRETYPE_FIXED64
import dev.starlinkguard.core.proto.WIRETYPE_LENGTH_DELIMITED
import dev.starlinkguard.core.proto.WIRETYPE_VARINT
import dev.starlinkguard.core.proto.WireReader
import dev.starlinkguard.core.proto.WireWriter

/**
 * Field numbers from the SpaceX device API.
 *
 * These come from the `.proto` files recovered from the dish's own gRPC server reflection
 * (mirrored in projects like starlink-grpc-tools and starlink-grpc-golang). Note the
 * asymmetry that trips people up: you *send* `get_status` as field 1004 and the reply comes
 * back as `dish_get_status`, field **2004**.
 */
object StarlinkFields {
    // SpaceX.API.Device.Request
    const val REQUEST_GET_STATUS = 1004
    const val REQUEST_GET_LOCATION = 1017

    // SpaceX.API.Device.Response
    const val RESPONSE_DISH_GET_STATUS = 2004
    const val RESPONSE_GET_LOCATION = 1017

    // DishGetStatusResponse
    const val STATUS_DEVICE_INFO = 1
    const val STATUS_ALERTS = 1005
    const val STATUS_STOW_REQUESTED = 1010
    const val STATUS_BORESIGHT_AZIMUTH_DEG = 1011
    const val STATUS_BORESIGHT_ELEVATION_DEG = 1012
    const val STATUS_GPS_STATS = 1015
    const val STATUS_MOBILITY_CLASS = 1017
    const val STATUS_ALIGNMENT_STATS = 1027

    // DeviceInfo
    const val DEVICE_INFO_ID = 1

    // AlignmentStats
    const val ALIGN_HAS_ACTUATORS = 1
    const val ALIGN_ACTUATOR_STATE = 2
    const val ALIGN_TILT_ANGLE_DEG = 3
    const val ALIGN_BORESIGHT_AZIMUTH_DEG = 4
    const val ALIGN_BORESIGHT_ELEVATION_DEG = 5
    const val ALIGN_ATTITUDE_ESTIMATION_STATE = 6
    const val ALIGN_ATTITUDE_UNCERTAINTY_DEG = 7

    // DishGpsStats
    const val GPS_VALID = 1
    const val GPS_SATS = 2

    // DishAlerts
    const val ALERT_MOTORS_STUCK = 1
    const val ALERT_UNEXPECTED_LOCATION = 4
    const val ALERT_MAST_NOT_NEAR_VERTICAL = 5
    const val ALERT_ROAMING = 7

    // GetLocationResponse
    const val LOCATION_LLA = 1
    const val LOCATION_SOURCE = 3
    const val LOCATION_SIGMA_M = 4
    const val LOCATION_HORIZONTAL_SPEED_MPS = 5

    // LLAPosition
    const val LLA_LAT = 1
    const val LLA_LON = 2
    const val LLA_ALT = 3

    // HasActuators enum
    private const val HAS_ACTUATORS_YES = 1
    private const val HAS_ACTUATORS_NO = 2

    fun hasActuatorsFromWire(value: Int): Boolean? = when (value) {
        HAS_ACTUATORS_YES -> true
        HAS_ACTUATORS_NO -> false
        else -> null
    }
}

/** Encodes the request bodies and decodes the replies for the two RPCs the app uses. */
object StarlinkCodec {

    /** `Request { get_status: GetStatusRequest {} }` — three bytes: `E2 3E 00`. */
    fun encodeGetStatusRequest(): ByteArray =
        WireWriter().writeEmptyMessage(StarlinkFields.REQUEST_GET_STATUS).toByteArray()

    /** `Request { get_location: GetLocationRequest {} }` — three bytes: `CA 3F 00`. */
    fun encodeGetLocationRequest(): ByteArray =
        WireWriter().writeEmptyMessage(StarlinkFields.REQUEST_GET_LOCATION).toByteArray()

    /**
     * Pulls the `DishGetStatusResponse` out of a `Response` envelope and decodes it.
     *
     * @throws ProtoParseException if the envelope carries no dish status at all.
     */
    fun decodeStatusResponse(body: ByteArray): DishStatus {
        val envelope = WireReader(body)
        var status: DishStatus? = null
        envelope.forEachField { tag, reader ->
            if (tag.field == StarlinkFields.RESPONSE_DISH_GET_STATUS &&
                tag.wireType == WIRETYPE_LENGTH_DELIMITED
            ) {
                status = decodeDishStatus(reader.readMessage())
                true
            } else {
                false
            }
        }
        return status ?: throw ProtoParseException("response contained no dish_get_status (field 2004)")
    }

    /** Pulls the `GetLocationResponse` out of a `Response` envelope. */
    fun decodeLocationResponse(body: ByteArray): DishLocation? {
        val envelope = WireReader(body)
        var location: DishLocation? = null
        envelope.forEachField { tag, reader ->
            if (tag.field == StarlinkFields.RESPONSE_GET_LOCATION &&
                tag.wireType == WIRETYPE_LENGTH_DELIMITED
            ) {
                location = decodeLocation(reader.readMessage())
                true
            } else {
                false
            }
        }
        return location
    }

    private fun decodeDishStatus(reader: WireReader): DishStatus {
        var serial: String? = null
        var topAzimuth: Float? = null
        var topElevation: Float? = null
        var alignAzimuth: Float? = null
        var alignElevation: Float? = null
        var tilt: Float? = null
        var attitudeState = AttitudeEstimationState.UNKNOWN
        var actuatorState = ActuatorState.UNKNOWN
        var attitudeUncertainty: Float? = null
        var hasActuators: Boolean? = null
        var stowRequested = false
        var mobilityClass = MobilityClass.UNKNOWN
        var gpsValid = false
        var gpsSats = 0
        var alerts = DishAlerts()

        reader.forEachField { tag, r ->
            when {
                tag.field == StarlinkFields.STATUS_DEVICE_INFO && tag.wireType == WIRETYPE_LENGTH_DELIMITED -> {
                    serial = decodeDeviceInfoId(r.readMessage()); true
                }
                tag.field == StarlinkFields.STATUS_BORESIGHT_AZIMUTH_DEG && tag.wireType == WIRETYPE_FIXED32 -> {
                    topAzimuth = r.readFloat(); true
                }
                tag.field == StarlinkFields.STATUS_BORESIGHT_ELEVATION_DEG && tag.wireType == WIRETYPE_FIXED32 -> {
                    topElevation = r.readFloat(); true
                }
                tag.field == StarlinkFields.STATUS_STOW_REQUESTED && tag.wireType == WIRETYPE_VARINT -> {
                    stowRequested = r.readBool(); true
                }
                tag.field == StarlinkFields.STATUS_MOBILITY_CLASS && tag.wireType == WIRETYPE_VARINT -> {
                    mobilityClass = MobilityClass.fromWire(r.readInt32()); true
                }
                tag.field == StarlinkFields.STATUS_ALIGNMENT_STATS && tag.wireType == WIRETYPE_LENGTH_DELIMITED -> {
                    val align = r.readMessage()
                    align.forEachField { t, ar ->
                        when {
                            t.field == StarlinkFields.ALIGN_HAS_ACTUATORS && t.wireType == WIRETYPE_VARINT -> {
                                hasActuators = StarlinkFields.hasActuatorsFromWire(ar.readInt32()); true
                            }
                            t.field == StarlinkFields.ALIGN_ACTUATOR_STATE && t.wireType == WIRETYPE_VARINT -> {
                                actuatorState = ActuatorState.fromWire(ar.readInt32()); true
                            }
                            t.field == StarlinkFields.ALIGN_TILT_ANGLE_DEG && t.wireType == WIRETYPE_FIXED32 -> {
                                tilt = ar.readFloat(); true
                            }
                            t.field == StarlinkFields.ALIGN_BORESIGHT_AZIMUTH_DEG && t.wireType == WIRETYPE_FIXED32 -> {
                                alignAzimuth = ar.readFloat(); true
                            }
                            t.field == StarlinkFields.ALIGN_BORESIGHT_ELEVATION_DEG && t.wireType == WIRETYPE_FIXED32 -> {
                                alignElevation = ar.readFloat(); true
                            }
                            t.field == StarlinkFields.ALIGN_ATTITUDE_ESTIMATION_STATE && t.wireType == WIRETYPE_VARINT -> {
                                attitudeState = AttitudeEstimationState.fromWire(ar.readInt32()); true
                            }
                            t.field == StarlinkFields.ALIGN_ATTITUDE_UNCERTAINTY_DEG && t.wireType == WIRETYPE_FIXED32 -> {
                                attitudeUncertainty = ar.readFloat(); true
                            }
                            else -> false
                        }
                    }
                    true
                }
                tag.field == StarlinkFields.STATUS_GPS_STATS && tag.wireType == WIRETYPE_LENGTH_DELIMITED -> {
                    val gps = r.readMessage()
                    gps.forEachField { t, gr ->
                        when {
                            t.field == StarlinkFields.GPS_VALID && t.wireType == WIRETYPE_VARINT -> {
                                gpsValid = gr.readBool(); true
                            }
                            t.field == StarlinkFields.GPS_SATS && t.wireType == WIRETYPE_VARINT -> {
                                gpsSats = gr.readInt32(); true
                            }
                            else -> false
                        }
                    }
                    true
                }
                tag.field == StarlinkFields.STATUS_ALERTS && tag.wireType == WIRETYPE_LENGTH_DELIMITED -> {
                    alerts = decodeAlerts(r.readMessage()); true
                }
                else -> false
            }
        }

        // AlignmentStats carries the authoritative pointing numbers on hardware that has
        // them; the top-level fields are the fallback for dishes that do not.
        return DishStatus(
            serial = serial,
            azimuthDeg = alignAzimuth ?: topAzimuth,
            elevationDeg = alignElevation ?: topElevation,
            tiltDeg = tilt,
            attitudeState = attitudeState,
            actuatorState = actuatorState,
            attitudeUncertaintyDeg = attitudeUncertainty,
            hasActuators = hasActuators,
            stowRequested = stowRequested,
            mobilityClass = mobilityClass,
            gpsValid = gpsValid,
            gpsSats = gpsSats,
            alerts = alerts,
        )
    }

    private fun decodeDeviceInfoId(reader: WireReader): String? {
        var id: String? = null
        reader.forEachField { tag, r ->
            if (tag.field == StarlinkFields.DEVICE_INFO_ID && tag.wireType == WIRETYPE_LENGTH_DELIMITED) {
                id = r.readString(); true
            } else {
                false
            }
        }
        return id
    }

    private fun decodeAlerts(reader: WireReader): DishAlerts {
        var motorsStuck = false
        var unexpectedLocation = false
        var mastNotNearVertical = false
        var roaming = false
        reader.forEachField { tag, r ->
            when {
                tag.wireType != WIRETYPE_VARINT -> false
                tag.field == StarlinkFields.ALERT_MOTORS_STUCK -> { motorsStuck = r.readBool(); true }
                tag.field == StarlinkFields.ALERT_UNEXPECTED_LOCATION -> { unexpectedLocation = r.readBool(); true }
                tag.field == StarlinkFields.ALERT_MAST_NOT_NEAR_VERTICAL -> { mastNotNearVertical = r.readBool(); true }
                tag.field == StarlinkFields.ALERT_ROAMING -> { roaming = r.readBool(); true }
                else -> false
            }
        }
        return DishAlerts(motorsStuck, unexpectedLocation, mastNotNearVertical, roaming)
    }

    private fun decodeLocation(reader: WireReader): DishLocation? {
        var lat: Double? = null
        var lon: Double? = null
        var alt: Double? = null
        var sigma: Double? = null
        var speed: Double? = null
        var source = PositionSource.UNKNOWN

        reader.forEachField { tag, r ->
            when {
                tag.field == StarlinkFields.LOCATION_LLA && tag.wireType == WIRETYPE_LENGTH_DELIMITED -> {
                    val lla = r.readMessage()
                    lla.forEachField { t, lr ->
                        when {
                            t.wireType != WIRETYPE_FIXED64 -> false
                            t.field == StarlinkFields.LLA_LAT -> { lat = lr.readDouble(); true }
                            t.field == StarlinkFields.LLA_LON -> { lon = lr.readDouble(); true }
                            t.field == StarlinkFields.LLA_ALT -> { alt = lr.readDouble(); true }
                            else -> false
                        }
                    }
                    true
                }
                tag.field == StarlinkFields.LOCATION_SOURCE && tag.wireType == WIRETYPE_VARINT -> {
                    source = PositionSource.fromWire(r.readInt32()); true
                }
                tag.field == StarlinkFields.LOCATION_SIGMA_M && tag.wireType == WIRETYPE_FIXED64 -> {
                    sigma = r.readDouble(); true
                }
                tag.field == StarlinkFields.LOCATION_HORIZONTAL_SPEED_MPS && tag.wireType == WIRETYPE_FIXED64 -> {
                    speed = r.readDouble(); true
                }
                else -> false
            }
        }

        val latitude = lat ?: return null
        val longitude = lon ?: return null
        // A dish with no fix reports 0,0 rather than omitting the field.
        if (latitude == 0.0 && longitude == 0.0) return null
        return DishLocation(latitude, longitude, alt, sigma, source, speed)
    }
}
