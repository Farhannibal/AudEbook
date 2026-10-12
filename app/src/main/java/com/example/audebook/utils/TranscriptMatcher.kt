package com.example.audebook.utils

import info.debatty.java.stringsimilarity.JaroWinkler

/** Fuzzy matching between Whisper transcripts and EPUB text chunks. Pure, so safe off the main thread. */
object TranscriptMatcher {

    private val punctuation = Regex("[^\\w\\s]")

    /**
     * Returns the indices of [chunks] that appear somewhere in [transcription]: a chunk matches
     * when a run of transcript words the same length as the chunk scores at least [threshold]
     * with Jaro-Winkler.
     */
    fun findMatchingChunks(
        chunks: List<String>,
        transcription: String,
        threshold: Double = 0.75
    ): List<Int> {
        val jaroWinkler = JaroWinkler()
        // Clean and split the transcript once rather than once per chunk
        val words = removePunctuation(transcription).lowercase().split(" ")

        return chunks.indices.filter { index ->
            val cleanedChunk = removePunctuation(chunks[index]).lowercase()
            val windowSize = cleanedChunk.split(" ").size
            (0..(words.size - windowSize)).any { i ->
                val window = words.subList(i, i + windowSize).joinToString(" ")
                jaroWinkler.similarity(cleanedChunk, window) >= threshold
            }
        }
    }

    private fun removePunctuation(text: String): String = text.replace(punctuation, "")
}
