package com.example.audebook.utils.sync

/**
 * Finds where a transcript fits in the book by voting.
 *
 * Every three-word sequence in the transcript is looked up in the [BookIndex]. Each place it
 * occurs votes for the transcript starting at (book word - transcript word), so hits that agree
 * pile up on the same start. A sequence that occurs in many places ("and then he") spreads a
 * small vote thinly; a rare one ("the Shattered Plains") gives a strong vote in one place. A few
 * misheard words only cost a few votes, so noisy transcripts still land.
 */
class TranscriptAligner<E>(private val index: BookIndex<E>) {

    /** Where a transcript best fits, and how strongly. */
    data class Match(
        val start: BookPosition,
        val wordCount: Int,
        val score: Double,
        val runnerUpScore: Double
    ) {
        val endWord: Int get() = start.word + wordCount - 1

        /** Clearly better than the best alternative place. */
        val isClearWinner: Boolean get() = score >= runnerUpScore * CLEAR_WINNER_RATIO
    }

    /** Words [fromWord]..[toWord] of one chapter. */
    data class Range(val chapter: Int, val fromWord: Int, val toWord: Int)

    /** Every indexed chapter in full. */
    fun everywhere(): List<Range> =
        index.chapters().values.map { Range(it.number, 0, it.wordCount - 1) }

    /**
     * Ranges covering [before] words before [center] and [after] words after it, across chapter
     * boundaries where the neighbouring chapters are indexed.
     */
    fun around(center: BookPosition, before: Int, after: Int): List<Range> {
        val from = index.advance(center, -before)
        val to = index.advance(center, after)
        if (from.chapter == to.chapter) return listOf(Range(from.chapter, from.word, to.word))
        val ranges = mutableListOf<Range>()
        for (number in from.chapter..to.chapter) {
            val chapter = index.chapter(number) ?: continue
            val start = if (number == from.chapter) from.word else 0
            val end = if (number == to.chapter) to.word else chapter.wordCount - 1
            ranges += Range(number, start, end)
        }
        return ranges
    }

    /** The best place for [words] within [ranges], or null if no three-word sequence matches. */
    fun search(words: List<String>, ranges: List<Range>): Match? {
        if (words.size < 3 || ranges.isEmpty()) return null
        val hashes = IntArray(words.size) { words[it].hashCode() }

        class Hit(val chapter: Int, val bookWord: Int, val transcriptWord: Int, val weight: Double)

        val hits = mutableListOf<Hit>()
        for (t in 0 until words.size - 2) {
            val key = Words.trigramKey(hashes[t], hashes[t + 1], hashes[t + 2])
            val found = mutableListOf<Pair<Int, Int>>()
            for (range in ranges) {
                val chapter = index.chapter(range.chapter) ?: continue
                for (position in chapter.positionsOf(key)) {
                    if (position in range.fromWord..range.toWord) found += range.chapter to position
                }
            }
            if (found.isEmpty()) continue
            val weight = 1.0 / found.size
            found.forEach { (chapter, position) -> hits += Hit(chapter, position, t, weight) }
        }
        if (hits.isEmpty()) return null

        // Votes per (chapter, start bucket); buckets absorb a few inserted or dropped words
        fun bucketOf(hit: Hit) = Math.floorDiv(hit.bookWord - hit.transcriptWord, BUCKET_WORDS)
        val votes = HashMap<Pair<Int, Int>, Double>()
        for (hit in hits) {
            val key = hit.chapter to bucketOf(hit)
            votes[key] = (votes[key] ?: 0.0) + hit.weight
        }
        fun smoothed(chapter: Int, bucket: Int) =
            (bucket - 1..bucket + 1).sumOf { votes[chapter to it] ?: 0.0 }

        val best = votes.keys.maxByOrNull { (chapter, bucket) -> smoothed(chapter, bucket) } ?: return null
        val bestScore = smoothed(best.first, best.second)
        val runnerUp = votes.keys
            .filter { (chapter, bucket) -> chapter != best.first || kotlin.math.abs(bucket - best.second) > NEIGHBOURHOOD_BUCKETS }
            .maxOfOrNull { (chapter, bucket) -> smoothed(chapter, bucket) } ?: 0.0

        // Start of the transcript in the book: the median of the agreeing hits' implied starts
        val starts = hits
            .filter { it.chapter == best.first && kotlin.math.abs(bucketOf(it) - best.second) <= 1 }
            .map { it.bookWord - it.transcriptWord }
            .sorted()
        val chapter = index.chapter(best.first) ?: return null
        val start = starts[starts.size / 2].coerceIn(0, (chapter.wordCount - 1).coerceAtLeast(0))
        val wordCount = words.size.coerceAtMost(chapter.wordCount - start)

        return Match(BookPosition(best.first, start), wordCount, bestScore, runnerUp)
    }

    companion object {
        const val BUCKET_WORDS = 4
        const val NEIGHBOURHOOD_BUCKETS = 3
        const val CLEAR_WINNER_RATIO = 2.0
    }
}
