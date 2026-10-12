package com.example.audebook

import com.example.audebook.utils.TranscriptMatcher
import info.debatty.java.stringsimilarity.JaroWinkler
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptMatcherTest {

    @Test
    fun matchesTheOriginalAlgorithm() {
        val random = Random(42)
        var casesWithMatches = 0

        repeat(2000) { case ->
            val book = generateProse(random, 300)
            val chunks = chunk(random, book)
            val transcript = noisyTranscript(random, book)

            val expected = chunks.indices.filter { originalIsSentenceInParagraph(chunks[it], transcript) }
            val actual = TranscriptMatcher.findMatchingChunks(chunks, transcript)

            assertEquals("case $case", expected, actual)
            if (actual.isNotEmpty()) casesWithMatches++
        }
        // Guard against a generator that never produces matches, which would make the check vacuous
        assertTrue("only $casesWithMatches cases had matches", casesWithMatches > 1000)
    }

    @Test
    fun findsTheChunksTheTranscriptCovers() {
        val chunks = listOf(
            "The lamplighter crossed the square",
            "before the bells had finished,",
            "and nobody saw him go.",
            "Far to the north, the river froze."
        )
        val transcript = "the lamp lighter crossed the square before the bells had finished and nobody saw him go"

        assertEquals(listOf(0, 1, 2), TranscriptMatcher.findMatchingChunks(chunks, transcript))
    }

    /** Verbatim copy of EpubReaderFragment.isSentenceInParagraph before the rewrite. */
    private fun originalIsSentenceInParagraph(
        sentence: String,
        paragraph: String,
        threshold: Double = 0.75
    ): Boolean {
        val jaroWinkler = JaroWinkler()
        val cleanedParagraph = originalRemovePunctuation(paragraph).lowercase()
        val cleanedSentence = originalRemovePunctuation(sentence).lowercase()
        val words = cleanedParagraph.split(" ")
        val sentenceWords = cleanedSentence.split(" ")

        for (i in 0..(words.size - sentenceWords.size)) {
            val subParagraph = words.subList(i, i + sentenceWords.size).joinToString(" ")
            val similarity = jaroWinkler.similarity(cleanedSentence, subParagraph)
            if (similarity >= threshold) {
                return true
            }
        }
        return false
    }

    private fun originalRemovePunctuation(text: String): String {
        return text.replace(Regex("[^\\w\\s]"), "")
    }

    private val vocabulary = listOf(
        "the", "a", "and", "of", "to", "in", "was", "he", "she", "it", "that", "his", "her",
        "lantern", "river", "stone", "gate", "tower", "whisper", "morning", "silver", "road",
        "walked", "turned", "remembered", "carried", "opened", "listened", "waited", "fell",
        "quietly", "slowly", "again", "never", "always", "beneath", "across", "toward",
        "guild", "torturer", "apprentice", "citadel", "necropolis", "mist", "lamplighter"
    )

    private fun generateProse(random: Random, wordCount: Int): List<String> =
        List(wordCount) { i ->
            var word = vocabulary.random(random)
            if (i == 0 || random.nextInt(12) == 0) word = word.replaceFirstChar { it.uppercase() }
            when (random.nextInt(14)) {
                0 -> "$word,"
                1 -> "$word."
                2 -> "$word;"
                3 -> "“$word”"
                4 -> "$word's"
                else -> word
            }
        }

    /** Splits prose into consecutive chunks of 3-9 words, like the merged word ranges in the reader. */
    private fun chunk(random: Random, words: List<String>): List<String> {
        val chunks = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val size = random.nextInt(3, 10).coerceAtMost(words.size - i)
            val separator = if (random.nextInt(20) == 0) "  " else " "
            chunks += words.subList(i, i + size).joinToString(separator)
            i += size
        }
        return chunks.take(90)
    }

    /** A Whisper-like transcript: a span of the prose with dropped, swapped and misspelled words. */
    private fun noisyTranscript(random: Random, words: List<String>): String {
        val start = random.nextInt(0, words.size - 40)
        val span = words.subList(start, start + random.nextInt(15, 40))
        return span.mapNotNull { word ->
            when (random.nextInt(20)) {
                0 -> null
                1 -> vocabulary.random(random)
                2 -> word.dropLast(1)
                3 -> word.uppercase()
                else -> word
            }
        }.joinToString(" ", prefix = if (random.nextBoolean()) " " else "")
    }
}
