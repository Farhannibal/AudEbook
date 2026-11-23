package com.example.audebook

import com.example.audebook.utils.sync.BookIndex
import com.example.audebook.utils.sync.BookPosition
import com.example.audebook.utils.sync.SyncTracker
import com.example.audebook.utils.sync.TranscriptAligner
import com.example.audebook.utils.sync.Words
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTrackerTest {

    private val slotSeconds = 10

    // ---- BookIndex ----

    @Test
    fun tokenizesLikeTranscripts() {
        assertEquals(
            listOf("dont", "stop", "kaladins", "rules", "based", "1999"),
            Words.normalize("“Don’t stop,” Kaladin's rules-based… 1999!")
        )
    }

    @Test
    fun spansPointAtTheRightText() {
        val index = BookIndex<String>()
        val chapter = index.addChapter(
            0, listOf(BookIndex.Element("p1", "The storm came."), BookIndex.Element("p2", "Then it went away."))
        )
        val spans = chapter.spans(1, 4)
        assertEquals(2, spans.size)
        assertEquals("storm came", spans[0].element.text.substring(spans[0].charStart, spans[0].charEnd))
        assertEquals("Then it", spans[1].element.text.substring(spans[1].charStart, spans[1].charEnd))
        assertEquals(3, chapter.firstWordOf(1))
    }

    @Test
    fun movesAcrossIndexedChapters() {
        val index = BookIndex<String>()
        index.addChapter(0, listOf(BookIndex.Element("a", "one two three four five")))
        index.addChapter(1, listOf(BookIndex.Element("b", "six seven eight")))
        assertEquals(BookPosition(1, 1), index.advance(BookPosition(0, 3), 3))
        assertEquals(BookPosition(0, 4), index.advance(BookPosition(1, 0), -1))
        assertEquals(4, index.distance(BookPosition(0, 2), BookPosition(1, 1)))
        assertEquals(-4, index.distance(BookPosition(1, 1), BookPosition(0, 2)))
        // Chapter 3 isn't indexed, so distances across it are unknown
        index.addChapter(4, listOf(BookIndex.Element("c", "nine ten")))
        assertNull(index.distance(BookPosition(1, 0), BookPosition(4, 0)))
    }

    // ---- TranscriptAligner ----

    @Test
    fun findsACleanTranscriptExactly() {
        val book = SyntheticBook(Random(1))
        val aligner = TranscriptAligner(book.index)
        val match = aligner.search(Words.normalize(book.text(BookPosition(0, 1234), 26)), aligner.everywhere())
        assertNotNull(match)
        assertEquals(BookPosition(0, 1234), match!!.start)
        assertTrue(match.isClearWinner)
    }

    @Test
    fun findsANoisyTranscriptClosely() {
        val book = SyntheticBook(Random(2))
        val aligner = TranscriptAligner(book.index)
        val random = Random(3)
        var close = 0
        repeat(200) {
            val truth = BookPosition(random.nextInt(book.chapterCount), random.nextInt(100, 7_000))
            val transcript = book.noisyTranscript(truth, 26, random)
            val match = aligner.search(Words.normalize(transcript), aligner.everywhere())
            if (match != null && match.start.chapter == truth.chapter && abs(match.start.word - truth.word) <= 5) close++
        }
        assertTrue("only $close/200 noisy transcripts landed within 5 words", close >= 190)
    }

    @Test
    fun doesNotConfidentlyPlaceUnrelatedSpeech() {
        val book = SyntheticBook(Random(4))
        val aligner = TranscriptAligner(book.index)
        val random = Random(5)
        var confident = 0
        repeat(200) {
            // Same vocabulary, but words in an order the book never uses
            val words = List(26) { book.randomWord(random) }
            val match = aligner.search(words, aligner.everywhere())
            if (match != null && match.score >= SyncTracker.MIN_GLOBAL_SCORE && match.isClearWinner) confident++
        }
        assertTrue("$confident/200 unrelated transcripts looked like confident matches", confident <= 4)
    }

    // ---- SyncTracker ----

    @Test
    fun followsTheNarratorAfterAnchor() {
        val book = SyntheticBook(Random(6))
        val narrator = Narrator(book, start = BookPosition(1, 500), wordsPerSecond = 2.9)
        val tracker = SyncTracker(book.index)
        tracker.anchor(0, narrator.positionAt(0))

        val random = Random(7)
        var close = 0
        for (slot in 0 until 90) {
            val seconds = slot * slotSeconds
            val placement = tracker.place(seconds, slotSeconds, narrator.transcript(seconds, slotSeconds, random))
            assertNotNull(placement)
            if (narrator.isWithin(placement!!.start, seconds, 6)) close++
        }
        assertTrue("only $close/90 slots within 6 words", close >= 86)
        assertEquals(2.9, tracker.wordsPerSecond, 0.2)
    }

    @Test
    fun findsTheStartWithoutAnchor() {
        val book = SyntheticBook(Random(8))
        val narrator = Narrator(book, start = BookPosition(2, 3_000), wordsPerSecond = 2.6)
        val tracker = SyncTracker(book.index)
        val random = Random(9)

        // The first slot alone isn't trusted; the second agreeing one locks on
        assertNull(tracker.place(0, slotSeconds, narrator.transcript(0, slotSeconds, random)))
        val second = tracker.place(10, slotSeconds, narrator.transcript(10, slotSeconds, random))!!
        assertEquals(SyncTracker.Kind.RELOCATED, second.kind)
        assertTrue(narrator.isWithin(second.start, 10, 6))
        assertTrue(tracker.isAnchored)
    }

    @Test
    fun recoversFromAWrongAnchor() {
        val book = SyntheticBook(Random(10))
        val narrator = Narrator(book, start = BookPosition(0, 4_000), wordsPerSecond = 2.6)
        val tracker = SyncTracker(book.index)
        // Anchored about 15 minutes of reading away from the narrator
        tracker.anchor(0, BookPosition(0, 1_500))
        val random = Random(11)

        var recoveredAt: Int? = null
        for (slot in 0 until 12) {
            val seconds = slot * slotSeconds
            val placement = tracker.place(seconds, slotSeconds, narrator.transcript(seconds, slotSeconds, random))
            if (placement != null && narrator.isWithin(placement.start, seconds, 6)) {
                recoveredAt = slot
                break
            }
        }
        assertNotNull("never recovered", recoveredAt)
        assertTrue("took $recoveredAt slots to recover", recoveredAt!! <= 2)
    }

    @Test
    fun keepsMovingThroughSlotsWithNoSpeech() {
        val book = SyntheticBook(Random(12))
        val narrator = Narrator(book, start = BookPosition(0, 1_000), wordsPerSecond = 2.6)
        val tracker = SyncTracker(book.index)
        tracker.anchor(0, narrator.positionAt(0))
        val random = Random(13)
        for (slot in 0 until 6) {
            tracker.place(slot * slotSeconds, slotSeconds, narrator.transcript(slot * slotSeconds, slotSeconds, random))
        }

        // Music or silence: nothing to match, so it predicts from the pace
        val silent = tracker.place(60, slotSeconds, "")!!
        assertEquals(SyncTracker.Kind.PREDICTED, silent.kind)
        assertTrue(narrator.isWithin(silent.start, 60, 15))

        // Speech resumes and matches again
        val resumed = tracker.place(70, slotSeconds, narrator.transcript(70, slotSeconds, random))!!
        assertEquals(SyncTracker.Kind.MATCHED, resumed.kind)
    }

    @Test
    fun ignoresASingleMisheardSlot() {
        val book = SyntheticBook(Random(14))
        val narrator = Narrator(book, start = BookPosition(0, 2_000), wordsPerSecond = 2.6)
        val tracker = SyncTracker(book.index)
        tracker.anchor(0, narrator.positionAt(0))
        val random = Random(15)
        for (slot in 0 until 5) {
            tracker.place(slot * slotSeconds, slotSeconds, narrator.transcript(slot * slotSeconds, slotSeconds, random))
        }

        // One slot that clearly matches a passage far away (e.g. a repeated epigraph)
        val elsewhere = book.text(BookPosition(3, 5_000), 26)
        val odd = tracker.place(50, slotSeconds, elsewhere)!!
        assertTrue("jumped on one slot", narrator.isWithin(odd.start, 50, 15))

        val next = tracker.place(60, slotSeconds, narrator.transcript(60, slotSeconds, random))!!
        assertEquals(SyncTracker.Kind.MATCHED, next.kind)
        assertTrue(narrator.isWithin(next.start, 60, 6))
    }

    @Test
    fun followsIntoTheNextChapter() {
        val book = SyntheticBook(Random(16))
        val chapterLength = book.index.chapter(0)!!.wordCount
        val narrator = Narrator(book, start = BookPosition(0, chapterLength - 100), wordsPerSecond = 2.6)
        val tracker = SyncTracker(book.index)
        tracker.anchor(0, narrator.positionAt(0))
        val random = Random(17)
        var last: SyncTracker.Placement? = null
        for (slot in 0 until 12) {
            last = tracker.place(slot * slotSeconds, slotSeconds, narrator.transcript(slot * slotSeconds, slotSeconds, random))
        }
        assertEquals(1, last!!.start.chapter)
        assertTrue(narrator.isWithin(last.start, 110, 6))
    }

    @Test
    fun indexesABookQuickly() {
        // About the length of a long novel: 20 chapters of 8,000 words
        val random = Random(18)
        val book = SyntheticBook(random, chapters = 20, wordsPerChapter = 8_000, index = false)
        val startedAt = System.nanoTime()
        book.chapterElements.forEachIndexed { number, elements -> book.index.addChapter(number, elements) }
        val ms = (System.nanoTime() - startedAt) / 1_000_000
        println("Indexed 160,000 words in $ms ms (${ms / 20} ms per chapter)")
        assertTrue("indexing took $ms ms", ms < 5_000)
    }

    // ---- Test helpers ----

    /** Prose-like text: Zipf-distributed words, so common words and phrases repeat. */
    private class SyntheticBook(
        random: Random,
        chapters: Int = 4,
        wordsPerChapter: Int = 8_000,
        index: Boolean = true
    ) {
        val index = BookIndex<String>()
        val chapterCount = chapters
        private val vocabulary = List(6_000) { i -> syllables(i) }
        private val cumulative = DoubleArray(vocabulary.size).also { weights ->
            var total = 0.0
            for (i in weights.indices) {
                total += 1.0 / (i + 1).toDouble().pow(1.05)
                weights[i] = total
            }
        }
        private val words: List<List<String>> = List(chapters) { List(wordsPerChapter) { randomWord(random) } }
        val chapterElements: List<List<BookIndex.Element<String>>> = words.mapIndexed { c, chapterWords ->
            chapterWords.chunked(60).mapIndexed { p, paragraph ->
                val text = paragraph.mapIndexed { i, w -> if (i == 0) w.replaceFirstChar { it.uppercase() } else w }
                    .joinToString(" ") + "."
                BookIndex.Element("c$c-p$p", text)
            }
        }

        init {
            if (index) chapterElements.forEachIndexed { number, elements -> this.index.addChapter(number, elements) }
        }

        fun randomWord(random: Random): String {
            val r = random.nextDouble() * cumulative.last()
            var low = 0
            var high = cumulative.size - 1
            while (low < high) {
                val mid = (low + high) / 2
                if (cumulative[mid] < r) low = mid + 1 else high = mid
            }
            return vocabulary[low]
        }

        fun word(position: BookPosition): String? = words.getOrNull(position.chapter)?.getOrNull(position.word)

        fun text(start: BookPosition, count: Int): String =
            (0 until count).mapNotNull { word(index.advance(start, it)) }.joinToString(" ")

        /** What Whisper might hear: ~8% of words wrong, ~4% dropped, ~4% extra. */
        fun noisyTranscript(start: BookPosition, count: Int, random: Random): String {
            val out = mutableListOf<String>()
            for (i in 0 until count) {
                val word = word(index.advance(start, i)) ?: continue
                when (random.nextInt(100)) {
                    in 0..7 -> out += randomWord(random)
                    in 8..11 -> Unit
                    in 12..15 -> { out += word; out += randomWord(random) }
                    else -> out += word
                }
            }
            return out.joinToString(" ")
        }

        private fun syllables(i: Int): String {
            val parts = listOf("ka", "lo", "ren", "ti", "mar", "sel", "do", "vin", "ash", "el", "tor", "un", "bri", "go")
            var n = i
            val sb = StringBuilder()
            do {
                sb.append(parts[n % parts.size]); n /= parts.size
            } while (n > 0)
            return sb.toString()
        }
    }

    /** A narrator reading the book steadily from [start]. */
    private class Narrator(val book: SyntheticBook, val start: BookPosition, val wordsPerSecond: Double) {
        fun positionAt(seconds: Int): BookPosition = book.index.advance(start, (seconds * wordsPerSecond).toInt())

        fun transcript(seconds: Int, length: Int, random: Random): String =
            book.noisyTranscript(positionAt(seconds), (length * wordsPerSecond).toInt(), random)

        fun isWithin(position: BookPosition, seconds: Int, words: Int): Boolean {
            val distance = book.index.distance(positionAt(seconds), position) ?: return false
            return abs(distance) <= words
        }
    }
}
