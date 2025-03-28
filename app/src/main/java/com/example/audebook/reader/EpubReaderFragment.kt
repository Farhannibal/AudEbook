/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.example.audebook.reader

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.view.*
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.SeekBar
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.ColorUtils
import androidx.core.os.BundleCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.FragmentResultListener
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.*
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.toCss
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.epub.pageList
import com.example.audebook.LITERATA
import com.example.audebook.R
import com.example.audebook.domain.ImportError
import com.example.audebook.reader.preferences.UserPreferencesViewModel
import com.example.audebook.search.SearchFragment
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.shared.publication.services.content.content
import timber.log.Timber

import info.debatty.java.stringsimilarity.JaroWinkler
import org.readium.navigator.media.common.TimeBasedMediaNavigator
import org.readium.r2.shared.publication.Publication

import kotlin.random.Random

import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit
import org.readium.r2.shared.util.Language
import java.util.Locale

import com.example.audebook.Readium
import com.example.audebook.Application
import com.example.audebook.asr.Whisper
import com.example.audebook.domain.PublicationError
import com.example.audebook.domain.toUserError
import com.example.audebook.utils.UserError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.json.JSONObject
import org.readium.adapter.exoplayer.audio.ExoPlayerEngine
import org.readium.adapter.exoplayer.audio.ExoPlayerEngineProvider
import org.readium.adapter.exoplayer.audio.ExoPlayerPreferences
import org.readium.adapter.exoplayer.audio.ExoPlayerSettings
import org.readium.navigator.media.audio.AudioNavigator
import org.readium.navigator.media.audio.AudioNavigatorFactory
import org.readium.navigator.media.common.MediaNavigator
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.getOrElse
import kotlin.time.Duration
import kotlin.time.DurationUnit


import com.arthenica.ffmpegkit.FFmpegKit
import com.example.audebook.domain.Bookshelf
//import com.example.audebook.domain.PublicationError
import com.example.audebook.domain.PublicationRetriever
import com.example.audebook.reader.preferences.AudioPreferencesBottomSheetDialogFragment
import com.example.audebook.reader.preferences.ExoPlayerPreferencesManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.format.Format
import org.readium.r2.shared.util.toUrl

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import org.readium.r2.navigator.preferences.Configurable
import java.io.InputStream
import kotlin.time.Duration.Companion.seconds

import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.locate


@OptIn(ExperimentalReadiumApi::class)
class EpubReaderFragment : VisualReaderFragment(), SeekBar.OnSeekBarChangeListener {

    override lateinit var navigator: EpubNavigatorFragment

    private lateinit var readium: Readium

//    lateinit var audioNavigator: TimeBasedMediaNavigator<*, *, *>
    lateinit var audioNavigator: AudioNavigator<*, *>
    lateinit var audioPublication: Publication

    private lateinit var menuSearch: MenuItem
    lateinit var menuSearchView: SearchView

    private lateinit var menuDebug: MenuItem

    private var isSearchViewIconified = true

    private lateinit var locators: MutableList<Locator>


    private lateinit var application: Application

//    private lateinit var navigatorPreferences: DataStore<Preferences>

//    private val Context.navigatorPreferences: DataStore<Preferences>
//            by preferencesDataStore(name = "navigator-preferences")

    private var seekingItem: Int? = null

    // whisper-tiny.tflite and whisper-base-nooptim.en.tflite works well
//    private val DEFAULT_MODEL_TO_USE = "whisper-tiny.tflite"
    // English only model ends with extension ".en.tflite"
//    private val ENGLISH_ONLY_MODEL_EXTENSION = ".en.tflite"
//    private val ENGLISH_ONLY_VOCAB_FILE = "filters_vocab_en.bin"
//    private val MULTILINGUAL_VOCAB_FILE = "filters_vocab_multilingual.bin"
    private val EXTENSIONS_TO_COPY = arrayOf("tflite", "bin", "wav", "pcm")

    private var startTime: Long = 0

    private var mWhisper: Whisper? = null

    private var epubBookId: Long = -1

    private lateinit var appStoragePickerLauncher: ActivityResultLauncher<String>
    private lateinit var sharedStoragePickerLauncher: ActivityResultLauncher<Array<String>>

    private val coroutineScope: CoroutineScope =
        MainScope()

    private var lastSaveTime: Long = 0

    var totalTimeOfAudio: String = "00:00:00"

    var currentTranscribeSegment: String = "00:00:00"

    private val transcriptionMap: MutableMap<String, String> = mutableMapOf()

    private lateinit var transcriptionRange: List<String>

    private val locatorMap: MutableMap<String, List<Locator>> = mutableMapOf()

    private var isLoadingInitTranscription: Boolean = false

    private lateinit var latestPlaybackStatus: TimeBasedMediaNavigator.Playback

    private var isFollowingAudio: Boolean = true
    private var lastHighlightUpdate: Long = 0

    lateinit var globalIterator: Content.Iterator

    lateinit var audioDialog: AudioPreferencesBottomSheetDialogFragment


    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            isSearchViewIconified = savedInstanceState.getBoolean(IS_SEARCH_VIEW_ICONIFIED)
        }

        application = requireActivity().application as Application
//        navigatorPreferences = application.navigatorPreferences
        readium = application.readium

        // Register for activity result here
        sharedStoragePickerLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                uri?.let {
                    val takeFlags: Int = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags)
                    addPublicationFromStorage(it.toUrl()!! as AbsoluteUrl)
                }
            }

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

                                    lifecycleScope.launch(Dispatchers.Main) {
//                                        binding.transcriptionResult.text = result + " \n\n\n" + timeTaken + "ms"
                                        transcriptionMap[currentTranscribeSegment] = result
                                        locatorMap[currentTranscribeSegment] = syncTranscriptionWithLocator(result,currentTranscribeSegment)

//                                        if (isLoadingInitTranscription && (transcriptionMap.count() <=2)){
//                                            transcribeAudio(getNext15SecondInterval(currentTranscribeSegment, 1))
//                                        }else{
//                                            loadThenPlayEnd()
//                                        }
                                    }



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




        locators = mutableListOf()
//        locatorMap = mutableMapOf()

        val readerData = model.readerInitData as? EpubReaderInitData ?: run {
            // We provide a dummy fragment factory  if the ReaderActivity is restored after the
            // app process was killed because the ReaderRepository is empty. In that case, finish
            // the activity as soon as possible and go back to the previous one.
            childFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
            super.onCreate(savedInstanceState)
            requireActivity().finish()
            return
        }

        epubBookId = readerData.bookId

        lifecycleScope.launch {
            try {
                loadAudiobookNavigator(epubBookId)
            } catch (e: Exception) {
                Timber.d("")
                Timber.e(e, "Failed to load audiobook navigator")
                // Handle the exception, e.g., show an error message to the user
            }
        }




        childFragmentManager.fragmentFactory =
            readerData.navigatorFactory.createFragmentFactory(
                initialLocator = readerData.initialLocation,
                initialPreferences = readerData.preferencesManager.preferences.value,
                listener = model,
                configuration = EpubNavigatorFragment.Configuration {
                    // To customize the text selection menu.
                    selectionActionModeCallback = customSelectionActionModeCallback

                    // App assets which will be accessible from the EPUB resources.
                    // You can use simple glob patterns, such as "images/.*" to allow several
                    // assets in one go.
                    servedAssets = listOf(
                        // For the custom font Literata.
                        "fonts/.*",
                        // Icon for the annotation side mark, see [annotationMarkTemplate].
                        "annotation-icon.svg"
                    )

                    // Register the HTML templates for our custom decoration styles.
                    decorationTemplates[DecorationStyleAnnotationMark::class] = annotationMarkTemplate()
                    decorationTemplates[DecorationStylePageNumber::class] = pageNumberTemplate()

                    // Declare a custom font family for reflowable EPUBs.
                    addFontFamilyDeclaration(FontFamily.LITERATA) {
                        addFontFace {
                            addSource("fonts/Literata-VariableFont_opsz,wght.ttf")
                            setFontStyle(FontStyle.NORMAL)
                            // Literata is a variable font family, so we can provide a font weight range.
                            setFontWeight(200..900)
                        }
                        addFontFace {
                            addSource("fonts/Literata-Italic-VariableFont_opsz,wght.ttf")
                            setFontStyle(FontStyle.ITALIC)
                            setFontWeight(200..900)
                        }
                    }
                }
            )



        childFragmentManager.setFragmentResultListener(
            SearchFragment::class.java.name,
            this,
            FragmentResultListener { _, result ->
                menuSearch.collapseActionView()
                BundleCompat.getParcelable(
                    result,
                    SearchFragment::class.java.name,
                    Locator::class.java
                )?.let {
                    navigator.go(it)

                }
            }
        )



        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = super.onCreateView(inflater, container, savedInstanceState)

        if (savedInstanceState == null) {
            childFragmentManager.commitNow {
                add(
                    R.id.fragment_reader_container,
                    EpubNavigatorFragment::class.java,
                    Bundle(),
                    NAVIGATOR_FRAGMENT_TAG
                )
            }
        }
        navigator = childFragmentManager.findFragmentByTag(NAVIGATOR_FRAGMENT_TAG) as EpubNavigatorFragment




//        navigator.currentLocator.value

        return view
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStart() {
        super.onStart()
        binding.timelineBar.setOnTouchListener(this::forbidUserSeeking)
        binding.timelineBar.setOnSeekBarChangeListener(this)
        binding.playPause.setOnClickListener(this::onPlayPause)
        binding.skipForward.setOnClickListener(this::onSkipForward)
        binding.skipBackward.setOnClickListener(this::onSkipBackward)

        binding.transcribe.setOnClickListener(this::onReanchorTranscriptionLocator)
//        binding.transcribe.setOnClickListener(this::onExtractAndTranscribe)
        binding.loadAudioBook.setOnClickListener(this::onLoadAudioBook )
        binding.setSpeed.setOnClickListener(this::onSetSpeed )
        updateControlsLayoutBackground(false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        @Suppress("Unchecked_cast")
        (model.settings as UserPreferencesViewModel<EpubSettings, EpubPreferences>)
            .bind(navigator, viewLifecycleOwner)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Display page number labels if the book contains a `page-list` navigation document.
                (navigator as? DecorableNavigator)?.applyPageNumberDecorations()
            }
        }



        val loadingIcon = view.findViewById<ImageView>(R.id.imageView3)
        val drawable = loadingIcon.drawable
        if (drawable is AnimatedVectorDrawable) {
            drawable.start()
        }

        val menuHost: MenuHost = requireActivity()

        menuHost.addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuSearch = menu.findItem(R.id.search).apply {
                        isVisible = true
                        menuSearchView = actionView as SearchView
                    }

                    menuDebug = menu.findItem(R.id.debugButton).apply {
                        isVisible = true
//                        menuSearchView = actionView as SearchView
                    }

                    connectSearch()
                    if (!isSearchViewIconified) menuSearch.expandActionView()
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    when (menuItem.itemId) {
                        R.id.search -> {
                            return true
                        }
                        android.R.id.home -> {
                            menuSearch.collapseActionView()
                            return true
                        }
                        R.id.debugButton -> {
                            viewLifecycleOwner.lifecycleScope.launch {
                                getCurrentVisibleContentRange()
                                Timber.d("")
                            }

                            return true
                        }
                    }
                    return false
                }
            },
            viewLifecycleOwner
        )
    }

    private suspend fun testFunc() {


    }

    /**
     * Will display margin labels next to page numbers in an EPUB publication with a `page-list`
     * navigation document.
     *
     * See http://kb.daisy.org/publishing/docs/navigation/pagelist.html
     */
    private suspend fun DecorableNavigator.applyPageNumberDecorations() {
        val decorations = publication.pageList
            .mapIndexedNotNull { index, link ->
                val label = link.title ?: return@mapIndexedNotNull null
                val locator = publication.locatorFromLink(link) ?: return@mapIndexedNotNull null

                Decoration(
                    id = "page-$index",
                    locator = locator,
                    style = DecorationStylePageNumber(label = label)
                )
            }

        applyDecorations(decorations, "pageNumbers")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(IS_SEARCH_VIEW_ICONIFIED, isSearchViewIconified)
    }

    private fun connectSearch() {
        menuSearch.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {

            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                if (isSearchViewIconified) { // It is not a state restoration.
                    showSearchFragment()
                }

                isSearchViewIconified = false
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                isSearchViewIconified = true
                childFragmentManager.popBackStack()
                menuSearchView.clearFocus()

                return true
            }
        })

        menuSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {

            override fun onQueryTextSubmit(query: String): Boolean {
                model.search(query)
                menuSearchView.clearFocus()

                return false
            }

            override fun onQueryTextChange(s: String): Boolean {
                return false
            }
        })

        menuSearchView.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn).setOnClickListener {
            menuSearchView.requestFocus()
            model.cancelSearch()
            menuSearchView.setQuery("", false)

            (activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(
                this.view,
                0
            )
        }
    }

    private fun showSearchFragment() {
        childFragmentManager.commit {
            childFragmentManager.findFragmentByTag(SEARCH_FRAGMENT_TAG)?.let { remove(it) }
            add(
                R.id.fragment_reader_container,
                SearchFragment::class.java,
                Bundle(),
                SEARCH_FRAGMENT_TAG
            )
            hide(navigator)
            addToBackStack(SEARCH_FRAGMENT_TAG)
        }
    }

    companion object {
        private const val SEARCH_FRAGMENT_TAG = "search"
        private const val NAVIGATOR_FRAGMENT_TAG = "navigator"
        private const val IS_SEARCH_VIEW_ICONIFIED = "isSearchViewIconified"
    }

    private suspend fun openAudio(
        bookId: Long,
        publication: Publication,
        initialLocator: Locator?
    ): Try<AudioNavigator<ExoPlayerSettings, ExoPlayerPreferences>, OpeningError> {


        val preferencesManager = ExoPlayerPreferencesManagerFactory(application.readerRepository.preferencesDataStoreRepo)
            .createPreferenceManager(bookId)
        val initialPreferences = preferencesManager.preferences.value

        val navigatorFactory = AudioNavigatorFactory(
            publication,
            ExoPlayerEngineProvider(application)
        ) ?: return Try.failure(
            OpeningError.CannotRender(
                DebugError("Cannot create audio navigator factory.")
            )
        )





        val navigator = navigatorFactory.createNavigator(
            initialLocator,
            initialPreferences
        ).getOrElse {
            return Try.failure(
                when (it) {
                    is AudioNavigatorFactory.Error.EngineInitialization ->
                        OpeningError.AudioEngineInitialization(it)
                    is AudioNavigatorFactory.Error.UnsupportedPublication ->
                        OpeningError.CannotRender(it)
                }
            )
        }

        val initData = MediaReaderInitData(
            bookId,
            publication,
            navigator,
            preferencesManager,
            navigatorFactory
        )


        val preferencesModel = UserPreferencesViewModel(model.viewModelScope,initData)
//        val nav = (navigator as? Configurable<ExoPlayerSettings, ExoPlayerPreferences>)
////        model.s
//        preferencesModel?.bind(nav,viewLifecycleOwner)

        @Suppress("Unchecked_cast")
        (navigator as? Configurable<ExoPlayerSettings, ExoPlayerPreferences>)
            ?.let { navigator ->
                @Suppress("Unchecked_cast")
                (preferencesModel as UserPreferencesViewModel<ExoPlayerSettings, ExoPlayerPreferences>)
                    .bind(navigator, viewLifecycleOwner)
            }

//        if (preferencesModel != null) {
        audioDialog = AudioPreferencesBottomSheetDialogFragment()
        audioDialog.setAudioPreferencesModelFunc(preferencesModel!!)
//            Timber.d(AudioPreferencesBottomSheetDialogFragment().audioPreferencesModel.toString())
//        }

        return Try.success(navigator)
    }

    private fun onPlaybackChanged(
        playback: TimeBasedMediaNavigator.Playback
    ) {
        Timber.v("onPlaybackChanged $playback")
        latestPlaybackStatus = playback
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

//        if (playback.playWhenReady) {
//            updateControlsLayoutBackground(true)
//        } else {
//            updateControlsLayoutBackground(false)
//        }
        if (seekingItem == null) {
            updateTimeline(playback)
        }





        val playbackTranscribeSegment = roundTimestampToNearest15Seconds(binding.timelinePosition.text.toString())
//        binding.transcriptionResult.text = if (transcriptionMap.containsKey(playbackTranscribeSegment)) {
//            transcriptionMap[playbackTranscribeSegment]
//        } else {
//            binding.transcriptionResult.text
//        }
        val currentTime = System.currentTimeMillis()

        if(currentTime - lastHighlightUpdate >= 500) {
            lastHighlightUpdate = currentTime
            if (locatorMap.containsKey(playbackTranscribeSegment)) {
                model.viewModelScope.launch {
                    if(locatorMap.containsKey(getNext15SecondInterval(playbackTranscribeSegment, -2))) {
                        highlightText(
                            locatorMap[playbackTranscribeSegment],
                            locatorMap[getNext15SecondInterval(playbackTranscribeSegment, -2)],
                            locatorMap[getNext15SecondInterval(playbackTranscribeSegment, -1)]
                        )
                    }else if(locatorMap.containsKey(getNext15SecondInterval(playbackTranscribeSegment, -1))) {
                        highlightText(
                            locatorMap[playbackTranscribeSegment],
                            locatorMap[getNext15SecondInterval(playbackTranscribeSegment, -1)],
                            locatorMap[getNext15SecondInterval(playbackTranscribeSegment, -1)]
                        )
                    }else {
                        highlightText(locatorMap[playbackTranscribeSegment])
                    }
                }
            } else {
                model.viewModelScope.launch {
                    if (!locators.isEmpty())
                        loadThenPlayStart(listOf(playbackTranscribeSegment
                            ,getNext15SecondInterval(playbackTranscribeSegment, 1)
                            ,getNext15SecondInterval(playbackTranscribeSegment, 2)))
                }
            }
        }



        if (currentTime - lastSaveTime >= 5000) { // 5 seconds
            lastSaveTime = currentTime
            model.viewModelScope.launch {
                application.bookRepository.saveAudiobookProgression(
                    audioNavigator.currentLocator.value,
                    epubBookId
                )

                getNextTranscriptionSegment()


//                var currentSegment = getPlaybackTranscribeSegment()
//                var iterations = 1
//
//                while (iterations <= 12) {
//                    if (transcriptionMap.containsKey(currentSegment)) {
//                        // The map contains the key
//                        val transcription = transcriptionMap[currentSegment]
//                        Timber.d("Transcription for $currentSegment: $transcription")
//
//                    } else {
//                        // The map does not contain the key
//                        Timber.d("No transcription found for $currentSegment")
//                        if (playback.playWhenReady == true) {
//                            transcribeAudio(currentSegment)
//                        }
//                        break
//                    }
//                    currentSegment = getNext15SecondInterval(playbackTranscribeSegment, iterations)
//                    iterations++
//                }


            }
        }

//        model.viewModelScope.launch {
//            val currentAudioPosition = audioNavigator.currentLocator.value
//            val currentEPubPosition = navigator.currentLocator.value
//
//            Timber.d("Transcription for currentAudioPosition: " + currentAudioPosition.toString())
//            Timber.d("Transcription for currentEPubPosition: " + currentEPubPosition.toString())
//        }
    }

    private suspend fun getNextTranscriptionSegment() {
        var currentSegment = getPlaybackTranscribeSegment()
        var iterations = 1

        while (iterations <= 100) {
            if (transcriptionMap.containsKey(currentSegment)) {
                // The map contains the key
                val transcription = transcriptionMap[currentSegment]
                Timber.d("Transcription for $currentSegment: $transcription")

            } else {
                // The map does not contain the key
                Timber.d("No transcription found for $currentSegment")
                if (audioNavigator.playback.value.playWhenReady == true) {
                    transcribeAudio(currentSegment)
                }
                break
            }
            currentSegment = getNext15SecondInterval(getPlaybackTranscribeSegment(), iterations)
            iterations++
        }
    }

    private fun getPlaybackTranscribeSegment(): String {
        return roundTimestampToNearest15Seconds(binding.timelinePosition.text.toString())
    }

    private fun updateTimeline(
        playback: TimeBasedMediaNavigator.Playback
    ) {
        val currentItem = audioNavigator.readingOrder.items[playback.index]
        binding.timelineBar.max = currentItem.duration?.inWholeSeconds?.toInt() ?: 0
        binding.timelineDuration.text = currentItem.duration?.formatElapsedTime()
        binding.timelineBar.progress = playback.offset.inWholeSeconds.toInt()
        binding.timelinePosition.text = playback.offset.formatElapsedTime()
        binding.timelineBar.secondaryProgress = playback.buffered?.inWholeSeconds?.toInt() ?: 0
    }

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

    private fun Duration.formatElapsedTime(): String =
        DateUtils.formatElapsedTime(toLong(DurationUnit.SECONDS))

    private fun onPlayPause(@Suppress("UNUSED_PARAMETER") view: View) {
        return when (audioNavigator.playback.value.state) {
            is MediaNavigator.State.Ready, is MediaNavigator.State.Buffering -> {
                model.viewModelScope.launch {
                    if (audioNavigator.playback.value.playWhenReady) {
                        audioNavigator.pause()
                        updateControlsLayoutBackground(false)
                    } else {
                        if (transcriptionMap.isEmpty()){
                            loadThenPlayStart(generateTranscriptionRanges(roundTimestampToNearest15Seconds(binding.timelinePosition.text.toString())))
                        }
                        audioNavigator.play()
                        updateControlsLayoutBackground(true)
                    }
                }
                Unit
            }
            is MediaNavigator.State.Ended -> {
                model.viewModelScope.launch {
                    audioNavigator.skipTo(0, Duration.ZERO)
                    audioNavigator.play()
                }
                Unit
            }
            is MediaNavigator.State.Failure -> {
                // Do nothing.
            }
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser) {
            binding.timelinePosition.text = progress.seconds.formatElapsedTime()
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        Timber.d("onStartTrackingTouch")
        seekingItem = audioNavigator.playback.value.index
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        Timber.d("onStopTrackingTouch")
        audioNavigator.skipTo(checkNotNull(seekingItem), seekBar.progress.seconds)
        seekingItem = null
    }

    private fun onSkipForward(@Suppress("UNUSED_PARAMETER") view: View) {
        model.viewModelScope.launch {
            audioNavigator.skipForward()
        }
    }

    private fun onSkipBackward(@Suppress("UNUSED_PARAMETER") view: View) {
        model.viewModelScope.launch {
            audioNavigator.skipBackward()
        }
    }

//    override fun go(locator: Locator, animated: Boolean) {
//        model.viewModelScope.launch {
//            audioNavigator.go(locator)
//            audioNavigator.play()
//        }
//    }

    @Suppress("UNUSED_PARAMETER")
    private fun forbidUserSeeking(view: View, event: MotionEvent): Boolean {
        // Check if audioNavigator is initialized
        if (!::audioNavigator.isInitialized) {
            return false
        }
        return audioNavigator.playback.value.state is MediaNavigator.State.Ended
    }



    private fun onLoadAudioBook(@Suppress("UNUSED_PARAMETER") view: View) {
        model.viewModelScope.launch {
//            audioNavigator.skipBackward()
            Timber.d("Load Audio Book")
            sharedStoragePickerLauncher.launch(arrayOf("*/*"))

//            try {
//                loadAudiobookNavigator(epubBookId)
//            } catch (e: Exception) {
//                Timber.e(e, "Failed to load audiobook navigator")
//                // Handle the exception, e.g., show an error message to the user
//            }
        }
    }

    private fun onSetSpeed(@Suppress("UNUSED_PARAMETER") view: View) {
        model.viewModelScope.launch {

//        @Suppress("Unchecked_cast")
//        (model.settings as UserPreferencesViewModel<ExoPlayerSettings, ExoPlayerPreferences>)
//            .bind(audioNavigator, viewLifecycleOwner)

//        @Suppress("Unchecked_cast")
//        (audioNavigator as? Configurable<ExoPlayerSettings, ExoPlayerPreferences>)
//            ?.let { navigator ->
//                @Suppress("Unchecked_cast")
//                (model.settings as UserPreferencesViewModel<ExoPlayerSettings, ExoPlayerPreferences>)
//                    .bind(navigator, viewLifecycleOwner)
//            }


//            audioNavigator.skipForward()

//            MainPreferencesBottomSheetDialogFragment().con
            audioDialog.show(childFragmentManager, "Settings")
//            MainPreferencesBottomSheetDialogFragment()
//                .show(childFragmentManager, "Settings")
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

    private suspend fun transcribeAudio(transcriptionTimestamp: String) {
        try {
            if (mWhisper?.isInProgress() == false) {
                val sdcardDataFolder = withContext(Dispatchers.IO) {
                    context?.getExternalFilesDir(null)
                }

                if (sdcardDataFolder != null) {
                    Timber.d(audioNavigator.readingOrder.items[0].toString())

                    val inputFilePath = withContext(Dispatchers.IO) {
                        getFilePathFromContentUri(
                            requireContext(),
                            Uri.parse(audioPublication.get(audioPublication.readingOrder[0].url())!!.sourceUrl.toString())
                        )
                    }.toString()

                    val inputFileType = inputFilePath.substringAfterLast('.', "")
                    val outputFilePath = sdcardDataFolder.absolutePath + "/extracted_segment." + inputFileType
                    val timestamp = withContext(Dispatchers.Main) {
                        binding.timelinePosition.text.toString()
                    }
                    val duration = 15

                    currentTranscribeSegment = transcriptionTimestamp
                    transcriptionRange = generateTranscriptionRanges(currentTranscribeSegment)

                    val startTimeInSeconds = convertTimestampToSeconds(currentTranscribeSegment)
                    val startTimeFormatted = String.format(
                        "%02d:%02d:%02d",
                        startTimeInSeconds / 3600,
                        (startTimeInSeconds % 3600) / 60,
                        startTimeInSeconds % 60
                    )

                    Timber.d(timestamp)

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
        } catch (e: Exception) {
            Timber.e(e, "Error occurred while transcribing audio")
        }
    }

    private fun extractAudioSegment(
        inputFilePath: String,
        outputFilePath: String,
        startTime: String,
        duration: Int
    ) {
//        withContext(Dispatchers.IO) {
            val command = "-y -ss $startTime -analyzeduration 1000000 -probesize 500000 -i \"$inputFilePath\" -t $duration -vn -c copy $outputFilePath"
            Timber.d(command)
            FFmpegKit.execute(command)

            if (!outputFilePath.endsWith(".wav")){
                val convertCommand = "-y -i $outputFilePath $outputFilePath.wav"
                FFmpegKit.execute(convertCommand)
            }
//        }
    }

    private fun convertTimestampToSeconds(timestamp: String): Int {
//        var parts = timestamp.split(":").map { it.toInt() }
        var parts = timestamp.split(":").map { it.toInt() }.toMutableList()
        if (parts.size != 3) {
            if (parts.size == 2) {
//            parts = "00:00:00".split(":").map { it.toInt() }
                parts.add(0, 0)
            } else if (parts.size == 1) {
                parts.add(0, 0)
                parts.add(0, 0)
            } else {
//            parts = "00:00:00".split(":").map { it.toInt() }.toMutableList()
                parts = mutableListOf(0, 0, 0)
            }
        }
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    }

    private fun roundTimestampToNearest15Seconds(timestamp: String): String {
//        var parts = timestamp.split(":").map { it.toInt() }
//        if (parts.size != 3)
//            parts = "00:00:00".split(":").map { it.toInt() }

        var parts = timestamp.split(":").map { it.toInt() }.toMutableList()
        if (parts.size != 3) {
            if (parts.size == 2) {
//            parts = "00:00:00".split(":").map { it.toInt() }
                parts.add(0, 0)
            } else if (parts.size == 1) {
                parts.add(0, 0)
                parts.add(0, 0)
            } else {
//            parts = "00:00:00".split(":").map { it.toInt() }.toMutableList()
                parts = mutableListOf(0, 0, 0)
            }
        }

        val totalSeconds = parts[0] * 3600 + parts[1] * 60 + parts[2]
        val roundedSeconds = (totalSeconds / 15) * 15

        val hours = roundedSeconds / 3600
        val minutes = (roundedSeconds % 3600) / 60
        val seconds = roundedSeconds % 60

        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun generateTranscriptionRanges(currentTranscribeSegment: String): List<String> {
        val baseTimeInSeconds = convertTimestampToSeconds(currentTranscribeSegment)
        val ranges = mutableListOf<String>()

//        for (i in -15..45 step 15) {
        for (i in 0..45 step 15) {
            val newTimeInSeconds = baseTimeInSeconds + i
            val hours = newTimeInSeconds / 3600
            val minutes = (newTimeInSeconds % 3600) / 60
            val seconds = newTimeInSeconds % 60
            ranges.add(String.format("%02d:%02d:%02d", hours, minutes, seconds))
        }

        Timber.d("Transcription Range: " + ranges.toString())

        return ranges
    }

    private fun getNext15SecondInterval(currentTranscribeSegment: String, intervals: Int): String {
        val baseTimeInSeconds = convertTimestampToSeconds(currentTranscribeSegment)
        val newTimeInSeconds = baseTimeInSeconds + (intervals *15)
        val hours = newTimeInSeconds / 3600
        val minutes = (newTimeInSeconds % 3600) / 60
        val seconds = newTimeInSeconds % 60

//        Timber.d("Transcription for: "+ String.format("%02d:%02d:%02d", hours, minutes, seconds))

        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private suspend fun loadAudiobookNavigator(bookId: Long){
//        Timber.d("BookIdTest: " +bookIdTest.toString())
//        val bookId = 1L

        val allaudiobooks = application.bookRepository.audiobooks()
        val allbooks = application.bookRepository.books()
        allaudiobooks.onEach {
                Timber.d(it.toString())
            }

//        Timber.d("BookIdTest Total Number of Audiobooks: " +allbooks.count().toString())
//        Timber.d("BookIdTest Total Number of Audiobooks: " +allaudiobooks.count().toString())
//        Timber.d("BookIdTest Total Number of Audiobooks: " +allaudiobooks.count().toString())

        val book = checkNotNull(application.bookRepository.getAudiobook(bookId))
        val asset = readium.assetRetriever.retrieve(
            book.url,
            book.mediaType
        ).getOrElse {
            return@getOrElse Try.failure(
                OpeningError.PublicationError(
                    PublicationError(it)
                )
            )
        }

        val audioPublicationLoader = readium.publicationOpener.open(
            asset as Asset,
            allowUserInteraction = true
        ).getOrElse {
            return@getOrElse Try.failure(
                OpeningError.PublicationError(
                    PublicationError(it)
                )
            )
        }

        val initialLocator = book.progression
            ?.let { Locator.fromJSON(JSONObject(it)) }

//                                    val audioNavigator

        val readerInitData = when {
            (audioPublicationLoader as Publication).conformsTo(Publication.Profile.AUDIOBOOK) ->
                openAudio(bookId, audioPublicationLoader, initialLocator)
            else ->
                Try.failure(
                    OpeningError.CannotRender(
                        DebugError("No navigator supports this publication.")
                    )
                )
        }

        audioNavigator = readerInitData.getOrNull()!!
        audioPublication = audioPublicationLoader

        totalTimeOfAudio = audioNavigator.readingOrder.items[0].duration?.formatElapsedTime().toString()


//                                    Timber.d(readerInitData.toString())
//        audioNavigator.play()
        audioNavigator.playback
            .onEach { onPlaybackChanged(it) }
            .launchIn(viewLifecycleOwner.lifecycleScope)

//        @Suppress("Unchecked_cast")
//        (model.settings as UserPreferencesViewModel<EpubSettings, EpubPreferences>)
//            .bind(audioNavigator, viewLifecycleOwner)


//                @Suppress("Unchecked_cast")
//                (model.settings as UserPreferencesViewModel<ExoPlayerSettings, ExoPlayerPreferences>)
//                    .bind(audioNavigator, viewLifecycleOwner)


        binding.loadAudioBook.visibility = View.GONE


    }

    val channel: Channel<Bookshelf.Event> =
        Channel(Channel.UNLIMITED)

    private suspend fun addBookFeedback(
        retrieverResult: Try<PublicationRetriever.Result, ImportError>
    ) {
        retrieverResult
            .map { addBook(it.publication.toUrl(), it.format, it.coverUrl) }
            .onSuccess { channel.send(Bookshelf.Event.ImportPublicationSuccess) }
            .onFailure { channel.send(Bookshelf.Event.ImportPublicationError(it)) }
    }

    private suspend fun addBookFeedback(
        url: AbsoluteUrl,
        format: Format? = null,
        coverUrl: AbsoluteUrl? = null
    ) {
        addBook(url, format, coverUrl)
            .onSuccess { channel.send(Bookshelf.Event.ImportPublicationSuccess) }
            .onFailure { channel.send(Bookshelf.Event.ImportPublicationError(it)) }
    }

    private suspend fun addBook(
        url: AbsoluteUrl,
        format: Format? = null,
        coverUrl: AbsoluteUrl? = null
    ): Try<Unit, ImportError> {
        val asset =
            if (format == null) {
                readium.assetRetriever.retrieve(url)
            } else {
                readium.assetRetriever.retrieve(url, format)
            }.getOrElse {
                return Try.failure(
                    ImportError.Publication(PublicationError(it))
                )
            }

        readium.publicationOpener.open(
            asset,
            allowUserInteraction = false
        ).onSuccess { audioPublication ->
//            val coverFile =
//                coverStorage.storeCover(publication, coverUrl)
//                    .getOrElse {
//                        return Try.failure(
//                            ImportError.FileSystem(
//                                FileSystemError.IO(it)
//                            )
//                        )
//                    }

            val id = application.bookRepository.insertAudioBook(
                url,
                asset.format.mediaType,
                audioPublication,
                epubBookId
            )
            if (id == -1L) {
//                coverFile.delete()
                return Try.failure(
                    ImportError.Database(
                        DebugError("Could not insert book into database.")
                    )
                )
            }
        }
            .onFailure {
                Timber.e("Cannot open publication: $it.")
                return Try.failure(
                    ImportError.Publication(PublicationError(it))
                )
            }

        try {
            loadAudiobookNavigator(epubBookId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load audiobook navigator")
            // Handle the exception, e.g., show an error message to the user
        }
        return Try.success(Unit)
    }

    fun addPublicationFromStorage(
        url: AbsoluteUrl
    ) {
        coroutineScope.launch {
            addBookFeedback(url)
        }
    }

    suspend fun getCurrentVisibleContentRange(full: Boolean = false) {
//        model.viewModelScope.launch {
        // Display page number labels if the book contains a `page-list` navigation document.
//            (navigator as? DecorableNavigator)?.applyPageNumberDecorations()
//            navigator.applyDecorations(
//                listOfNotNull(null),
//                "tts"
//            )


//        val start = if (locators.isNotEmpty()) {
//            locators[0]
//
//        } else {
//            (navigator as? VisualNavigator)?.firstVisibleElementLocator()
//        }

        val currentEpubProgress = navigator.currentLocator.value.locations.totalProgression
        val currentAudioProgression = audioNavigator.currentLocator.value.locations.totalProgression



        if (!::globalIterator.isInitialized){
            globalIterator = publication.content((navigator as? VisualNavigator)?.firstVisibleElementLocator())!!.iterator()
            if (currentEpubProgress != null && currentAudioProgression !=null) {
                if (currentEpubProgress - currentAudioProgression >= 0.2) {
                    globalIterator = publication.content()!!.iterator()
                }
            }
        }

//        val content = publication.content(start)
//        val firstVisibleIndex = publication.content()?.elements()?.indexOfFirst { it.locator == start } as Int
//        val content = publication.content(publication.content()?.elements()[firstVisibleIndex - 2]?.locator)

        val progressionRange = if (full) { -1.00..1.00 }
        else { -0.03..0.03 }

//        val progressionRange = -0.03..0.03


        val tokenizer = DefaultTextContentTokenizer(
            unit = TextUnit.Word,
            language = Language(Locale.ENGLISH)
        )

//        var i = 0

//        val iterator = content?.iterator()
//        locators.clear()
//        var combinedRange: IntRange? = null


//        while (iterator!!.hasNext() && i <= 15) {
        while (globalIterator.hasNext()) {
            val element = globalIterator.next()
//            Timber.d("Transcription for unmerged: " + tokenizedContent.toString())
//            Timber.d("Transcription for merged: " + mergeRanges(tokenizedContent).toString())



            Timber.d("Transcription for currentEpubProgress: " + currentEpubProgress)
            Timber.d("Transcription for currentAudioProgression: " + currentAudioProgression)

//            if(currentEpubProgress!! - currentAudioProgression!! !in -0.03..0.03) {
            val progression = element.locator.locations.totalProgression

            if (progression != null && currentAudioProgression != null) {
                Timber.d("Transcription for: " + progression)
                // Calculate the difference between locator progression and currentAudioProgression
                val difference = progression - currentAudioProgression

                // Check if the difference is within 5% of currentAudioProgression
                if (difference in progressionRange) {
                    // Execute the logic when the locator is within 5%
                    // Break out of the loop
                    break
                }
            }
        }

        while (globalIterator.hasNext()) {
                val element = globalIterator.next()
                val string = element.locator.text.highlight.toString()
                val tokenizedContent = mergeRanges(tokenizer.tokenize(string),20)

            for (range in tokenizedContent) {
                addLocator(element.locator, string, range)
            }

            val progression = element.locator.locations.totalProgression

            if (progression != null && currentAudioProgression != null) {
                Timber.d("Transcription for: " + progression)
                // Calculate the difference between locator progression and currentAudioProgression
                val difference = progression - currentAudioProgression

                // Check if the difference is within 5% of currentAudioProgression
                if (difference !in progressionRange) {
                    // Execute the logic when the locator is within 5%
                    // Break out of the loop
                    break
                }
            }


//            }
        }

        Timber.d("Transcription for: " + locators.toString())

//        }


//        binding.audioOverlayText
    }

    private fun addLocator(element: Locator, string: String, range: IntRange) {
        val contextSnippetLength = 50

        val after = string.substring(
            range.last,
            (range.last + contextSnippetLength).coerceAtMost(string.length)
        )
        val before = string.substring(
            (range.first - contextSnippetLength).coerceAtLeast(0),
            range.first
        )
        val subLocator = Locator.Text(
            after = after.takeIf { it.isNotEmpty() },
            before = before.takeIf { it.isNotEmpty() },
            highlight = string.substring(range)
        )

        Timber.d("Transcription for addLocator: " + element)
        locators.add(element.copy(text = subLocator))
    }

    fun mergeRanges(ranges: List<IntRange>, minSize: Int = 50): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()

        val sortedRanges = ranges.sortedBy { it.first }
        val mergedRanges = mutableListOf<IntRange>()
        var currentRange = sortedRanges.first()

        for (range in sortedRanges.drop(1)) {
            if (range.first <= currentRange.last + 1) {
                currentRange = currentRange.first..maxOf(currentRange.last, range.last)
            } else {
                mergedRanges.add(currentRange)
                currentRange = range
            }
        }
        mergedRanges.add(currentRange)

        // Ensure each resulting range has a minimum size of 50
        val finalRanges = mutableListOf<IntRange>()
        var combinedRange: IntRange? = null

        for (range in mergedRanges) {
            if (range.last - range.first + 1 >= minSize) {
                if (combinedRange != null) {
                    finalRanges.add(combinedRange.first..range.last)
                    combinedRange = null
                } else {
                    finalRanges.add(range)
                }
            } else {
                if (combinedRange == null) {
                    combinedRange = range
                } else {
                    combinedRange = combinedRange.first..range.last
                    if (combinedRange.last - combinedRange.first + 1 >= minSize) {
                        finalRanges.add(combinedRange)
                        combinedRange = null
                    }
                }
            }
        }

        if (combinedRange != null) {
            if (finalRanges.isNotEmpty() && combinedRange.last - combinedRange.first + 1 < minSize) {
                val lastRange = finalRanges.removeAt(finalRanges.size - 1)
                finalRanges.add(lastRange.first..combinedRange.last)
            } else {
                finalRanges.add(combinedRange)
            }
        }

        return finalRanges
    }

    fun onReanchorTranscriptionLocator(@Suppress("UNUSED_PARAMETER") view: View) {
//        locators.clear()
//        audioNavigator.play()
        audioNavigator.pause()

//        val currentEpubProgress = navigator.currentLocator.value.locations.totalProgression
//        val currentAudioProgression = audioNavigator.currentLocator.value.locations.totalProgression
//
//
//
//        Timber.d("Transcription for currentEpubProgress: " + currentEpubProgress)
//        Timber.d("Transcription for currentAudioProgression: " + currentAudioProgression)
//
//        if(currentEpubProgress!! - currentAudioProgression!! !in -0.03..0.03) {
//
//
//            val iterator = publication.content()?.iterator()
//
//
//            model.viewModelScope.launch {
//                while (iterator!!.hasNext()) {
//                    val locator = iterator.next().locator
//                    val progression = locator.locations.totalProgression
//                    if (progression != null) {
////                Timber.d("Transcription for: " + locator.locations.progression)
//                        // Calculate the difference between locator progression and currentAudioProgression
//                        val difference = progression - currentAudioProgression
//
//                        // Check if the difference is within 5% of currentAudioProgression
//                        if (difference in -0.01..0.01) {
//                            // Execute the logic when the locator is within 5%
//                            Timber.d("Transcription for Locator within 5% of currentAudioProgression: $locator")
//                            navigator.go(locator)
//                            // Break out of the loop
//                            break
//                        }
//                    }
//                }
//            }
//        }

        model.viewModelScope.launch {
            locators.clear()
            transcriptionMap.clear()
            locatorMap.clear()
            binding.loadingOverlay.visibility = View.VISIBLE
            globalIterator = publication.content((navigator as? VisualNavigator)?.firstVisibleElementLocator())!!.iterator()
            getCurrentVisibleContentRange(true)

            loadThenPlayStart(generateTranscriptionRanges(roundTimestampToNearest15Seconds(binding.timelinePosition.text.toString())))
        }
    }

    suspend fun getFilePathFromContentUri(context: Context, contentUri: Uri): String? {
        return withContext(Dispatchers.IO) {
            var filePath: String? = null
            val fileName = getFileName(context, contentUri)
            if (fileName != null) {
                val file = File(context.filesDir, fileName)
                filePath = file.absolutePath
                saveFileFromUri(context, contentUri, file)
            }
            filePath
        }
    }

    @SuppressLint("Range")
    private fun getFileName(context: Context, uri: Uri): String? {
        var fileName: String? = null
        val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                fileName = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))

            }
        }
        return fileName
    }

    private fun saveFileFromUri(context: Context, uri: Uri, file: File) {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        val outputStream: FileOutputStream = FileOutputStream(file)
        inputStream?.use { input ->
            outputStream.use { output ->
                val buffer = ByteArray(1024)
                var length: Int
                while (input.read(buffer).also { length = it } > 0) {
                    output.write(buffer, 0, length)
                }
                output.flush()
            }
        }
    }

    private fun updateControlsLayoutBackground(playWhenReady: Boolean) {
        val currentColor = (binding.controlsLayout.background as? ColorDrawable)?.color ?: Color.WHITE
        val startAlpha = Color.alpha(currentColor)
        val endAlpha = if (playWhenReady) 0 else 210

        val animator = ValueAnimator.ofInt(startAlpha, endAlpha)
        animator.addUpdateListener { animation ->
            val animatedValue = animation.animatedValue as Int
            val color = ColorUtils.setAlphaComponent(Color.WHITE, animatedValue)
            binding.controlsLayout.setBackgroundColor(color)
        }
        animator.duration = 300 // Duration in milliseconds
        animator.repeatCount = 0 // Ensure the animation does not repeat

//        if (playWhenReady) {
//            animator.startDelay = 3500 // Delay in milliseconds before starting the animation
//        }

        animator.start()
    }

    suspend fun syncTranscriptionWithLocator(transcription: String, timestamp: String): List<Locator>{
        val startTimeGetContent = System.currentTimeMillis()
//        (navigator as? DecorableNavigator)?.applyPageNumberDecorations()
//            navigator.applyDecorations(
//                listOfNotNull(null),
//                "tts"
//            )

        if (locators.isEmpty())
            getCurrentVisibleContentRange()
        var matchedLocators = mutableListOf<Locator>()
        val matchedDebugTest = mutableListOf<String>()
        val matchedIndexs = mutableListOf<Int>()

        val prevSegment = getNext15SecondInterval(timestamp, -1)

//        for (locator in locators) {
//            val text = locator.text.highlight.toString()
//            val similarity = jaroWinkler.similarity(transcription, text)
//            if (similarity > 0.5) { // Adjust the threshold as needed
//                matchedLocators.add(locator)
//                matchedDebugTest.add(locator.text.highlight.toString())
//            }
//        }

//        val transcriptionSegments = transcription.split(" ") // Split transcription into words
//
//        for (locator in locators) {
//            val text = locator.text.highlight.toString()
//            for (segment in transcriptionSegments) {
//                val similarity = jaroWinkler.similarity(segment, text)
//                if (similarity > 0.5) { // Adjust the threshold as needed
//                    matchedLocators.add(locator)
//                    matchedDebugTest.add(locator.text.highlight.toString())
//                    Timber.d("Transcription for: " + locator.text.highlight.toString()+ "|" + similarity + "|" + segment)
//                    break // Stop comparing once a match is found
//                }
//            }
//        }

//        val transcriptionSegments = transcription.split(" ") // Split transcription into words
//        val sentences = locators.map { it.text.highlight.toString() }
//
//        for (each in transcriptionSegments) {
//            val closestMatches = sentences.map { it to jaroWinkler.similarity(each, it) }
//                .filter { it.second >= 0.8 }
//                .sortedByDescending { it.second }
//                .take(1)
//                .map { it.first }
//
//            val closestMatchIndex = closestMatches.firstOrNull()?.let { sentences.indexOf(it) }
//
////            voiceMap.add(
////                mapOf(
////                    "text" to each,
////                    "closest_match" to closestMatches.firstOrNull(),
////                    "closest_match_index" to closestMatchIndex,
////                    "first_section" to 0,
////                    "last_section" to 0
////                )
////            )
//
//            if (closestMatchIndex != null) {
//                matchedLocators.add(locators[closestMatchIndex])
//                matchedDebugTest.add(locators[closestMatchIndex].text.highlight.toString())
//                Timber.d("Transcription for: " + locators[closestMatchIndex].text.highlight.toString())
//            }
//        }

        Timber.d("Transcription for locators.count():" + locators.count())

        if (locators.count() < 50)
            getCurrentVisibleContentRange(true)

        var locatorSlice = if (locatorMap.isEmpty()){
            locators
        } else {
            locators.slice(0..50)
        }

//        val prevLocators = locatorMap[prevSegment]?.toSet() ?: emptySet()

        for (locator in locatorSlice) {
//            if (locator in prevLocators) continue

            val text = locator.text.highlight.toString()
            if (isSentenceInParagraph(text, transcription)) {
                matchedLocators.add(locator)
                matchedDebugTest.add(locator.text.highlight.toString())
                matchedIndexs.add(locators.indexOf(locator))
//                Timber.d("Transcription for: " + locator.text.highlight.toString())
            }
        }

        val indexRange = findLongestRun(matchedIndexs,5)
        if(indexRange.isNotEmpty()) {
            matchedLocators =
                locators.slice(indexRange.first()..indexRange.last()) as MutableList<Locator>

            // Remove locators from matchedLocators that are in locatorMap[prevSegment]
            val prevLocators = locatorMap[prevSegment]?.toSet() ?: emptySet()
            matchedLocators = matchedLocators.filter { it !in prevLocators }.toMutableList()


//        if (!matchedLocators.isEmpty()) {
//
//                val random = Random.Default
//                val decorations: List<Decoration> = matchedLocators.map { locator ->
//                    Decoration(
//                        id = "tts",
//                        locator = locator,
//                        style = Decoration.Style.Highlight(
//                            tint = Color.rgb(
//                                random.nextInt(256),
//                                random.nextInt(256),
//                                random.nextInt(256)
//                            )
//                        )
//                    )
//                }
//
//                navigator.applyDecorations(
//                    decorations,
//                    "tts"
//                )
//            }

            if (locatorMap.containsKey(prevSegment)) {
                locatorMap[prevSegment] = locatorMap[prevSegment]!!.toMutableList().apply {
                    addAll(locators.subList(0, indexRange.first()))
                }
            }

            locators.subList(0, indexRange.first()).clear()

        } else {
            matchedLocators.clear()
        }
//        Timber.d("Transcription for: "+transcription)
////        Timber.d("Transcription for: "+matchedDebugTest.toString())
////        Timber.d("Transcription for: "+matchedIndexs.toString())
//        Timber.d("Transcription for: "+matchedLocators.map { it.text.highlight.toString() }.toString())
//        Timber.d("Transcription for: "+indexRange.toString())
        Timber.d("Transcription for time taken to transcribe: "+ (System.currentTimeMillis() - startTimeGetContent))
        Timber.d("")




        return matchedLocators
    }

    private fun isSentenceInParagraph(sentence: String, paragraph: String, threshold: Double = 0.75): Boolean {
        val jaroWinkler = JaroWinkler()
        val cleanedParagraph = removePunctuation(paragraph).lowercase()
        val cleanedSentence = removePunctuation(sentence).lowercase()
        val words = cleanedParagraph.split(" ")
        val sentenceWords = cleanedSentence.split(" ")

        for (i in 0..(words.size - sentenceWords.size)) {
            val subParagraph = words.subList(i, i + sentenceWords.size).joinToString(" ")
            val similarity = jaroWinkler.similarity(cleanedSentence, subParagraph)
            if (similarity >= threshold) {
                Timber.d("$cleanedSentence | $similarity")
                return true
            }
        }
        return false
    }

    private fun removePunctuation(text: String): String {
        return text.replace(Regex("[^\\w\\s]"), "")
    }



//    fun findLongestRun(indices: List<Int>, marginOfError: Int = 1): List<Int> {
//        if (indices.isEmpty()) return emptyList()
//
//        val sortedIndices = indices.sorted()
//        val filledIndices = mutableListOf<Int>()
//
//        // Fill in the gaps within the margin of error
//        for (i in sortedIndices.indices) {
//            filledIndices.add(sortedIndices[i])
//            if (i < sortedIndices.size - 1) {
//                val gap = sortedIndices[i + 1] - sortedIndices[i]
//                if (gap > 1 && gap <= marginOfError + 1) {
//                    for (j in 1 until gap) {
//                        filledIndices.add(sortedIndices[i] + j)
//                    }
//                }
//            }
//        }
//
//        // Find the longest run of consecutive indices
//        var longestRun = mutableListOf<Int>()
//        var currentRun = mutableListOf<Int>()
//
//        for (i in filledIndices.indices) {
//            if (currentRun.isEmpty() || filledIndices[i] - currentRun.last() == 1) {
//                currentRun.add(filledIndices[i])
//            } else {
//                if (currentRun.size > longestRun.size) {
//                    longestRun = currentRun
//                }
//                currentRun = mutableListOf(filledIndices[i])
//            }
//        }
//
//        if (currentRun.size > longestRun.size) {
//            longestRun = currentRun
//        }
//
//        return longestRun
//    }

    fun findLongestRun(indices: List<Int>, marginOfError: Int = 1): List<Int> {
        if (indices.isEmpty()) return emptyList()

        val sortedIndices = indices.sorted()
        var longestRun = mutableListOf<Int>()
        var currentRun = mutableListOf<Int>()

        for (i in sortedIndices.indices) {
            if (currentRun.isEmpty() || sortedIndices[i] - currentRun.last() <= marginOfError) {
                currentRun.add(sortedIndices[i])
            } else {
                if (currentRun.size > longestRun.size) {
                    longestRun = currentRun
                }
                currentRun = mutableListOf(sortedIndices[i])
            }
        }

        if (currentRun.size > longestRun.size) {
            longestRun = currentRun
        }

        // Fill in any gaps within the margin of error
        val filledRun = mutableListOf<Int>()
        for (i in longestRun.indices) {
            filledRun.add(longestRun[i])
            if (i < longestRun.size - 1) {
                val gap = longestRun[i + 1] - longestRun[i]
                if (gap > 1 && gap <= marginOfError + 1) {
                    for (j in 1 until gap) {
                        filledRun.add(longestRun[i] + j)
                    }
                }
            }
        }

        // Ensure the filled run is sorted and contains no duplicates
        return filledRun.distinct().sorted()
    }

    fun findFirstRun(indices: List<Int>, marginOfError: Int = 1): List<Int> {
        if (indices.isEmpty()) return emptyList()

        val sortedIndices = indices.sorted()
        val firstRun = mutableListOf<Int>()

        // Start with the first index
        firstRun.add(sortedIndices[0])

        // Iterate through the sorted indices and fill gaps within the margin of error
        for (i in 1 until sortedIndices.size) {
            val previousIndex = firstRun.last()
            val currentIndex = sortedIndices[i]

            if (currentIndex - previousIndex <= marginOfError + 1) {
                // Add the current index to the run if it's within the margin of error
                firstRun.add(currentIndex)
            } else {
                // Stop as soon as the first run ends
                break
            }
        }

        // Fill in any gaps within the margin of error
        val filledRun = mutableListOf<Int>()
        for (i in firstRun.indices) {
            filledRun.add(firstRun[i])
            if (i < firstRun.size - 1) {
                val gap = firstRun[i + 1] - firstRun[i]
                if (gap > 1 && gap <= marginOfError + 1) {
                    for (j in 1 until gap) {
                        filledRun.add(firstRun[i] + j)
                    }
                }
            }
        }

        // Ensure the filled run is sorted and contains no duplicates
        return filledRun.distinct().sorted()
    }

    suspend fun loadThenPlayStart(timestamps: List<String>) {
//        isLoadingInitTranscription = true
        binding.loadingOverlay.visibility = View.VISIBLE
        audioNavigator.pause()
        if (locators.isEmpty())
            getCurrentVisibleContentRange()
//        transcribeAudio(timestamp)
        var currentTime = System.currentTimeMillis()
        Timber.d("Transcription for loadThenPlayStart")
        for (timestamp in timestamps) {
            while (!locatorMap.containsKey(timestamp)) {
                if (System.currentTimeMillis() - currentTime >= 100) {
                    currentTime = System.currentTimeMillis()
                    transcribeAudio(timestamp)
                    if (timestamp == timestamps.last()) {
                        break
                    }
                }
            }
            Timber.d("Transcription for timestamp: " + timestamp)
        }
        audioNavigator.play()
        binding.loadingOverlay.visibility = View.GONE
    }

    suspend fun loadThenPlayEnd() {
        isLoadingInitTranscription = false
        binding.loadingOverlay.visibility = View.GONE
        audioNavigator.play()
    }

    suspend fun highlightText(matchedLocators: List<Locator>?, pageLocators: List<Locator>? = null,prevLocators: List<Locator>? = null) {


//
//                TOOOOOOOOOOO DOOOOOOOOOOOOOOOOOO
//                   Remove overlap from Previous Locator
//
        val currentAudioPosition = audioNavigator.currentLocator.value
        val currentEPubPosition = navigator.currentLocator.value

//        Timber.d("Transcription for navSetting: "+navigator.settings.value)

        val settingsScroll = navigator.settings.value.scroll


//        Timber.d("Transcription for navSetting"+navigator.settings.value.textAlign.toString())
//        val epubPreferences = EpubPreferences
//        navigator.submitPreferences()


        matchedLocators?.isEmpty()?.let {
            if (!it) {

                var pageLocator = matchedLocators[0]

                if (settingsScroll && pageLocators != null)
//                    if(((pageLocators[0].locations.progression ?: 0.0) - (pageLocator.locations.progression ?: 0.0)) < 0.001)
                    if((pageLocators[0].href == pageLocator.href))
                        pageLocator = pageLocators[0]

//                Timber.d("Transcription for currentAudioPosition: " + currentAudioPosition.locations.totalProgression)
//                Timber.d("Transcription for currentEPubPosition: " + currentEPubPosition.locations.position)
//                Timber.d("Transcription for currentmatchedLocators[0]: " + matchedLocators[0].href)
//                Timber.d("Transcription for currentLocatorPosition: " + pageLocators?.get(0)?.href)
//                Timber.d("Transcription for testCompare: " + (pageLocators?.get(0)?.href == matchedLocators[0].href).toString())
//                if (pageLocators != null) {
//                    val numCompare = ((pageLocators[0].locations.progression
//                        ?: 0.0) - ((matchedLocators[0].locations.progression ?: 0.0)))
//
//                    val testCompare = (numCompare < 0.001)
//                    Timber.d("Transcription for numCompare: " + numCompare.toString())
//                    Timber.d("Transcription for testCompare: " + testCompare.toString())
//                }

//                publication.normalizeLocator(matchedLocators[0])
//                val iterator = publication.content(matchedLocators[0])?.iterator()
//                val locatorToGoTo = if (iterator!!.hasPrevious()){
//                    iterator.previous().locator
//                } else {
//                    matchedLocators[0]
//                }

                navigator.go(pageLocator, true)
//                navigator.go(locatorToGoTo, true)
//                navigator.goBackward(true)

                var updatedMatchedLocators = matchedLocators

                if (prevLocators != null && prevLocators.size >= 2){
                    updatedMatchedLocators = prevLocators.takeLast(2) + matchedLocators
                }

                // Concatenate locator.text.highlight into one string separated by spaces
//                val concatenatedHighlights = updatedMatchedLocators.joinToString(" ") { it.text.highlight.toString() }
//
//                val after = updatedMatchedLocators.last().text.after
//
//                val before = updatedMatchedLocators.first().text.before
//
//                val subLocator = Locator.Text(
//                    after = after,
//                    before = before,
//                    highlight = concatenatedHighlights
//                )
//
//                val highlightSum = matchedLocators[0].copy(text = subLocator)
//
//                Timber.d("Transcription for highlightSum: " + highlightSum.toString())

                val random = Random.Default

                (navigator as? DecorableNavigator)?.applyPageNumberDecorations()
                navigator.applyDecorations(
                    listOfNotNull(null),
                    "tts"
                )

                val decorations: List<Decoration> = updatedMatchedLocators.map { locator ->
                    Decoration(
                        id = "tts",
                        locator = locator,
                        style = Decoration.Style.Highlight(
                            tint = Color.rgb(124, 198, 247)
                        )
                    )
                }

//                val decoration = Decoration(
//                    id = "tts",
//                    locator = highlightSum,
//                    style = Decoration.Style.Highlight(tint = Color.rgb(
//                                random.nextInt(256),
//                                random.nextInt(256),
//                                random.nextInt(256)
//                            ))
//                )





                navigator.applyDecorations(
                    decorations,
                    "tts"
                )

//                navigator.applyDecorations(listOfNotNull(decoration), "tts")



            }
        }

//        if (locators.count() < 100)
//            getCurrentVisibleContentRange()
    }

}

// Examples of HTML templates for custom Decoration Styles.

/**
 * This Decorator Style will display a tinted "pen" icon in the page margin to show that a highlight
 * has an associated note.
 *
 * Note that the icon is served from the app assets folder.
 */
private fun annotationMarkTemplate(@ColorInt defaultTint: Int = Color.YELLOW): HtmlDecorationTemplate {
    val className = "testapp-annotation-mark"
    val iconUrl = checkNotNull(EpubNavigatorFragment.assetUrl("annotation-icon.svg"))
    return HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOUNDS,
        width = HtmlDecorationTemplate.Width.PAGE,
        element = { decoration ->
            val style = decoration.style as? DecorationStyleAnnotationMark
            val tint = style?.tint ?: defaultTint
            // Using `data-activable=1` prevents the whole decoration container from being
            // clickable. Only the icon will respond to activation events.
            """
            <div><div data-activable="1" class="$className" style="background-color: ${tint.toCss()} !important"/></div>"
            """
        },
        stylesheet = """
            .$className {
                float: left;
                margin-left: 8px;
                width: 30px;
                height: 30px;
                border-radius: 50%;
                background: url('$iconUrl') no-repeat center;
                background-size: auto 50%;
                opacity: 0.8;
            }
            """
    )
}

/**
 * This Decoration Style is used to display the page number labels in the margins, when a book
 * provides a `page-list`. The label is stored in the [DecorationStylePageNumber] itself.
 *
 * See http://kb.daisy.org/publishing/docs/navigation/pagelist.html
 */
private fun pageNumberTemplate(): HtmlDecorationTemplate {
    val className = "testapp-page-number"
    return HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOUNDS,
        width = HtmlDecorationTemplate.Width.PAGE,
        element = { decoration ->
            val style = decoration.style as? DecorationStylePageNumber

            // Using `var(--RS__backgroundColor)` is a trick to use the same background color as
            // the Readium theme. If we don't set it directly inline in the HTML, it might be
            // forced transparent by Readium CSS.
            """
            <div><span class="$className" style="background-color: var(--RS__backgroundColor) !important">${style?.label}</span></div>"
            """
        },
        stylesheet = """
            .$className {
                float: left;
                margin-left: 8px;
                padding: 0px 4px 0px 4px;
                border: 1px solid;
                border-radius: 20%;
                box-shadow: rgba(50, 50, 93, 0.25) 0px 2px 5px -1px, rgba(0, 0, 0, 0.3) 0px 1px 3px -1px;
                opacity: 0.8;
            }
            """
    )
}

