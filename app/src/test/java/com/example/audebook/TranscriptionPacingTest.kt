package com.example.audebook

import com.example.audebook.utils.TranscriptionPacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionPacingTest {

    private val slotSeconds = 10

    // Whisper time per 10 s slot measured on a Galaxy S24+ (WhisperBenchmarkTest)
    private val measuredSlotTimeMs = mapOf(1 to 1470.0, 2 to 1000.0, 4 to 770.0)

    @Test
    fun lookaheadCoversAMinuteOfListening() {
        assertEquals(6, TranscriptionPacing.maxSlotsAhead(1.0, slotSeconds))
        assertEquals(12, TranscriptionPacing.maxSlotsAhead(2.0, slotSeconds))
        assertEquals(30, TranscriptionPacing.maxSlotsAhead(5.0, slotSeconds))
        assertEquals(3, TranscriptionPacing.maxSlotsAhead(0.5, slotSeconds))
        assertEquals(TranscriptionPacing.MAX_SLOTS_AHEAD, TranscriptionPacing.maxSlotsAhead(100.0, slotSeconds))
    }

    @Test
    fun settlesOnTheFewestThreadsThatKeepUp() {
        val expected = mapOf(1.0 to 1, 2.0 to 1, 3.0 to 1, 4.0 to 2, 5.0 to 2)
        for ((speed, threads) in expected) {
            assertEquals("at ${speed}x", threads, settle(startThreads = 1, speed = speed))
        }
    }

    @Test
    fun stepsBackDownAfterSlowingPlayback() {
        // e.g. after Anchor or a burst at 5x, then back to normal speed
        assertEquals(1, settle(startThreads = 4, speed = 1.0))
        assertEquals(1, settle(startThreads = 4, speed = 2.0))
    }

    @Test
    fun settledThreadsKeepEnoughHeadroom() {
        for (speed in listOf(1.0, 2.0, 3.0, 4.0, 5.0)) {
            val threads = settle(startThreads = 1, speed = speed)
            val headroom = TranscriptionPacing.headroom(measuredSlotTimeMs.getValue(threads), speed, slotSeconds)
            assertTrue("at ${speed}x, $threads threads only has ${headroom}x", headroom >= TranscriptionPacing.MIN_HEADROOM)
        }
    }

    @Test
    fun fallingBehindStepsUpUntilTheTopLevel() {
        assertEquals(2, TranscriptionPacing.threadsAfterFallingBehind(1))
        assertEquals(4, TranscriptionPacing.threadsAfterFallingBehind(2))
        assertEquals(4, TranscriptionPacing.threadsAfterFallingBehind(4))
    }

    /** Applies the decision repeatedly with the measured times until it stops changing. */
    private fun settle(startThreads: Int, speed: Double): Int {
        var threads = startThreads
        repeat(10) {
            val headroom = TranscriptionPacing.headroom(measuredSlotTimeMs.getValue(threads), speed, slotSeconds)
            val next = TranscriptionPacing.nextThreadCount(threads, headroom)
            if (next == threads) return threads
            threads = next
        }
        error("thread count did not settle at ${speed}x")
    }
}
