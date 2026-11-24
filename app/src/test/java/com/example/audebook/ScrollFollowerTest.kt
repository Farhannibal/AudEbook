package com.example.audebook

import com.example.audebook.utils.ScrollFollower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFollowerTest {

    private val height = 2000

    /**
     * Simulates a narrator moving the word down the page at [pixelsPerSecond] for [seconds], with
     * the follower measuring every 150 ms and scrolling every 16 ms. Returns the word's final
     * position as a fraction of the page.
     */
    private fun follow(pixelsPerSecond: Double, seconds: Int, start: Double = 0.4): Double {
        val follower = ScrollFollower()
        var wordY = start * height
        var sinceCheck = 0
        repeat(seconds * 1000 / 16) {
            wordY += pixelsPerSecond * 16 / 1000
            sinceCheck += 16
            if (sinceCheck >= 150) {
                sinceCheck = 0
                wordY -= follower.onPosition(wordY / height, height)
            }
            wordY -= follower.pixelsFor(16)
        }
        return wordY / height
    }

    @Test
    fun keepsTheWordNearTheTargetWhateverTheFontSize() {
        // Small text in landscape, normal text, and very large text: the word moves down the page
        // at very different rates, and stays close to 40% in every case
        for (rate in listOf(5.0, 15.0, 60.0)) {
            val position = follow(rate, seconds = 60)
            assertTrue("at $rate px/s the word ended at $position", position in 0.38..0.48)
        }
    }

    @Test
    fun scrollsFasterForBiggerText() {
        val small = ScrollFollower()
        val big = ScrollFollower()
        repeat(20) {
            small.onPosition(0.45, height)
            big.onPosition(0.6, height)
        }
        assertTrue(big.velocity > small.velocity)
    }

    @Test
    fun waitsWhenTheNarratorIsBehind() {
        val follower = ScrollFollower()
        repeat(20) { follower.onPosition(0.2, height) }
        assertEquals(0.0, follower.velocity, 0.0)
        assertEquals(0, follower.pixelsFor(1000))
    }

    @Test
    fun jumpsToTheWordWhenItIsOffScreen() {
        val follower = ScrollFollower()
        // Two screens further down, e.g. after the font was made larger
        assertEquals(((2.4 - 0.4) * height).toInt(), follower.onPosition(2.4, height))
        // Above the top, e.g. after the font was made smaller
        assertEquals(((-0.5 - 0.4) * height).toInt(), follower.onPosition(-0.5, height))
        // On screen: no jump
        assertEquals(0, follower.onPosition(0.7, height))
    }

    @Test
    fun stopsWhenThereIsNoWord() {
        val follower = ScrollFollower()
        repeat(10) { follower.onPosition(0.8, height) }
        assertTrue(follower.velocity > 0)
        follower.onPosition(null, height)
        assertEquals(0.0, follower.velocity, 0.0)
    }
}
