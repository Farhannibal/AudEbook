package com.example.audebook.utils

/**
 * Eases playback up to the chosen speed instead of starting at it: playback starts at
 * [START_SPEED] and rises at a steady rate until it reaches the target. Pure, so it can be unit
 * tested.
 */
object SpeedRamp {

    const val START_SPEED = 1.0

    // How long it takes to gain 1x of speed: 4x is reached after 6 s, 5x after 8 s
    const val SECONDS_PER_1X = 2.0

    // How often the speed is updated while ramping
    const val STEP_INTERVAL_MS = 200L

    /** Whether playback at [targetSpeed] should be ramped up to, rather than started at. */
    fun shouldRamp(targetSpeed: Double): Boolean = targetSpeed > START_SPEED

    /** The speed to play at [elapsedMs] after playback started, never above [targetSpeed]. */
    fun speedAt(elapsedMs: Long, targetSpeed: Double): Double {
        if (!shouldRamp(targetSpeed)) return targetSpeed
        val ramped = START_SPEED + elapsedMs / 1000.0 / SECONDS_PER_1X
        return ramped.coerceAtMost(targetSpeed)
    }

    /** How long the full ramp from [START_SPEED] to [targetSpeed] takes. */
    fun durationMs(targetSpeed: Double): Long =
        if (shouldRamp(targetSpeed)) ((targetSpeed - START_SPEED) * SECONDS_PER_1X * 1000).toLong() else 0L
}
