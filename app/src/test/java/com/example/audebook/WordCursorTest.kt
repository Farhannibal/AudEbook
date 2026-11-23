package com.example.audebook

import com.example.audebook.utils.sync.BookPosition
import com.example.audebook.utils.sync.SyncTracker
import com.example.audebook.utils.sync.WordCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordCursorTest {

    private fun placement(chapter: Int, start: Int, end: Int) =
        SyncTracker.Placement(0, BookPosition(chapter, start), end, SyncTracker.Kind.MATCHED)

    @Test
    fun stretchesToMeetTheNextSlot() {
        // Whisper heard 24 words, but the next slot starts 32 words in
        assertEquals(1344, WordCursor.contiguousEnd(placement(1, 1313, 1336), placement(1, 1345, 1370)))
    }

    @Test
    fun trimsAnOverlapWithTheNextSlot() {
        assertEquals(1340, WordCursor.contiguousEnd(placement(1, 1313, 1350), placement(1, 1341, 1370)))
    }

    @Test
    fun keepsItsOwnEndWhenTheNextSlotIsElsewhere() {
        // Not placed yet
        assertEquals(1336, WordCursor.contiguousEnd(placement(1, 1313, 1336), null))
        // Another chapter
        assertEquals(1336, WordCursor.contiguousEnd(placement(1, 1313, 1336), placement(2, 5, 30)))
        // A jump far ahead
        assertEquals(1336, WordCursor.contiguousEnd(placement(1, 1313, 1336), placement(1, 4000, 4025)))
        // Earlier in the book
        assertEquals(1336, WordCursor.contiguousEnd(placement(1, 1313, 1336), placement(1, 900, 925)))
    }

    @Test
    fun movesThroughEveryWordInOrder() {
        val words = (0 until 2000).map { (it % 9) + 1 }
        var previous = 100
        for (step in 0..100) {
            val word = WordCursor.wordAt(step / 100.0, 100, 124) { words[it] }
            assertTrue("went backwards at $step%", word >= previous)
            assertTrue(word in 100..124)
            previous = word
        }
        assertEquals(100, WordCursor.wordAt(0.0, 100, 124) { words[it] })
        assertEquals(124, WordCursor.wordAt(1.0, 100, 124) { words[it] })
    }

    @Test
    fun spendsLongerOnLongerWords() {
        // "a extraordinarily a": the long middle word should take most of the slot
        val lengths = mapOf(0 to 1, 1 to 16, 2 to 1)
        assertEquals(0, WordCursor.wordAt(0.05, 0, 2) { lengths.getValue(it) })
        assertEquals(1, WordCursor.wordAt(0.5, 0, 2) { lengths.getValue(it) })
        assertEquals(1, WordCursor.wordAt(0.8, 0, 2) { lengths.getValue(it) })
        assertEquals(2, WordCursor.wordAt(0.95, 0, 2) { lengths.getValue(it) })
    }
}
