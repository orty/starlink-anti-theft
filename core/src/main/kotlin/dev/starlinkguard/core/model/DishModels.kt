package dev.starlinkguard.core.model

/**
 * How much the dish trusts its own attitude estimate. Azimuth, elevation and tilt are only
 * meaningful once the filter has converged; before that the dish is still working out which
 * way it is pointing and the numbers swing wildly.
 */
enum class AttitudeEstimationState(val wireValue: Int) {
    FILTER_RESET(0),
    FILTER_UNCONVERGED(1),
    FILTER_CONVERGED(2),
    FILTER_FAULTED(3),
    FILTER_INVALID(4),
    UNKNOWN(-1);

    companion object {
        fun fromWire(value: Int): AttitudeEstimationState =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

/**
 * What the dish's motors are doing. Anything other than [ACTUATOR_STATE_IDLE] means the dish
 * is re-aiming itself, which is the single largest source of false theft alarms on motorised
 * Gen2/Gen3 hardware.
 */
enum class ActuatorState(val wireValue: Int) {
    ACTUATOR_STATE_IDLE(0),
    ACTUATOR_STATE_FULL_TILT(1),
    ACTUATOR_STATE_ROTATE(2),
    ACTUATOR_STATE_TILT(3),
    ACTUATOR_STATE_UNWRAP_POSITIVE(4),
    ACTUATOR_STATE_UNWRAP_NEGATIVE(5),
    ACTUATOR_STATE_TILT_TO_STOWED(6),
    ACTUATOR_STATE_FAULTED(7),
    ACTUATOR_STATE_WAIT_TIL_STATIC(8),
    ACTUATOR_STATE_DRIVE_TO_MOBILE_POSITION(9),
    ACTUATOR_STATE_MOBILE_WAIT(10),
    UNKNOWN(-1);

    val isMoving: Boolean get() = this != ACTUATOR_STATE_IDLE && this != UNKNOWN

    companion object {
        fun fromWire(value: Int): ActuatorState =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

enum class MobilityClass(val wireValue: Int) {
    STATIONARY(0),
    NOMADIC(1),
    MOBILE(2),
    UNKNOWN(-1);

    companion object {
        fun fromWire(value: Int): MobilityClass =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

/**
 * The subset of `DishAlerts` worth surfacing. [unexpectedLocation] and [mastNotNearVertical]
 * are the dish's own "I have been moved" and "I am not upright" flags — useful backup when
 * GPS coordinates are unavailable, which is common.
 */
data class DishAlerts(
    val motorsStuck: Boolean = false,
    val unexpectedLocation: Boolean = false,
    val mastNotNearVertical: Boolean = false,
    val roaming: Boolean = false,
) {
    val anyMovementRelated: Boolean get() = unexpectedLocation || mastNotNearVertical
}

/** A decoded `DishGetStatusResponse`, narrowed to the fields this app acts on. */
data class DishStatus(
    val serial: String? = null,
    val azimuthDeg: Float? = null,
    val elevationDeg: Float? = null,
    val tiltDeg: Float? = null,
    val attitudeState: AttitudeEstimationState = AttitudeEstimationState.UNKNOWN,
    val actuatorState: ActuatorState = ActuatorState.UNKNOWN,
    val attitudeUncertaintyDeg: Float? = null,
    val hasActuators: Boolean? = null,
    val stowRequested: Boolean = false,
    val mobilityClass: MobilityClass = MobilityClass.UNKNOWN,
    val gpsValid: Boolean = false,
    val gpsSats: Int = 0,
    val alerts: DishAlerts = DishAlerts(),
) {
    /** True when azimuth/elevation/tilt can be compared against a baseline. */
    val hasOrientation: Boolean
        get() = azimuthDeg != null || elevationDeg != null || tiltDeg != null
}

enum class PositionSource(val wireValue: Int) {
    AUTO(0),
    NONE(1),
    UT_INFO(2),
    EXTERNAL(3),
    GPS(4),
    STARLINK(5),
    GNC_FUSED(6),
    GNC_BAD_SAT(7),
    GNC_GPS(8),
    GNC_PNT(9),
    GNC_STATIC(10),
    UNKNOWN(-1);

    companion object {
        fun fromWire(value: Int): PositionSource =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

/** A decoded `GetLocationResponse`. */
data class DishLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val sigmaMeters: Double? = null,
    val source: PositionSource = PositionSource.UNKNOWN,
    val horizontalSpeedMps: Double? = null,
)
