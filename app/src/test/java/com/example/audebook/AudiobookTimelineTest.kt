package com.example.audebook

import com.example.audebook.utils.AudiobookTimeline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookTimelineTest {

    private val timeline = AudiobookTimeline(listOf(100.seconds, 50.5.seconds, 200.seconds))

    @Test
    fun tracksFollowOneAnother() {
        assertEquals(listOf(Duration.ZERO, 100.seconds, 150.5.seconds), timeline.trackStarts)
        assertEquals(350.5.seconds, timeline.duration)
        assertEquals(160.5.seconds, timeline.bookTime(2, 10.seconds))
    }

    @Test
    fun bookTimeMapsBackToItsTrack() {
        assertEquals(0 to 99.seconds, timeline.trackAt(99.seconds))
        assertEquals(1 to Duration.ZERO, timeline.trackAt(100.seconds))
        assertEquals(1 to 50.seconds, timeline.trackAt(150.seconds))
        assertEquals(2 to 49.5.seconds, timeline.trackAt(200.seconds))
        // Past the end stays in the last track
        assertEquals(2 to 250.seconds, timeline.trackAt(400.5.seconds))
    }

    @Test
    fun singleFileIsItsOwnTimeline() {
        val single = AudiobookTimeline(listOf(3600.seconds))
        assertEquals(0 to 1234.seconds, single.trackAt(1234.seconds))
        assertEquals(1234.seconds, single.bookTime(0, 1234.seconds))
    }

    @Test
    fun namesSortByTheirNumbers() {
        val names = listOf(
            "Chapter 10.mp3", "chapter 2.mp3", "Chapter 1.mp3",
            "alice_03_carroll.mp3", "alice_01_carroll.mp3", "alice_002_carroll.mp3"
        )
        assertEquals(
            listOf(
                "alice_01_carroll.mp3", "alice_002_carroll.mp3", "alice_03_carroll.mp3",
                "Chapter 1.mp3", "chapter 2.mp3", "Chapter 10.mp3"
            ),
            names.sortedWith(AudiobookTimeline.naturalOrder)
        )
    }
}
