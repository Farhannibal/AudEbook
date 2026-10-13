package com.example.audebook

import com.example.audebook.utils.SpeedRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedRampTest {

    @Test
    fun startsAtNormalSpeed() {
        assertEquals(1.0, SpeedRamp.speedAt(0, 4.0), 1e-9)
    }

    @Test
    fun risesSteadilyToTheTarget() {
        assertEquals(1.5, SpeedRamp.speedAt(1_000, 4.0), 1e-9)
        assertEquals(2.5, SpeedRamp.speedAt(3_000, 4.0), 1e-9)
        assertEquals(4.0, SpeedRamp.speedAt(6_000, 4.0), 1e-9)
    }

    @Test
    fun neverGoesPastTheTarget() {
        assertEquals(4.0, SpeedRamp.speedAt(60_000, 4.0), 1e-9)
        assertEquals(1.2, SpeedRamp.speedAt(5_000, 1.2), 1e-9)
    }

    @Test
    fun takesTwoSecondsPerExtra1x() {
        assertEquals(6_000L, SpeedRamp.durationMs(4.0))
        assertEquals(8_000L, SpeedRamp.durationMs(5.0))
        assertEquals(0L, SpeedRamp.durationMs(1.0))
    }

    @Test
    fun onlyRampsAboveNormalSpeed() {
        assertTrue(SpeedRamp.shouldRamp(1.5))
        assertFalse(SpeedRamp.shouldRamp(1.0))
        assertFalse(SpeedRamp.shouldRamp(0.75))
        // Slower than normal plays at the chosen speed straight away
        assertEquals(0.75, SpeedRamp.speedAt(0, 0.75), 1e-9)
    }
}
