package dev.starlinkguard.core.detect

import kotlinx.serialization.Serializable

/**
 * Everything that decides how twitchy the alarm is.
 *
 * Defaults are tuned to be quiet on a healthy fixed installation: a dish that is bolted down
 * holds azimuth and tilt to well under a degree, so ten degrees of azimuth movement is far
 * outside normal drift while still catching someone lifting the dish off its mount.
 */
@Serializable
data class Thresholds(
    /** How often the dish is polled, in seconds. */
    val pollIntervalSec: Int = 10,

    /**
     * The look-back window for the "sudden change" comparison, in seconds. A theft shows up
     * as a large move inside this window; slow thermal or mount settling does not.
     */
    val suddenWindowSec: Int = 30,

    val azimuthDeg: Float = 10f,
    val elevationDeg: Float = 5f,
    val tiltDeg: Float = 5f,

    /** Distance the dish's own GPS fix may move from the armed position, in metres. */
    val gpsMeters: Double = 50.0,

    /** Consecutive breaching polls required before the alarm sounds. */
    val confirmSamples: Int = 2,

    /**
     * Settling time after arming before a baseline is captured, in seconds. Nothing can
     * trigger during this window.
     */
    val armingGraceSec: Int = 30,

    /**
     * Ignore orientation changes while the dish's motors are running.
     *
     * Motorised dishes re-aim themselves, and that is by far the largest source of false
     * alarms. A thief carrying a dish away leaves the actuators idle while the orientation
     * changes, so this suppression costs very little detection power. Turn it off for a dish
     * with no actuators or if you would rather have false alarms than miss anything.
     */
    val suppressWhileActuating: Boolean = true,

    /**
     * Only evaluate orientation when the dish's attitude filter has converged. Before
     * convergence the reported angles are guesses.
     */
    val requireConvergedAttitude: Boolean = true,

    /** Compare the dish's GPS fix against the armed position, when a fix is available. */
    val gpsEnabled: Boolean = true,

    /**
     * Also trigger on the dish's own `unexpected_location` / `mast_not_near_vertical` alerts.
     *
     * Off by default because they can fire for benign reasons, but they are useful insurance
     * on installations where the dish refuses to report GPS coordinates.
     */
    val useDishMovedAlerts: Boolean = false,
) {
    init {
        require(pollIntervalSec > 0) { "pollIntervalSec must be positive" }
        require(confirmSamples >= 1) { "confirmSamples must be at least 1" }
        require(armingGraceSec >= 0) { "armingGraceSec cannot be negative" }
        require(suddenWindowSec >= pollIntervalSec) {
            "suddenWindowSec ($suddenWindowSec) must be at least one poll interval ($pollIntervalSec)"
        }
    }
}
