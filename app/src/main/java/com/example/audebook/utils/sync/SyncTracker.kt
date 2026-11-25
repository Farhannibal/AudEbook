package com.example.audebook.utils.sync

import java.util.TreeMap
import kotlin.math.abs

/**
 * Keeps track of where the narrator is in the book and decides what to highlight for each slot.
 *
 * It remembers confident matches (audio time -> book position) and learns the narrator's pace from
 * them, which predicts where the next slot should be. For each transcribed slot it:
 *  1. searches near the predicted position, and accepts a decent match there (the one nearest
 *     the prediction when a line repeats nearby, which then doesn't count as a confident match);
 *  2. otherwise searches everything indexed, and accepts a strong, clear match there only once a
 *     second slot agrees with it, so one misheard slot can't drag the highlight away;
 *  3. otherwise falls back to the predicted position, so the highlight keeps moving.
 *
 * With no confident matches yet (no Anchor), the same agreement rule finds the starting position
 * on its own. Times are audio seconds, so the pace doesn't depend on playback speed.
 */
class SyncTracker<E>(private val index: BookIndex<E>) {

    enum class Kind {
        /** Matched near where the narrator was expected to be. */
        MATCHED,

        /** Found elsewhere in the book, confirmed by two slots agreeing. */
        RELOCATED,

        /** Not matched; where the narrator is estimated to be from their pace. */
        PREDICTED
    }

    /** Words [start]..[endWord] of [start]'s chapter, for the slot starting at [slotSeconds]. */
    data class Placement(val slotSeconds: Int, val start: BookPosition, val endWord: Int, val kind: Kind)

    private val aligner = TranscriptAligner(index)
    private val observations = TreeMap<Int, BookPosition>()
    private var pending: Pair<Int, TranscriptAligner.Match>? = null

    /** Narrator pace in words per audio second, learned from confident matches. */
    var wordsPerSecond = DEFAULT_WORDS_PER_SECOND
        private set

    val isAnchored: Boolean get() = observations.isNotEmpty()

    /** Forgets everything, e.g. when a different audiobook is loaded. */
    fun reset() {
        observations.clear()
        pending = null
        wordsPerSecond = DEFAULT_WORDS_PER_SECOND
    }

    /** The user says the slot at [slotSeconds] starts at [position]. Earlier matches are dropped. */
    fun anchor(slotSeconds: Int, position: BookPosition) {
        observations.clear()
        pending = null
        observations[slotSeconds] = position
    }

    /** Where the slot at [slotSeconds] should start, from the nearest confident matches. */
    fun predict(slotSeconds: Int): BookPosition? {
        val before = observations.floorEntry(slotSeconds)
        val after = observations.ceilingEntry(slotSeconds)
        if (before != null && after != null && before.key != after.key) {
            val words = index.distance(before.value, after.value)
            if (words != null && words >= 0) {
                val fraction = (slotSeconds - before.key).toDouble() / (after.key - before.key)
                return index.advance(before.value, (words * fraction).toInt())
            }
        }
        val nearest = when {
            before == null -> after
            after == null -> before
            slotSeconds - before.key <= after.key - slotSeconds -> before
            else -> after
        } ?: return null
        return index.advance(nearest.value, ((slotSeconds - nearest.key) * wordsPerSecond).toInt())
    }

    /** Places the transcript of the slot starting at [slotSeconds] that lasts [slotLength] seconds. */
    fun place(slotSeconds: Int, slotLength: Int, transcript: String): Placement? {
        val words = Words.normalize(transcript)
        val predicted = predict(slotSeconds)

        if (predicted != null && words.size >= 3) {
            // Uncertainty grows with distance from the nearest confident match
            val gap = observations.keys.minOfOrNull { abs(it - slotSeconds) } ?: 0
            val slack = (LOCAL_WINDOW_WORDS + gap * wordsPerSecond * LOCAL_WINDOW_GROWTH).toInt()
            val local = aligner.search(words, aligner.around(predicted, slack, slack + words.size), near = predicted)
            if (local != null && local.score >= MIN_LOCAL_SCORE) {
                if (local.isClearWinner) return accept(slotSeconds, local, Kind.MATCHED)
                // Fits just as well somewhere else nearby (a line repeated on nearby pages), so it
                // went to the place nearest the prediction. Highlight it, but don't let it steer
                // where the next slots are looked for.
                return Placement(slotSeconds, local.start, local.endWord, Kind.MATCHED)
            }
        }

        if (words.size >= 3) {
            val global = aligner.search(words, aligner.everywhere())
            if (global != null && global.score >= MIN_GLOBAL_SCORE && global.isClearWinner) {
                val previous = pending
                pending = slotSeconds to global
                if (previous != null && agrees(previous, slotSeconds to global)) {
                    observations[previous.first] = previous.second.start
                    return accept(slotSeconds, global, Kind.RELOCATED)
                }
            }
        }

        if (predicted == null) return null
        val estimatedWords = (slotLength * wordsPerSecond).toInt().coerceAtLeast(1)
        val chapter = index.chapter(predicted.chapter) ?: return null
        val end = (predicted.word + estimatedWords - 1).coerceAtMost(chapter.wordCount - 1)
        return Placement(slotSeconds, predicted, end, Kind.PREDICTED)
    }

    private fun accept(slotSeconds: Int, match: TranscriptAligner.Match, kind: Kind): Placement {
        observations[slotSeconds] = match.start
        pending = null
        updatePace(slotSeconds)
        return Placement(slotSeconds, match.start, match.endWord, kind)
    }

    /** Two far-away matches agree if the words between them fit the time between them. */
    private fun agrees(first: Pair<Int, TranscriptAligner.Match>, second: Pair<Int, TranscriptAligner.Match>): Boolean {
        val seconds = second.first - first.first
        if (seconds <= 0 || seconds > MAX_AGREEMENT_SECONDS) return false
        val words = index.distance(first.second.start, second.second.start) ?: return false
        val expected = seconds * wordsPerSecond
        return abs(words - expected) <= AGREEMENT_TOLERANCE_WORDS + expected * 0.5
    }

    /** Re-estimates the pace from confident matches in the last few minutes before [slotSeconds]. */
    private fun updatePace(slotSeconds: Int) {
        val recent = observations.subMap(slotSeconds - PACE_WINDOW_SECONDS, true, slotSeconds, true)
        if (recent.size < 2) return
        val first = recent.firstEntry() ?: return
        val last = recent.lastEntry() ?: return
        val seconds = last.key - first.key
        if (seconds < MIN_PACE_SECONDS) return
        val words = index.distance(first.value, last.value) ?: return
        val measured = (words.toDouble() / seconds).coerceIn(MIN_WORDS_PER_SECOND, MAX_WORDS_PER_SECOND)
        wordsPerSecond = wordsPerSecond * (1 - PACE_SMOOTHING) + measured * PACE_SMOOTHING
    }

    companion object {
        // About 155 words a minute, typical for an audiobook narrator
        const val DEFAULT_WORDS_PER_SECOND = 2.6
        const val MIN_WORDS_PER_SECOND = 1.2
        const val MAX_WORDS_PER_SECOND = 4.5

        // Search this many words either side of the prediction, plus a share of the words the
        // narrator could have read since the nearest confident match
        const val LOCAL_WINDOW_WORDS = 150
        const val LOCAL_WINDOW_GROWTH = 0.5

        // Votes needed to accept a match: a couple of rare word sequences near the prediction,
        // more (and a clear winner) when searching the whole book
        const val MIN_LOCAL_SCORE = 1.5
        const val MIN_GLOBAL_SCORE = 3.0

        // Two far-away matches must be this close to what the pace predicts, and close in time
        const val AGREEMENT_TOLERANCE_WORDS = 40
        const val MAX_AGREEMENT_SECONDS = 60

        const val PACE_WINDOW_SECONDS = 180
        const val MIN_PACE_SECONDS = 30
        const val PACE_SMOOTHING = 0.5
    }
}
