/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.example.audebook.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.appcompat.widget.SearchView
import androidx.core.os.BundleCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.Preferences
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
import org.readium.r2.shared.publication.services.content.ContentTokenizer

import kotlin.random.Random

import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit
import org.readium.r2.shared.util.tokenizer.Tokenizer
import org.readium.r2.shared.util.Language
import java.util.Locale

import com.example.audebook.Readium
import com.example.audebook.Application
import com.example.audebook.asr.Whisper
import com.example.audebook.bookshelf.BookshelfViewModel.Event
import com.example.audebook.domain.PublicationError
import com.example.audebook.domain.PublicationError.Companion.invoke
import com.example.audebook.domain.toUserError
import com.example.audebook.reader.preferences.ExoPlayerPreferencesManagerFactory
import com.example.audebook.utils.EventChannel
import com.example.audebook.utils.UserError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.json.JSONObject
import org.readium.adapter.exoplayer.audio.ExoPlayerEngine
import org.readium.adapter.exoplayer.audio.ExoPlayerEngineProvider
import org.readium.adapter.exoplayer.audio.ExoPlayerNavigator
import org.readium.adapter.exoplayer.audio.ExoPlayerPreferences
import org.readium.adapter.exoplayer.audio.ExoPlayerSettings
import org.readium.navigator.media.audio.AudioNavigator
import org.readium.navigator.media.audio.AudioNavigatorFactory
import org.readium.navigator.media.audio.AudioNavigatorFactory.Companion.invoke
import org.readium.navigator.media.common.MediaNavigator
import org.readium.r2.shared.publication.allAreHtml
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.getOrElse
import kotlin.time.Duration
import kotlin.time.DurationUnit


import com.arthenica.ffmpegkit.FFmpegKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

@OptIn(ExperimentalReadiumApi::class)
class EpubReaderFragment : VisualReaderFragment() {

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
    private lateinit var locatorMap: MutableMap<Locator, Boolean>

    private lateinit var application: Application

    private lateinit var navigatorPreferences: DataStore<Preferences>

//    private val Context.navigatorPreferences: DataStore<Preferences>
//            by preferencesDataStore(name = "navigator-preferences")

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
        if (savedInstanceState != null) {
            isSearchViewIconified = savedInstanceState.getBoolean(IS_SEARCH_VIEW_ICONIFIED)
        }

        application = requireActivity().application as Application
//        navigatorPreferences = application.navigatorPreferences
        readium = application.readium

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




        locators = mutableListOf()
        locatorMap = mutableMapOf()

        val readerData = model.readerInitData as? EpubReaderInitData ?: run {
            // We provide a dummy fragment factory  if the ReaderActivity is restored after the
            // app process was killed because the ReaderRepository is empty. In that case, finish
            // the activity as soon as possible and go back to the previous one.
            childFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
            super.onCreate(savedInstanceState)
            requireActivity().finish()
            return
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
//        binding.timelineBar.setOnSeekBarChangeListener(this)
        binding.playPause.setOnClickListener(this::onPlayPause)
        binding.skipForward.setOnClickListener(this::onSkipForward)
        binding.skipBackward.setOnClickListener(this::onSkipBackward)

//        binding.transcribe.setOnClickListener(this::onTranscribe)
//        binding.transcribe.setOnClickListener(this::onExtractAndTranscribe)
        binding.loadAudioBook.setOnClickListener { this::onLoadAudioBook }
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
                                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    // Display page number labels if the book contains a `page-list` navigation document.
                                    (navigator as? DecorableNavigator)?.applyPageNumberDecorations()
                                    navigator.applyDecorations(
                                        listOfNotNull(null),
                                        "tts"
                                    )
                                    val start = (navigator as? VisualNavigator)?.firstVisibleElementLocator()
                                    val content = publication.content(start)

                                    val bookId = 1L

                                    val book = checkNotNull(application.bookRepository.get(bookId))
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

                                    val audioPublication = readium.publicationOpener.open(
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
                                        (audioPublication as Publication).conformsTo(Publication.Profile.AUDIOBOOK) ->
                                            openAudio(bookId, audioPublication, initialLocator)
                                        else ->
                                            Try.failure(
                                                OpeningError.CannotRender(
                                                    DebugError("No navigator supports this publication.")
                                                )
                                            )
                                    }

                                    audioNavigator = readerInitData.getOrNull()!!

//                                    Timber.d(readerInitData.toString())
                                    audioNavigator.play()
                                    audioNavigator.playback
                                        .onEach { onPlaybackChanged(it) }
                                        .launchIn(viewLifecycleOwner.lifecycleScope)


                                    val tokenizer = DefaultTextContentTokenizer(unit = TextUnit.Sentence, language = Language(Locale.ENGLISH))
                                    val publicati = publication.content(start)
//                                    val wholeText = content?.text()
//                                    Timber.d(wholeText.toString())

//                                    val tokenizedContent = tokenizer.tokenize(content?.text().toString())
//                                    Timber.d(tokenizedContent.toString())

                                    var i = 0;

                                    val iterator = content?.iterator()
                                    locators.clear()
                                    while (iterator!!.hasNext() && i <= 10) {
                                        val element = iterator.next()
                                        val string = element.locator.text.highlight.toString()
//                                        Timber.d(element.locator.text.highlight)
                                        val tokenizedContent = tokenizer.tokenize(element.locator.text.highlight.toString())
                                        for (range in tokenizedContent){
//                                            Timber.d(range.toString())

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

//                                            Timber.d(subLocator.highlight)
                                            locators.add(element.locator.copy(text = subLocator))
                                        }

//                                        locators.add(element.locator)
                                        i=i+1
                                    }

                                    if (!locators.isEmpty()) {

                                        val random = Random.Default
                                        val decorations: List<Decoration> = locators.map { locator ->
                                            Decoration(
                                                id = "tts",
                                                locator = locator,
                                                style = Decoration.Style.Highlight(tint = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
                                            )
                                        }

                                        navigator.applyDecorations(
                                            decorations,
                                            "tts"
                                        )
                                    }
                                }
                            }

                            Timber.d(locators.toString())

                            binding.audioOverlayText

//                                    fun getCloseMatches(
//                                        word: String,
//                                        possibilities: List<String>,
//                                        n: Int = 1,
//                                        cutoff: Double = 0.5
//                                    ): List<String> {
//                                        val jaroWinkler = JaroWinkler()
//                                        return possibilities
//                                            .map { it to jaroWinkler.similarity(word, it) }
//                                            .filter { it.second >= cutoff }
//                                            .sortedByDescending { it.second }
//                                            .take(n)
//                                            .map { it.first }
//                                    }
//
//                                    fun main() {
//                                        val eachText = "example"
//                                        val sentences = listOf("sample", "example", "exemplar", "test", "simple")
//                                        val resultSegments = listOf("segment1", "segment2") // Example segments
//
//                                        val closeMatches = getCloseMatches(eachText, sentences.take(resultSegments.size * 2))
//                                        println(closeMatches)
//                                    }

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


//        val preferencesManager = ExoPlayerPreferencesManagerFactory(preferencesDataStore(name = "navigator-preferences"))
//            .createPreferenceManager(bookId)
//        val initialPreferences = preferencesManager.preferences.value

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
//            initialPreferences
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

//        val initData = MediaReaderInitData(
//            bookId,
//            publication,
//            navigator,
//            preferencesManager,
//            navigatorFactory
//        )
        return Try.success(navigator)
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

//            Timber.d("onPlaybackChanged $playback")
            transcribeAudio()
        }
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
                    } else {
                        audioNavigator.play()
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

    @Suppress("UNUSED_PARAMETER")
    private fun forbidUserSeeking(view: View, event: MotionEvent): Boolean =
        audioNavigator.playback.value.state is MediaNavigator.State.Ended

    private fun onLoadAudioBook(@Suppress("UNUSED_PARAMETER") view: View) {
        model.viewModelScope.launch {
//            audioNavigator.skipBackward()
            Timber.d("Load Audio Book")
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

    private fun transcribeAudio() {
        model.viewModelScope.launch {
            if (mWhisper?.isInProgress() == false) {
                val sdcardDataFolder = withContext(Dispatchers.IO) {
                    context?.getExternalFilesDir(null)
                }

                if (sdcardDataFolder != null) {

                    Timber.d(audioNavigator.readingOrder.items[0].toString())

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
                val convertCommand = "-y -i $outputFilePath $outputFilePath.wav"
                FFmpegKit.execute(convertCommand)
            }
        }
    }

    private fun convertTimestampToSeconds(timestamp: String): Int {
        val parts = timestamp.split(":").map { it.toInt() }
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
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

