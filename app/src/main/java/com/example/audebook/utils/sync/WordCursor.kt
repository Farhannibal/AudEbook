package com.example.audebook.utils.sync

/**
 * Estimates the word being spoken within a slot, and makes consecutive slot highlights meet.
 * Pure, so it can be unit tested.
 */
object WordCursor {

    // A next slot starting further than this past a slot's own end is a jump elsewhere in the
    // book, not the continuation, so the highlight isn't stretched to reach it
    const val MAX_GAP_WORDS = 40

    /**
     * The last word of [placement]'s highlight, so that it runs right up to where [next] (the
     * following slot) starts. Whisper often hears a few words fewer than were read, so a slot's
     * own end can stop short of the next slot, leaving words highlighted by neither. Falls back to
     * the slot's own end when the next slot isn't placed, is in another chapter, or is a jump.
     */
    fun contiguousEnd(placement: SyncTracker.Placement, next: SyncTracker.Placement?): Int {
        if (next == null || next.start.chapter != placement.start.chapter) return placement.endWord
        val nextStart = next.start.word
        if (nextStart <= placement.start.word) return placement.endWord
        if (nextStart - 1 > placement.endWord + MAX_GAP_WORDS) return placement.endWord
        return nextStart - 1
    }

    /**
     * The word being spoken [fraction] (0..1) of the way through a slot covering words
     * [start]..[end]. Longer words take longer to say, so each word's share of the slot is its
     * length in characters plus one for the gap after it.
     */
    fun wordAt(fraction: Double, start: Int, end: Int, wordLength: (Int) -> Int): Int {
        if (end <= start) return start
        val shares = IntArray(end - start + 1) { wordLength(start + it).coerceAtLeast(1) + 1 }
        val target = fraction.coerceIn(0.0, 1.0) * shares.sum()
        var reached = 0
        for (i in shares.indices) {
            reached += shares[i]
            if (reached > target) return start + i
        }
        return end
    }
}
