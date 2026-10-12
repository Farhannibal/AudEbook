package com.example.audebook

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.audebook.engine.WhisperEngineJava
import com.example.audebook.utils.AudioSegmentExtractor
import com.example.audebook.utils.WaveUtil
import java.io.File
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks that [AudioSegmentExtractor] produces the 16 kHz mono WAV Whisper expects, and that
 * Whisper can transcribe it.
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

        val output = File(context.cacheDir, "$fileName.wav")
        val startedAt = System.currentTimeMillis()
        // Override with: am instrument -e startSeconds 600 ...
        val startSeconds = InstrumentationRegistry.getArguments().getString("startSeconds")?.toInt() ?: 120
        val ok = AudioSegmentExtractor.extractToWav(input.absolutePath, output.absolutePath, startSeconds, 10)
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue("extraction failed for $fileName", ok)

        val header = output.readBytes().copyOfRange(0, 44)
        val channels = (header[22].toInt() and 0xff) or ((header[23].toInt() and 0xff) shl 8)
        val sampleRate = (0..3).sumOf { (header[24 + it].toInt() and 0xff) shl (8 * it) }
        assertEquals(1, channels)
        assertEquals(16000, sampleRate)

        val samples = WaveUtil.getSamples(output.absolutePath)
        // 10 s at 16 kHz, allowing one codec frame of slack at either end
        assertTrue("got ${samples.size} samples", samples.size in 158_000..160_000)

        val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)
        assertTrue("clip is silent (rms=$rms)", rms > 0.005)

        val transcript = transcribe(output)
        Log.i(TAG, "$fileName: ${samples.size} samples, rms=%.4f, extracted in ${elapsed} ms".format(rms))
        Log.i(TAG, "$fileName transcript: $transcript")
        assertTrue("empty transcript", transcript.isNotBlank())
    }

    private fun transcribe(wav: File): String {
        val model = copyAsset("whisper-tiny-en.tflite")
        val vocab = copyAsset("filters_vocab_en.bin")
        val engine = WhisperEngineJava(context)
        engine.initialize(model.absolutePath, vocab.absolutePath, false)
        return try {
            engine.transcribeFile(wav.absolutePath)
        } finally {
            engine.deinitialize()
        }
    }

    private fun copyAsset(name: String): File {
        val file = File(context.cacheDir, name)
        if (!file.exists()) {
            context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return file
    }

    companion object {
        private const val TAG = "AudioSegmentExtractorTest"
    }
}
