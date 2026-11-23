package com.example.audebook

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.audebook.asr.Whisper
import com.example.audebook.engine.WhisperEngineJava
import com.example.audebook.reader.BookIndexer
import com.example.audebook.utils.AudioSegmentExtractor
import com.example.audebook.utils.sync.BookIndex
import com.example.audebook.utils.sync.SyncTracker
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.toUrl

/**
 * Checks the book index and sync tracker on a real EPUB and its real audiobook: how long indexing
 * takes, and whether transcribed slots are found in the book with no Anchor.
 *
 * Push the matching pair first; the test is skipped without them:
 *   adb shell mkdir -p /data/local/tmp/audtest
 *   adb shell cp "/sdcard/Download/<book>.epub" /data/local/tmp/audtest/book.epub
 *   adb shell cp "/sdcard/Download/<book>.m4a" /data/local/tmp/audtest/book.m4a
 * Results are logged under the BookSyncRealData tag.
 */
@RunWith(AndroidJUnit4::class)
class BookSyncRealDataTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File("/data/local/tmp/audtest")

    @Test
    fun findsRealAudioInTheRealBook() = runBlocking {
        val epub = File(dir, "book.epub")
        val audio = listOf("book.m4a", "book.m4b", "book.mp3").map { File(dir, it) }.firstOrNull { it.canRead() }
        assumeTrue("book.epub and its audiobook not pushed", epub.canRead() && audio != null)

        val readium = (context.applicationContext as Application).readium
        val asset = readium.assetRetriever.retrieve(epub.toUrl()).getOrNull()!!
        val publication = readium.publicationOpener.open(asset, allowUserInteraction = false).getOrNull()!!

        // Indexing cost: each chapter on its own, as it happens when a book opens
        val indexer = BookIndexer(publication)
        val chapterTimes = (0 until indexer.chapterCount).map { indexer.indexChapter(it) }
        val words = indexer.index.chapters().values.sumOf { it.wordCount }
        Log.i(
            TAG,
            "Indexed ${indexer.chapterCount} chapters, $words words: total ${chapterTimes.sum()} ms, " +
                "slowest chapter ${chapterTimes.maxOrNull()} ms, median ${chapterTimes.sorted()[chapterTimes.size / 2]} ms"
        )

        val engine = WhisperEngineJava(context)
        assertTrue(engine.initializeFromAssets(Whisper.MODEL_ASSET, Whisper.VOCAB_ASSET, false))
        val startSeconds = InstrumentationRegistry.getArguments().getString("starts")
            ?.split(",")?.map { it.trim().toInt() } ?: listOf(600, 1800, 3600)

        try {
            AudioSegmentExtractor.open(context, Uri.fromFile(audio)).use { extractor ->
                for (start in startSeconds) {
                    val tracker = SyncTracker(indexer.index)
                    val placements = mutableListOf<SyncTracker.Placement?>()
                    for (slot in 0 until SLOTS) {
                        val seconds = start + slot * SLOT_SECONDS
                        val wav = File(context.cacheDir, "sync_$slot.wav")
                        check(extractor.extractToWav(wav.absolutePath, seconds, SLOT_SECONDS))
                        val transcript = engine.transcribeFile(wav.absolutePath)
                        wav.delete()

                        val placedAt = System.nanoTime()
                        val placement = tracker.place(seconds, SLOT_SECONDS, transcript)
                        val placeMicros = (System.nanoTime() - placedAt) / 1000
                        placements += placement

                        val bookText = placement?.let { textAt(indexer.index, it, 14) } ?: "-"
                        Log.i(TAG, "[$seconds s] ${placement?.kind ?: "NOT PLACED"} at ${placement?.start} ($placeMicros µs)")
                        Log.i(TAG, "    heard: ${transcript.trim().take(90)}")
                        Log.i(TAG, "    book:  ${bookText.take(90)}")
                    }

                    // Locks on within the first three slots, then moves forward at a narrator's pace
                    val lockedAt = placements.indexOfFirst { it != null && it.kind != SyncTracker.Kind.PREDICTED }
                    assertTrue("never found the audio at $start s", lockedAt in 0..2)
                    val after = placements.drop(lockedAt).filterNotNull()
                    for ((previous, next) in after.zipWithNext()) {
                        val step = indexer.index.distance(previous.start, next.start)
                        assertTrue("at ${next.slotSeconds} s moved $step words in one slot", step != null && step in 5..60)
                    }
                    Log.i(TAG, "start $start s: locked on at slot $lockedAt, pace ${"%.2f".format(tracker.wordsPerSecond)} words/s")
                }
            }
        } finally {
            engine.deinitialize()
        }
    }

    private fun textAt(index: BookIndex<*>, placement: SyncTracker.Placement, words: Int): String =
        index.chapter(placement.start.chapter)
            ?.spans(placement.start.word, placement.start.word + words - 1)
            ?.joinToString(" ") { it.element.text.substring(it.charStart, it.charEnd) }
            ?: "-"

    companion object {
        private const val TAG = "BookSyncRealData"
        private const val SLOTS = 6
        private const val SLOT_SECONDS = 10
    }
}
