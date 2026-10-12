package com.example.audebook.utils

import kotlin.math.ceil

/**
 * Decides how far ahead of playback to transcribe and how many Whisper threads to use, from the
 * playback speed and recent Whisper times. Pure, so it can be unit tested.
 */
object TranscriptionPacing {

    // Keep this much listening time transcribed ahead of playback
    const val LOOKAHEAD_LISTENING_SECONDS = 60.0
    const val MAX_SLOTS_AHEAD = 200

    // Headroom is a slot's playing time divided by how long Whisper took for it. On a Galaxy
    // S24+ one thread takes ~1.5 s per 10 s slot, which is only 1.3x headroom at 5x speed.
    val THREAD_LEVELS = listOf(1, 2, 4)
    const val MIN_HEADROOM = 2.0
    const val MAX_HEADROOM = 4.0

    /** Slots past the playback position to keep transcribed: 6 at 1x, 30 at 5x for 10 s slots. */
    fun maxSlotsAhead(playbackSpeed: Double, slotSeconds: Int): Int =
        ceil(LOOKAHEAD_LISTENING_SECONDS * playbackSpeed / slotSeconds).toInt().coerceIn(1, MAX_SLOTS_AHEAD)

    /** How many times faster than playback Whisper is getting through slots. */
    fun headroom(averageSlotTimeMs: Double, playbackSpeed: Double, slotSeconds: Int): Double =
        slotSeconds * 1000 / playbackSpeed / averageSlotTimeMs

    /**
     * The thread count to use next: one level up when headroom is under [MIN_HEADROOM], one
     * level down when it is over [MAX_HEADROOM], otherwise unchanged.
     */
    fun nextThreadCount(currentThreads: Int, headroom: Double): Int {
        val level = THREAD_LEVELS.indexOf(currentThreads).coerceAtLeast(0)
        return when {
            headroom < MIN_HEADROOM && level < THREAD_LEVELS.lastIndex -> THREAD_LEVELS[level + 1]
            headroom > MAX_HEADROOM && level > 0 -> THREAD_LEVELS[level - 1]
            else -> THREAD_LEVELS[level]
        }
    }

    /** One level up, for when transcription has fallen behind playback. */
    fun threadsAfterFallingBehind(currentThreads: Int): Int {
        val level = THREAD_LEVELS.indexOf(currentThreads).coerceAtLeast(0)
        return THREAD_LEVELS[(level + 1).coerceAtMost(THREAD_LEVELS.lastIndex)]
    }
}
