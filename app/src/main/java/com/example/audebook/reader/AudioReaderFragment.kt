/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.example.audebook.reader

import android.content.Context
import android.content.res.AssetManager

import android.annotation.SuppressLint
import android.media.AudioManager
import android.media.MediaPlayer

import android.os.Bundle
import android.os.Environment
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.readium.adapter.exoplayer.audio.ExoPlayerEngine
import org.readium.adapter.exoplayer.audio.ExoPlayerPreferences
import org.readium.adapter.exoplayer.audio.ExoPlayerSettings
import org.readium.navigator.media.audio.AudioNavigator
import org.readium.navigator.media.common.MediaNavigator
import org.readium.navigator.media.common.TimeBasedMediaNavigator
import org.readium.r2.navigator.preferences.Configurable
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.cover
import com.example.audebook.R
import com.example.audebook.databinding.FragmentAudiobookBinding
import com.example.audebook.domain.toUserError
import com.example.audebook.reader.preferences.UserPreferencesViewModel
import com.example.audebook.utils.UserError
import com.example.audebook.utils.viewLifecycle
import timber.log.Timber

import com.example.audebook.asr.Player;
import com.example.audebook.utils.WaveUtil;
import com.example.audebook.asr.Recorder;
import com.example.audebook.asr.Whisper

import com.arthenica.ffmpegkit.FFmpegKit

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import android.util.Log;
import org.readium.r2.shared.publication.epub.listOfAudioClips
import org.readium.r2.shared.util.toUri

@OptIn(ExperimentalReadiumApi::class)
class AudioReaderFragment : BaseReaderFragment(), SeekBar.OnSeekBarChangeListener {

    override lateinit var navigator: TimeBasedMediaNavigator<*, *, *>

    private var binding: FragmentAudiobookBinding by viewLifecycle()
    private var seekingItem: Int? = null

    // whisper-tiny.tflite and whisper-base-nooptim.en.tflite works well
    private val DEFAULT_MODEL_TO_USE = "whisper-tiny.tflite"
    // English only model ends with extension ".en.tflite"
    private val ENGLISH_ONLY_MODEL_EXTENSION = ".en.tflite"
    private val ENGLISH_ONLY_VOCAB_FILE = "filters_vocab_en.bin"
    private val MULTILINGUAL_VOCAB_FILE = "filters_vocab_multilingual.bin"
    private val EXTENSIONS_TO_COPY = arrayOf("tflite", "bin", "wav", "pcm")

    private var startTime: Long = 0

    private var mWhisper: Whisper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val readerData = model.readerInitData as MediaReaderInitData
        navigator = readerData.mediaNavigator

        if (savedInstanceState == null) {
            model.viewModelScope.launch {
//                navigator.play()
            }
        }

        val mediaPlayerTest = publication.get(publication.readingOrder[0].url())

        Timber.d("List of Audio Clips:  " + mediaPlayerTest!!.sourceUrl.toString() + " ")

        context?.let { ctx ->
            lifecycleScope.launch(Dispatchers.IO) {
                val sdcardDataFolder = ctx.getExternalFilesDir(null)
                if (sdcardDataFolder != null) {
                    copyAssetsToSdcard(ctx, sdcardDataFolder, EXTENSIONS_TO_COPY)
                    val selectedTfliteFile = File(sdcardDataFolder, "whisper-base.en.tflite")
//                    val selectedTfliteFile = File(sdcardDataFolder, "whisper-tiny.en.tflite")
                    val vocabFile = File(sdcardDataFolder, "filters_vocab_en.bin")

                    withContext(Dispatchers.Main) {
                        if (mWhisper == null) {
                            mWhisper = Whisper(ctx)
                            mWhisper?.loadModel(selectedTfliteFile, vocabFile, false)

                            mWhisper?.setListener(object : Whisper.WhisperListener {
                                override fun onUpdateReceived(message: String) {
                                    Timber.d("Update is received, Message: $message");

                                    when (message) {
                                        Whisper.MSG_PROCESSING -> {
//                                    handler.post { binding.tvStatus.text = message }
//                                    handler.post { binding.tvResult.text = "" }
                                            startTime = System.currentTimeMillis()
                                        }

                                        Whisper.MSG_PROCESSING_DONE -> {
                                            // handler.post { binding.tvStatus.text = message }
                                            // for testing
//                                    if (loopTesting) {
//                                        transcriptionSync.sendSignal()
//                                    }
                                        }

                                        Whisper.MSG_FILE_NOT_FOUND -> {
//                                    handler.post { binding.tvStatus.text = message }
                                            Timber.d("File not found error...!")
                                        }
                                    }
                                }

                                override fun onResultReceived(result: String) {
                                    val timeTaken = System.currentTimeMillis() - startTime
//                            handler.post { binding.tvStatus.text = "Processing done in ${timeTaken}ms" }

                                    binding.transcriptionResult.text = result + " \n\n\n" + timeTaken + "ms"

                                    Timber.d("Result: $result")
                                    Timber.d("timeTaken: $timeTaken ms")
//                            handler.post { binding.tvResult.append(result) }
                                }
                            })

                            Timber.d("Loaded Model");
                        }
                    }
                }
            }

        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAudiobookBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        @Suppress("Unchecked_cast")
        (navigator as? Configurable<ExoPlayerSettings, ExoPlayerPreferences>)
            ?.let { navigator ->
                @Suppress("Unchecked_cast")
                (model.settings as UserPreferencesViewModel<ExoPlayerSettings, ExoPlayerPreferences>)
                    .bind(navigator, viewLifecycleOwner)
            }

        binding.publicationTitle.text = model.publication.metadata.title

        viewLifecycleOwner.lifecycleScope.launch {
            publication.cover()?.let {
                binding.coverView.setImageBitmap(it)
            }
        }

        navigator.playback
            .onEach { onPlaybackChanged(it) }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun onPlaybackChanged(
        playback: TimeBasedMediaNavigator.Playback
    ) {
        Timber.v("onPlaybackChanged $playback")
        val failureState = playback.state as? AudioNavigator.State.Failure<*>
        if (failureState != null) {
            val error = failureState.error as ExoPlayerEngine.Error
            onPlayerError(error)
            return
        }

        binding.playPause.isEnabled = true
        binding.timelineBar.isEnabled = true
        binding.timelineDuration.isEnabled = true
        binding.timelinePosition.isEnabled = true
        binding.playPause.setImageResource(
            if (playback.playWhenReady) {
                R.drawable.ic_baseline_pause_24
            } else {
                R.drawable.ic_baseline_play_arrow_24
            }
        )

        if (seekingItem == null) {
            updateTimeline(playback)
        }

        if (playback.playWhenReady == true){

            Timber.d("onPlaybackChanged $playback")
            transcribeAudio()
        }
    }

    private fun updateTimeline(
        playback: TimeBasedMediaNavigator.Playback
    ) {
        val currentItem = navigator.readingOrder.items[playback.index]
        binding.timelineBar.max = currentItem.duration?.inWholeSeconds?.toInt() ?: 0
        binding.timelineDuration.text = currentItem.duration?.formatElapsedTime()
        binding.timelineBar.progress = playback.offset.inWholeSeconds.toInt()
        binding.timelinePosition.text = playback.offset.formatElapsedTime()
        binding.timelineBar.secondaryProgress = playback.buffered?.inWholeSeconds?.toInt() ?: 0
    }

    private fun Duration.formatElapsedTime(): String =
        DateUtils.formatElapsedTime(toLong(DurationUnit.SECONDS))

    private fun onPlayerError(error: ExoPlayerEngine.Error) {
        binding.playPause.isEnabled = false
        binding.timelineBar.isEnabled = false
        binding.timelinePosition.isEnabled = false
        binding.timelineDuration.isEnabled = false
        val userError = when (error) {
            is ExoPlayerEngine.Error.Engine ->
                UserError(error.message, error)
            is ExoPlayerEngine.Error.Source ->
                error.cause.toUserError()
        }
        userError.show(requireActivity())
    }

    override fun onResume() {
        super.onResume()
        activity?.volumeControlStream = AudioManager.STREAM_MUSIC
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStart() {
        super.onStart()
        binding.timelineBar.setOnTouchListener(this::forbidUserSeeking)
        binding.timelineBar.setOnSeekBarChangeListener(this)
        binding.playPause.setOnClickListener(this::onPlayPause)
        binding.skipForward.setOnClickListener(this::onSkipForward)
        binding.skipBackward.setOnClickListener(this::onSkipBackward)

        binding.transcribe.setOnClickListener(this::onTranscribe)
//        binding.transcribe.setOnClickListener(this::onExtractAndTranscribe)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun forbidUserSeeking(view: View, event: MotionEvent): Boolean =
        navigator.playback.value.state is MediaNavigator.State.Ended

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser) {
            binding.timelinePosition.text = progress.seconds.formatElapsedTime()
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        Timber.d("onStartTrackingTouch")
        seekingItem = navigator.playback.value.index
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        Timber.d("onStopTrackingTouch")
        navigator.skipTo(checkNotNull(seekingItem), seekBar.progress.seconds)
        seekingItem = null
    }

    private fun onPlayPause(@Suppress("UNUSED_PARAMETER") view: View) {
        return when (navigator.playback.value.state) {
            is MediaNavigator.State.Ready, is MediaNavigator.State.Buffering -> {
                model.viewModelScope.launch {
                    if (navigator.playback.value.playWhenReady) {
                        navigator.pause()
                    } else {
                        navigator.play()
                    }
                }
                Unit
            }
            is MediaNavigator.State.Ended -> {
                model.viewModelScope.launch {
                    navigator.skipTo(0, Duration.ZERO)
                    navigator.play()
                }
                Unit
            }
            is MediaNavigator.State.Failure -> {
                // Do nothing.
            }
        }
    }

    private fun onTranscribe(@Suppress("UNUSED_PARAMETER") view: View) {
        model.viewModelScope.launch {
//            mWhisper.skipForward()
            if (mWhisper?.isInProgress() == false) {

                val sdcardDataFolder = withContext(Dispatchers.IO) {
                    context?.getExternalFilesDir(null)
                }

//            context?.let { ctx ->
//                val sdcardDataFolder = ctx.getExternalFilesDir(null)
                if (sdcardDataFolder != null) {
//                    binding.transcriptionResult.text = "Transcribing..."


                    val files = listOf(
                        "jfk.wav",
                        "english_test2.wav",
                        "english_test1.wav",
                        "english_test_3_bili.wav"
                    )
                    val randomFile = files.random()

                    Timber.d(navigator.readingOrder.items[0].toString())

//                    val inputFilePath = "data/user/0/com.example.audebook/files/b901dd88-80c9-44b6-94fc-46bd4d609a96.m4a"
                    val inputFilePath =
                        publication.get(publication.readingOrder[0].url())!!.sourceUrl.toString()
                    val outputFilePath =
                        sdcardDataFolder.getAbsolutePath() + "/extracted_segment.m4a"
                    val timestamp = binding.timelinePosition.text.toString()
                    val duration = 15

                    val startTimeInSeconds = convertTimestampToSeconds(timestamp)
                    val startTimeFormatted = String.format(
                        "%02d:%02d:%02d",
                        startTimeInSeconds / 3600,
                        (startTimeInSeconds % 3600) / 60,
                        startTimeInSeconds % 60
                    )
//d
                    extractAudioSegment(inputFilePath, outputFilePath, startTimeFormatted, duration)
//d


                    val selectedWaveFile = File(sdcardDataFolder, randomFile)

                    mWhisper?.apply {
//                            setFilePath(selectedWaveFile.absolutePath)
//                            Timber.d(selectedWaveFile.absolutePath)
//                            playAudio(selectedWaveFile.absolutePath)
                        setFilePath(outputFilePath + ".wav")
                        Timber.d(outputFilePath + ".wav")
//                            playAudio(outputFilePath + ".wav")
                        setAction(Whisper.ACTION_TRANSCRIBE)
                        start()
                    }


                }
            } else {
                Timber.d("Whisper is already in progress...!");
            }
//            }
        }
    }

    private fun onSkipForward(@Suppress("UNUSED_PARAMETER") view: View) {
        model.viewModelScope.launch {
            navigator.skipForward()
        }
    }

    private fun onSkipBackward(@Suppress("UNUSED_PARAMETER") view: View) {
        model.viewModelScope.launch {
            navigator.skipBackward()
        }
    }

    override fun go(locator: Locator, animated: Boolean) {
        model.viewModelScope.launch {
            navigator.go(locator)
            navigator.play()
        }
    }

    private suspend fun copyAssetsToSdcard(context: Context, destFolder: File, extensions: Array<String>) {
        val assetManager: AssetManager = context.assets

        try {
            // List all files in the assets folder once
            val assetFiles: Array<String> = withContext(Dispatchers.IO) {
                assetManager.list("") ?: return@withContext emptyArray()
            }

            for (assetFileName in assetFiles) {
                // Check if file matches any of the provided extensions
                for (extension in extensions) {
                    if (assetFileName.endsWith(".$extension")) {
                        val outFile = File(destFolder, assetFileName)

                        // Skip if file already exists
                        if (withContext(Dispatchers.IO) { outFile.exists() }) break

                        // Copy the file from assets to the destination folder
                        withContext(Dispatchers.IO) {
                            assetManager.open(assetFileName).use { inputStream ->
                                FileOutputStream(outFile).use { outputStream ->
                                    val buffer = ByteArray(1024)
                                    var bytesRead: Int
                                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                        outputStream.write(buffer, 0, bytesRead)
                                    }
                                }
                            }
                        }
                        break // No need to check further extensions
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }



    private suspend fun extractAudioSegment(
        inputFilePath: String,
        outputFilePath: String,
        startTime: String,
        duration: Int
    ) {
        withContext(Dispatchers.IO) {
            val command = "-y -ss $startTime -analyzeduration 1000000 -probesize 500000 -i $inputFilePath  -t $duration -c copy $outputFilePath"
            Timber.d(command)
            FFmpegKit.execute(command)

            if (!outputFilePath.endsWith(".wav")){
                // TODODODODODOODO
                val convertCommand = "-y -i $outputFilePath $outputFilePath.wav"
                FFmpegKit.execute(convertCommand)
            }
        }
    }

    private fun convertTimestampToSeconds(timestamp: String): Int {
        val parts = timestamp.split(":").map { it.toInt() }
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    }

    private fun playAudio(filePath: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val mediaPlayer = MediaPlayer()
            try {
                mediaPlayer.setDataSource(filePath)
                mediaPlayer.prepare()
                mediaPlayer.setOnPreparedListener {
                    it.start()
                }
                mediaPlayer.setOnCompletionListener {
                    it.release()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun transcribeAudio() {
        model.viewModelScope.launch {
            if (mWhisper?.isInProgress() == false) {
                val sdcardDataFolder = withContext(Dispatchers.IO) {
                    context?.getExternalFilesDir(null)
                }

                if (sdcardDataFolder != null) {

                    Timber.d(navigator.readingOrder.items[0].toString())

                    val inputFilePath =
                        publication.get(publication.readingOrder[0].url())!!.sourceUrl.toString()
                    val inputFileType = inputFilePath.substringAfterLast('.', "")
                    val outputFilePath =
                        sdcardDataFolder.absolutePath + "/extracted_segment.m4a"
                    val timestamp = binding.timelinePosition.text.toString()
                    val duration = 15

                    val startTimeInSeconds = convertTimestampToSeconds(timestamp)
                    val startTimeFormatted = String.format(
                        "%02d:%02d:%02d",
                        startTimeInSeconds / 3600,
                        (startTimeInSeconds % 3600) / 60,
                        startTimeInSeconds % 60
                    )

                    extractAudioSegment(inputFilePath, outputFilePath, startTimeFormatted, duration)

                    mWhisper?.apply {
                        setFilePath(outputFilePath + ".wav")
                        Timber.d(outputFilePath + ".wav")
                        setAction(Whisper.ACTION_TRANSCRIBE)
                        start()
                    }
                }
            } else {
                Timber.d("Whisper is already in progress...!")
            }
        }
    }
}
