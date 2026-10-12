package com.example.audebook.utils

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import timber.log.Timber

/**
 * Cuts time windows out of one audio file and writes them as the WAV format Whisper expects
 * (16 kHz, mono, 16-bit PCM), using the platform decoders instead of FFmpeg.
 *
 * Open one per audiobook and reuse it: the file stays open, and a window that starts where the
 * previous one ended is decoded without seeking.
 */
class AudioSegmentExtractor private constructor(
    private val extractor: MediaExtractor
) : Closeable {

    private val decoder: MediaCodec
    private var sampleRate: Int
    private var channelCount: Int
    private var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

    /** End of the previous window, or -1 when the next call has to seek. */
    private var nextStartUs = -1L
    private var inputDone = false
    private var outputDone = false

    /** Decoded mono frames past the end of the previous window, starting at [pendingStartUs]. */
    private var pending = FloatArray(0)
    private var pendingStartUs = 0L

    init {
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw IllegalArgumentException("No audio track found")
        }
        val format = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()
    }

    /**
     * Decodes [durationSeconds] of audio starting at [startSeconds] and writes it to
     * [outputWavPath]. Returns false if decoding fails.
     */
    @Synchronized
    fun extractToWav(outputWavPath: String, startSeconds: Int, durationSeconds: Int): Boolean {
        val startUs = startSeconds * 1_000_000L
        val endUs = startUs + durationSeconds * 1_000_000L
        val startedAt = System.currentTimeMillis()

        return try {
            val contiguous = startUs == nextStartUs
            if (!contiguous) seekTo(startUs)

            val mono = FloatArrayBuilder(durationSeconds * sampleRate)
            var reachedEnd = takePending(startUs, endUs, mono)
            val info = MediaCodec.BufferInfo()

            while (!reachedEnd && !outputDone) {
                if (System.currentTimeMillis() - startedAt > DECODE_TIMEOUT_MS) {
                    Timber.e("Timed out decoding $startSeconds s + $durationSeconds s")
                    nextStartUs = -1L
                    return false
                }
                if (!inputDone) queueInput()

                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(outIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            reachedEnd = appendMono(
                                buffer.slice().order(ByteOrder.nativeOrder()),
                                info.presentationTimeUs, startUs, endUs, mono
                            )
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            nextStartUs = endUs

            val resampled = resample(mono.toArray(), sampleRate, TARGET_SAMPLE_RATE)
            WaveUtil.createWaveFile(outputWavPath, toPcm16(resampled), TARGET_SAMPLE_RATE, 1, 2)
            Timber.d(
                "Extracted ${resampled.size} samples at $startSeconds s " +
                    "(${if (contiguous) "contiguous" else "seek"}, ${System.currentTimeMillis() - startedAt} ms)"
            )
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract audio segment at $startSeconds s")
            nextStartUs = -1L
            false
        }
    }

    @Synchronized
    override fun close() {
        try {
            decoder.stop()
        } catch (_: IllegalStateException) {
        }
        decoder.release()
        extractor.release()
    }

    private fun seekTo(startUs: Long) {
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        decoder.flush()
        inputDone = false
        outputDone = false
        pending = FloatArray(0)
    }

    private fun queueInput() {
        val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
        if (inIndex < 0) return
        val size = extractor.readSampleData(decoder.getInputBuffer(inIndex)!!, 0)
        if (size < 0) {
            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    /** Moves leftover frames from the previous window into [out]. Returns true if they cover the whole window. */
    private fun takePending(startUs: Long, endUs: Long, out: FloatArrayBuilder): Boolean {
        val frames = pending
        pending = FloatArray(0)
        for (i in frames.indices) {
            val frameTimeUs = pendingStartUs + i * 1_000_000L / sampleRate
            if (frameTimeUs >= endUs) {
                pending = frames.copyOfRange(i, frames.size)
                pendingStartUs = frameTimeUs
                return true
            }
            if (frameTimeUs >= startUs) out.add(frames[i])
        }
        return false
    }

    /**
     * Mixes one decoded buffer down to mono, keeping only frames inside [startUs, endUs). Frames
     * past [endUs] are kept for the next window. Returns true once the window is complete.
     */
    private fun appendMono(
        buffer: ByteBuffer,
        bufferTimeUs: Long,
        startUs: Long,
        endUs: Long,
        out: FloatArrayBuilder
    ): Boolean {
        val isFloat = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameCount = buffer.remaining() / (bytesPerSample * channelCount)
        var overflow: FloatArrayBuilder? = null

        for (frame in 0 until frameCount) {
            val frameTimeUs = bufferTimeUs + frame * 1_000_000L / sampleRate
            var sum = 0f
            for (ch in 0 until channelCount) {
                sum += if (isFloat) buffer.getFloat() else buffer.getShort() / 32768f
            }
            val sample = sum / channelCount
            when {
                frameTimeUs < startUs -> Unit
                frameTimeUs < endUs -> out.add(sample)
                else -> {
                    if (overflow == null) {
                        overflow = FloatArrayBuilder(frameCount - frame)
                        pendingStartUs = frameTimeUs
                    }
                    overflow.add(sample)
                }
            }
        }
        overflow?.let { pending = it.toArray() }
        return overflow != null
    }

    private class FloatArrayBuilder(initialCapacity: Int) {
        private var data = FloatArray(initialCapacity.coerceAtLeast(1024))
        private var size = 0

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    companion object {
        private const val TARGET_SAMPLE_RATE = 16000
        private const val TIMEOUT_US = 10_000L
        private const val DECODE_TIMEOUT_MS = 15_000L

        /**
         * Opens [uri] for reading. Content URIs are read in place through the content resolver,
         * so the audiobook is never copied into app storage.
         */
        fun open(context: Context, uri: Uri): AudioSegmentExtractor {
            val extractor = MediaExtractor()
            try {
                if (uri.scheme == "file") {
                    extractor.setDataSource(uri.path!!)
                } else {
                    extractor.setDataSource(context, uri, null)
                }
            } catch (e: Exception) {
                extractor.release()
                throw e
            }
            return AudioSegmentExtractor(extractor)
        }

        /**
         * Downsampling averages each output sample's span of source samples, which acts as a
         * simple low-pass filter against aliasing. Upsampling falls back to linear interpolation.
         */
        private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
            if (fromRate == toRate || input.isEmpty()) return input
            // Source positions use integer math so a window resampled on its own lines up
            // exactly with the same audio resampled as part of a longer window
            val outLength = (input.size.toLong() * toRate / fromRate).toInt()
            val output = FloatArray(outLength)

            if (fromRate > toRate) {
                for (i in 0 until outLength) {
                    val from = (i.toLong() * fromRate / toRate).toInt()
                    val to = ((i + 1L) * fromRate / toRate).toInt().coerceAtMost(input.size).coerceAtLeast(from + 1)
                    var sum = 0f
                    for (j in from until to) sum += input[j]
                    output[i] = sum / (to - from)
                }
            } else {
                for (i in 0 until outLength) {
                    val scaled = i.toLong() * fromRate
                    val index = (scaled / toRate).toInt()
                    val next = (index + 1).coerceAtMost(input.size - 1)
                    val frac = (scaled % toRate).toFloat() / toRate
                    output[i] = input[index] * (1 - frac) + input[next] * frac
                }
            }
            return output
        }

        private fun toPcm16(samples: FloatArray): ByteArray {
            val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                bytes.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            return bytes.array()
        }
    }
}
