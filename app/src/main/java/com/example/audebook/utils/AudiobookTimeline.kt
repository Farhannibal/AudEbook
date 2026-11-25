package com.example.audebook.utils

import kotlin.time.Duration

/**
 * Joins an audiobook's tracks into one timeline, so a book split into files (one per chapter,
 * say) is timed, transcribed and synced as one long recording. A single-file audiobook is a
 * timeline with one track, where book time and track time are the same.
 *
 * Pure, so it can be unit tested.
 */
class AudiobookTimeline(trackDurations: List<Duration>) {

    /** Where each track starts in the book. */
    val trackStarts: List<Duration> =
        trackDurations.runningFold(Duration.ZERO) { start, length -> start + length }.dropLast(1)

    /** Length of the whole book. */
    val duration: Duration = trackDurations.fold(Duration.ZERO) { total, length -> total + length }

    /** The book time of [offset] into [track]. */
    fun bookTime(track: Int, offset: Duration): Duration =
        trackStarts.getOrElse(track) { Duration.ZERO } + offset

    /**
     * The track playing at [bookTime] and how far into it, the start of the next track at a
     * boundary. Times past the end fall in the last track.
     */
    fun trackAt(bookTime: Duration): Pair<Int, Duration> {
        if (trackStarts.isEmpty()) return 0 to bookTime
        val track = trackStarts.indexOfLast { it <= bookTime }.coerceAtLeast(0)
        return track to bookTime - trackStarts[track]
    }

    companion object {

        /**
         * Orders file names the way people number them: "Chapter 2" before "Chapter 10", ignoring
         * case.
         */
        val naturalOrder: Comparator<String> = Comparator { a, b ->
            val left = chunks(a)
            val right = chunks(b)
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]
                val y = right[i]
                val order = if (x[0].isDigit() && y[0].isDigit()) {
                    compareValuesBy(x, y, { it.trimStart('0').length }, { it.trimStart('0') })
                } else {
                    x.compareTo(y, ignoreCase = true)
                }
                if (order != 0) return@Comparator order
            }
            left.size.compareTo(right.size)
        }

        // Splits a name into runs of digits and runs of everything else
        private fun chunks(name: String): List<String> =
            Regex("\\d+|\\D+").findAll(name).map { it.value }.toList()
    }
}
