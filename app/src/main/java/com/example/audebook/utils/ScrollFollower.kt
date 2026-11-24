package com.example.audebook.utils

import kotlin.math.roundToInt

/**
 * Decides how to scroll so the word being spoken stays at [target] of the readable area.
 *
 * It only looks at where the word is on screen, measured after layout, so it works the same for
 * any font size, line width or orientation: bigger text moves the word down faster, which makes
 * it scroll faster. Pure, so it can be unit tested.
 *
 * Positions are fractions of the readable area: 0 is its top, 1 is the top of the audio controls.
 */
class ScrollFollower(private val target: Double = TARGET) {

    /** Current scroll speed in pixels per second. */
    var velocity = 0.0
        private set

    private var carry = 0.0

    /**
     * Takes a fresh measurement of where the word is ([position], or null if it isn't on any page)
     * and returns how many pixels to jump right away: non-zero only when the word is off screen,
     * e.g. after a rotation, a font change or a jump to another part of the book.
     */
    fun onPosition(position: Double?, readableHeightPx: Int): Int {
        if (position == null || readableHeightPx <= 0) {
            velocity = 0.0
            return 0
        }
        if (position < 0.0 || position > 1.0) {
            velocity = 0.0
            carry = 0.0
            return ((position - target) * readableHeightPx).roundToInt()
        }
        // Close the gap over SETTLE_SECONDS; never scroll back up, if the word is above the
        // target the narrator is just behind and the page waits for them
        val desired = ((position - target) * readableHeightPx / SETTLE_SECONDS)
            .coerceIn(0.0, MAX_SCREENS_PER_SECOND * readableHeightPx)
        // Ease toward it, so the line-by-line steps of the word don't make the page lurch
        velocity += (desired - velocity) * SMOOTHING
        return 0
    }

    /** Whole pixels to scroll for a frame lasting [elapsedMs], carrying the fractions over. */
    fun pixelsFor(elapsedMs: Long): Int {
        carry += velocity * elapsedMs / 1000.0
        val pixels = carry.toInt()
        carry -= pixels
        return pixels
    }

    /** Pixels to jump so the word at [position] lands exactly on the target. */
    fun recenter(position: Double, readableHeightPx: Int): Int =
        ((position - target) * readableHeightPx).roundToInt()

    fun reset() {
        velocity = 0.0
        carry = 0.0
    }

    companion object {
        const val TARGET = 0.4
        const val SETTLE_SECONDS = 1.5
        const val SMOOTHING = 0.3
        const val MAX_SCREENS_PER_SECOND = 0.5
    }
}
