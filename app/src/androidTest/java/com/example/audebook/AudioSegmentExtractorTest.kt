package com.example.audebook

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.audebook.asr.Whisper
import com.example.audebook.engine.WhisperEngineJava
import com.example.audebook.utils.AudioSegmentExtractor
import com.example.audebook.utils.WaveUtil
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks that [AudioSegmentExtractor] produces the 16 kHz mono WAV Whisper expects, that
 * back-to-back windows decode the same audio as a fresh seek, and that Whisper can transcribe it.
 *
 * Push sample audiobooks first; missing files are skipped:
 *   adb shell mkdir -p /data/local/tmp/audtest
 *   adb shell cp "/sdcard/Download/<book>.m4b" /data/local/tmp/audtest/sample.m4b
 *   (likewise sample.m4a, sample.mp3)
 */
@RunWith(AndroidJUnit4::class)
class AudioSegmentExtractorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val inputDir = File("/data/local/tmp/audtest")

    @Test
    fun extractsM4b() = checkExtraction("sample.m4b")

    @Test
    fun extractsM4a() = checkExtraction("sample.m4a")

    @Test
    fun extractsMp3() = checkExtraction("sample.mp3")

    private fun checkExtraction(fileName: String) {
        val input = File(inputDir, fileName)
        assumeTrue("$input not pushed to device", input.canRead())
        // Override with: am instrument -e startSeconds 600 ...
        val startSeconds = InstrumentationRegistry.getArguments().getString("startSeconds")?.toInt() ?: 120

        // Three back-to-back windows from one open extractor
        val windows = AudioSegmentExtractor.open(context, Uri.fromFile(input)).use { extractor ->
            (0 until 3).map { i ->
                val output = File(context.cacheDir, "$fileName.$i.wav")
                val startedAt = System.currentTimeMillis()
                val ok = extractor.extractToWav(output.absolutePath, startSeconds + i * 10, 10)
                Log.i(TAG, "$fileName window $i extracted in ${System.currentTimeMillis() - startedAt} ms")
                assertTrue("extraction failed for $fileName window $i", ok)
                assertWavFormat(output)
                WaveUtil.getSamples(output.absolutePath)
            }
        }

        windows.forEachIndexed { i, samples ->
            // 10 s at 16 kHz, allowing one codec frame of slack at either end
            assertTrue("window $i: got ${samples.size} samples", samples.size in 158_000..160_000)
            val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)
            assertTrue("window $i is silent (rms=$rms)", rms > 0.005)
        }

        // The second window should match the second half of one 20 s cut from the same seek point.
        // Comparing against a separate seek to +10 s would not work for MP3, where MediaExtractor
        // estimates seek positions and can land a second or two away.
        val single = AudioSegmentExtractor.open(context, Uri.fromFile(input)).use { extractor ->
            val output = File(context.cacheDir, "$fileName.single.wav")
            assertTrue(extractor.extractToWav(output.absolutePath, startSeconds, 20))
            WaveUtil.getSamples(output.absolutePath)
        }
        // Compare 10 ms loudness envelopes rather than raw samples. A 10 s boundary rarely falls
        // on a whole source sample (441,000 vs 441,001 frames at 44.1 kHz), so the two cuts
        // resample on grids up to one source sample apart. Envelopes ignore that but still catch
        // dropped or repeated audio.
        val secondHalf = single.copyOfRange(single.size - windows[1].size, single.size)
        val similarity = correlation(envelope(windows[1]), envelope(secondHalf))
        Log.i(TAG, "$fileName contiguous vs single-cut envelope correlation: %.4f".format(similarity))
        assertTrue("contiguous window differs from single cut (r=$similarity)", similarity > 0.99)

        val transcript = transcribe(File(context.cacheDir, "$fileName.1.wav"))
        Log.i(TAG, "$fileName transcript: $transcript")
        assertTrue("empty transcript", transcript.isNotBlank())

        context.cacheDir.listFiles { f -> f.name.startsWith(fileName) }?.forEach { it.delete() }
    }

    private fun assertWavFormat(wav: File) {
        val header = wav.readBytes().copyOfRange(0, 44)
        val channels = (header[22].toInt() and 0xff) or ((header[23].toInt() and 0xff) shl 8)
        val sampleRate = (0..3).sumOf { (header[24 + it].toInt() and 0xff) shl (8 * it) }
        assertEquals(1, channels)
        assertEquals(16000, sampleRate)
    }

    /** Mean absolute amplitude per 10 ms (160 samples at 16 kHz). */
    private fun envelope(samples: FloatArray) = FloatArray(samples.size / 160) { i ->
        var sum = 0f
        for (j in 0 until 160) sum += abs(samples[i * 160 + j])
        sum / 160
    }

    private fun correlation(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until n) {
            ab += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        return ab / sqrt(aa * bb)
    }

    // Uses the same bundled, memory-mapped model the app loads
    private fun transcribe(wav: File): String {
        val engine = WhisperEngineJava(context)
        assertTrue(engine.initializeFromAssets(Whisper.MODEL_ASSET, Whisper.VOCAB_ASSET, false))
        return try {
            engine.transcribeFile(wav.absolutePath)
        } finally {
            engine.deinitialize()
        }
    }

    companion object {
        private const val TAG = "AudioSegmentExtractorTest"
    }
}
