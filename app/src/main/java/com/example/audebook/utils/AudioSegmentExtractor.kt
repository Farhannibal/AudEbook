package com.example.audebook.utils

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import timber.log.Timber

/**
 * Cuts a time window out of an audio file and writes it as the WAV format Whisper expects
 * (16 kHz, mono, 16-bit PCM), using the platform decoders instead of FFmpeg.
 */
object AudioSegmentExtractor {

    private const val TARGET_SAMPLE_RATE = 16000
    private const val TIMEOUT_US = 10_000L

    /**
     * Decodes [durationSeconds] of audio starting at [startSeconds] from [inputPath] and writes
     * it to [outputWavPath]. Returns false if the file has no audio track or decoding fails.
     */
    fun extractToWav(
        inputPath: String,
        outputWavPath: String,
        startSeconds: Int,
        durationSeconds: Int
    ): Boolean {
        val startUs = startSeconds * 1_000_000L
        val endUs = startUs + durationSeconds * 1_000_000L

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(inputPath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run {
                Timber.e("No audio track in $inputPath")
                return false
            }
            val inputFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val mono = FloatArrayBuilder(durationSeconds * sampleRate)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(inIndex)!!, 0)
                        if (size < 0 || extractor.sampleTime >= endUs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

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
                            appendMono(
                                buffer.slice().order(ByteOrder.nativeOrder()),
                                pcmEncoding, channelCount, sampleRate,
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

            val resampled = resample(mono.toArray(), sampleRate, TARGET_SAMPLE_RATE)
            WaveUtil.createWaveFile(outputWavPath, toPcm16(resampled), TARGET_SAMPLE_RATE, 1, 2)
            Timber.d("Extracted ${resampled.size} samples (${sampleRate} Hz, ${channelCount} ch source) to $outputWavPath")
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract audio segment from $inputPath")
            return false
        } finally {
            try {
                decoder?.stop()
            } catch (_: IllegalStateException) {
            }
            decoder?.release()
            extractor.release()
        }
    }

    /**
     * Mixes one decoded buffer down to mono, keeping only frames whose timestamp falls inside
     * [startUs, endUs). The seek lands on the previous sync frame, so leading frames get dropped.
     */
    private fun appendMono(
        buffer: ByteBuffer,
        pcmEncoding: Int,
        channelCount: Int,
        sampleRate: Int,
        bufferTimeUs: Long,
        startUs: Long,
        endUs: Long,
        out: FloatArrayBuilder
    ) {
        val isFloat = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameCount = buffer.remaining() / (bytesPerSample * channelCount)

        for (frame in 0 until frameCount) {
            val frameTimeUs = bufferTimeUs + frame * 1_000_000L / sampleRate
            var sum = 0f
            for (ch in 0 until channelCount) {
                sum += if (isFloat) buffer.getFloat() else buffer.getShort() / 32768f
            }
            if (frameTimeUs in startUs until endUs) {
                out.add(sum / channelCount)
            }
        }
    }

    /**
     * Downsampling averages each output sample's span of source samples, which acts as a
     * simple low-pass filter against aliasing. Upsampling falls back to linear interpolation.
     */
    private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate
        val outLength = (input.size / ratio).toInt()
        val output = FloatArray(outLength)

        if (ratio > 1.0) {
            for (i in 0 until outLength) {
                val from = (i * ratio).toInt()
                val to = ((i + 1) * ratio).toInt().coerceAtMost(input.size).coerceAtLeast(from + 1)
                var sum = 0f
                for (j in from until to) sum += input[j]
                output[i] = sum / (to - from)
            }
        } else {
            for (i in 0 until outLength) {
                val pos = i * ratio
                val index = pos.toInt()
                val next = (index + 1).coerceAtMost(input.size - 1)
                val frac = (pos - index).toFloat()
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

    private class FloatArrayBuilder(initialCapacity: Int) {
        private var data = FloatArray(initialCapacity.coerceAtLeast(1024))
        private var size = 0

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
