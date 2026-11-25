package com.example.audebook.utils

import android.content.Context
import android.net.Uri
import java.io.Closeable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import timber.log.Timber

/**
 * Cuts windows out of a whole audiobook in book time (see [AudiobookTimeline]), reading each one
 * from the track it falls in. A window that runs over the end of a track is finished from the
 * start of the next, so the slots on either side of a chapter break are transcribed whole.
 *
 * Only the tracks being read are kept open: the current one and the one after it.
 */
class AudiobookSegmentExtractor(
    private val context: Context,
    private val tracks: List<Uri>,
    private val timeline: AudiobookTimeline
) : Closeable {

    // Least recently used first
    private val open = LinkedHashMap<Int, AudioSegmentExtractor>(4, 0.75f, true)

    /**
     * Decodes [durationSeconds] of the book starting at [startSeconds] and writes it to
     * [outputWavPath]. Returns false if nothing could be decoded.
     */
    @Synchronized
    fun extractToWav(outputWavPath: String, startSeconds: Int, durationSeconds: Int): Boolean {
        var (track, offset) = timeline.trackAt(startSeconds.seconds)
        var remaining = durationSeconds.seconds
        var samples = FloatArray(0)

        while (remaining > Duration.ZERO && track < tracks.size) {
            val trackLeft = trackLength(track) - offset
            val part = if (track < tracks.size - 1) minOf(remaining, trackLeft) else remaining
            if (part > Duration.ZERO) {
                val extractor = extractorFor(track) ?: return false
                val decoded = extractor.extractSamples(offset.inWholeMicroseconds, part.inWholeMicroseconds)
                    ?: return false
                samples += decoded
                remaining -= part
            }
            track++
            offset = Duration.ZERO
        }
        if (samples.isEmpty()) return false
        AudioSegmentExtractor.writeWav(outputWavPath, samples)
        return true
    }

    @Synchronized
    override fun close() {
        open.values.forEach { it.close() }
        open.clear()
    }

    private fun trackLength(track: Int): Duration =
        if (track + 1 < timeline.trackStarts.size) {
            timeline.trackStarts[track + 1] - timeline.trackStarts[track]
        } else {
            timeline.duration - timeline.trackStarts.getOrElse(track) { Duration.ZERO }
        }

    private fun extractorFor(track: Int): AudioSegmentExtractor? {
        open[track]?.let { return it }
        val extractor = try {
            AudioSegmentExtractor.open(context, tracks[track])
        } catch (e: Exception) {
            Timber.e(e, "Cannot open track $track for transcription: ${tracks[track]}")
            return null
        }
        open[track] = extractor
        while (open.size > MAX_OPEN_TRACKS) {
            val oldest = open.keys.first()
            open.remove(oldest)?.close()
        }
        return extractor
    }

    private companion object {
        const val MAX_OPEN_TRACKS = 2
    }
}
