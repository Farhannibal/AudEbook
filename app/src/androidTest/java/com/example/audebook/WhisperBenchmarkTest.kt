package com.example.audebook

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.audebook.engine.WhisperEngineJava
import com.example.audebook.utils.AudioSegmentExtractor
import com.example.audebook.utils.WaveUtil
import com.example.audebook.utils.WhisperUtil
import info.debatty.java.stringsimilarity.JaroWinkler
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Times Whisper with different thread counts and XNNPACK settings on real audiobook clips, and
 * checks each setting's transcripts against the current default (1 thread, XNNPACK off).
 *
 * Push a sample first; the test is skipped without it:
 *   adb shell mkdir -p /data/local/tmp/audtest
 *   adb shell cp "/sdcard/Download/<book>.m4a" /data/local/tmp/audtest/sample.m4a
 * Results are logged under the WhisperBenchmark tag.
 */
@RunWith(AndroidJUnit4::class)
class WhisperBenchmarkTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Config(val threads: Int, val xnnpack: Boolean) {
        override fun toString() = "threads=$threads xnnpack=${if (xnnpack) "on " else "off"}"
    }

    @Test
    fun compareConfigurations() {
        val input = listOf("sample.m4a", "sample.m4b", "sample.mp3")
            .map { File("/data/local/tmp/audtest", it) }
            .firstOrNull { it.canRead() }
        assumeTrue("no sample pushed to /data/local/tmp/audtest", input != null)

        val startSeconds = InstrumentationRegistry.getArguments().getString("startSeconds")?.toInt() ?: 600
        val clips = AudioSegmentExtractor.open(context, Uri.fromFile(input)).use { extractor ->
            (0 until CLIP_COUNT).map { i ->
                File(context.cacheDir, "bench_$i.wav").also {
                    check(extractor.extractToWav(it.absolutePath, startSeconds + i * 10, 10))
                }
            }
        }
        val model = copyAsset("whisper-tiny-en.tflite")
        val vocab = copyAsset("filters_vocab_en.bin")
        val cores = Runtime.getRuntime().availableProcessors()
        val baseline = Config(1, false)
        val configs = listOf(baseline, Config(1, true), Config(2, true), Config(4, true), Config(4, false))
            .plus(if (cores > 4) listOf(Config(cores, true)) else emptyList())

        Log.i(TAG, "Device: ${android.os.Build.MODEL}, $cores cores, ${input!!.name} from $startSeconds s")

        // Two rounds, the second in reverse, so heat build-up doesn't favour whichever runs first
        val timings = configs.associateWith { mutableListOf<Long>() }
        val transcripts = mutableMapOf<Config, List<String>>()
        for (round in listOf(configs, configs.reversed())) {
            for (config in round) {
                val engine = WhisperEngineJava(context, config.threads, config.xnnpack)
                engine.initialize(model.absolutePath, vocab.absolutePath, false)
                try {
                    engine.transcribeFile(clips[0].absolutePath) // warm-up
                    val texts = clips.map { clip ->
                        val startedAt = System.currentTimeMillis()
                        val text = engine.transcribeFile(clip.absolutePath)
                        timings.getValue(config) += System.currentTimeMillis() - startedAt
                        text
                    }
                    transcripts.putIfAbsent(config, texts)
                } finally {
                    engine.deinitialize()
                }
            }
        }

        val baselineMedian = timings.getValue(baseline).median()
        val jaroWinkler = JaroWinkler()
        for (config in configs) {
            val median = timings.getValue(config).median()
            val texts = transcripts.getValue(config)
            val identical = texts.zip(transcripts.getValue(baseline)).count { (a, b) -> a == b }
            val similarity = texts.zip(transcripts.getValue(baseline))
                .map { (a, b) -> jaroWinkler.similarity(a, b) }.average()
            Log.i(
                TAG,
                "%s  median %5d ms per clip  (%.2fx vs baseline)  transcripts identical %d/%d, similarity %.3f"
                    .format(config, median, baselineMedian.toDouble() / median, identical, texts.size, similarity)
            )
        }
        transcripts.getValue(baseline).forEachIndexed { i, text -> Log.i(TAG, "baseline clip $i: $text") }
        configs.drop(1).forEach { config ->
            transcripts.getValue(config).zip(transcripts.getValue(baseline)).forEachIndexed { i, (text, base) ->
                if (text != base) Log.i(TAG, "$config clip $i differs: $text")
            }
        }

        clips.forEach { it.delete() }
    }

    /** The optimised mel spectrogram must match the original bit for bit, so transcripts can't change. */
    @Test
    fun melMatchesLegacyImplementation() {
        val input = listOf("sample.m4a", "sample.m4b", "sample.mp3")
            .map { File("/data/local/tmp/audtest", it) }
            .firstOrNull { it.canRead() }
        assumeTrue("no sample pushed to /data/local/tmp/audtest", input != null)

        val vocab = copyAsset("filters_vocab_en.bin")
        val legacy = LegacyWhisperUtil().apply { loadFiltersAndVocab(false, vocab.absolutePath) }
        val current = WhisperUtil().apply { loadFiltersAndVocab(false, vocab.absolutePath) }
        val cores = Runtime.getRuntime().availableProcessors()
        val fixedSize = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE

        val legacyTimes = mutableListOf<Long>()
        val currentTimes = mapOf(1 to mutableListOf<Long>(), 2 to mutableListOf(), 4 to mutableListOf())
        AudioSegmentExtractor.open(context, Uri.fromFile(input)).use { extractor ->
            for (i in 0 until CLIP_COUNT) {
                val wav = File(context.cacheDir, "mel_$i.wav")
                check(extractor.extractToWav(wav.absolutePath, 600 + i * 10, 10))
                // Same 30 s zero padding as WhisperEngineJava
                val samples = WaveUtil.getSamples(wav.absolutePath).copyOf(fixedSize)
                wav.delete()

                var startedAt = System.currentTimeMillis()
                val expected = legacy.getMelSpectrogram(samples, fixedSize, cores).copyOf()
                legacyTimes += System.currentTimeMillis() - startedAt

                for ((threads, times) in currentTimes) {
                    startedAt = System.currentTimeMillis()
                    val actual = current.getMelSpectrogram(samples, fixedSize, threads)
                    times += System.currentTimeMillis() - startedAt
                    assertTrue("clip $i, $threads threads: mel differs from legacy", expected.contentEquals(actual))
                }
            }
        }

        Log.i(TAG, "mel legacy ($cores threads): median ${legacyTimes.median()} ms")
        currentTimes.forEach { (threads, times) ->
            Log.i(TAG, "mel optimised ($threads threads): median ${times.median()} ms, bit-identical on $CLIP_COUNT clips")
        }
    }

    private fun List<Long>.median() = sorted()[size / 2]

    private fun copyAsset(name: String): File {
        val file = File(context.cacheDir, name)
        if (!file.exists()) {
            context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return file
    }

    companion object {
        private const val TAG = "WhisperBenchmark"
        private const val CLIP_COUNT = 4
    }
}
