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
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.view.*
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import com.example.audebook.engine.WhisperEngineJava
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

import com.example.audebook.utils.AudiobookFolder
import com.example.audebook.utils.AudiobookSegmentExtractor
import com.example.audebook.utils.AudiobookTimeline
import com.example.audebook.utils.ScrollFollower
import com.example.audebook.utils.SpeedRamp
import com.example.audebook.utils.sync.BookPosition
import com.example.audebook.utils.sync.SyncTracker
import com.example.audebook.utils.sync.WordCursor
import com.example.audebook.utils.TranscriptionPacing
import com.example.audebook.utils.extensions.persistDocumentAccess
import com.example.audebook.domain.Bookshelf
import com.example.audebook.domain.PublicationRetriever
import com.example.audebook.reader.preferences.AudioPreferencesBottomSheetDialogFragment
import com.example.audebook.reader.preferences.ExoPlayerPreferencesManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.format.Format
import org.readium.r2.shared.util.toUrl

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import org.readium.r2.navigator.preferences.Configurable
import org.readium.r2.navigator.preferences.Preference
import java.io.InputStream
import kotlin.time.Duration.Companion.seconds

import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.locate
import kotlin.Exception

import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import org.readium.navigator.media.tts.android.AndroidTtsPreferences

@OptIn(ExperimentalReadiumApi::class)
class EpubReaderFragment : VisualReaderFragment(), SeekBar.OnSeekBarChangeListener {

    override lateinit var navigator: EpubNavigatorFragment

    private lateinit var readium: Readium

    lateinit var audioNavigator: AudioNavigator<*, *>
    lateinit var audioPublication: Publication

    private lateinit var menuSearch: MenuItem
    lateinit var menuSearchView: SearchView

    private lateinit var menuDebug: MenuItem

    private var isSearchViewIconified = true


    private lateinit var application: Application

    private var seekingItem: Int? = null


    private var startTime: Long = 0

    private var mWhisper: Whisper? = null

    private var epubBookId: Long = -1

    private lateinit var appStoragePickerLauncher: ActivityResultLauncher<String>
    private lateinit var sharedStoragePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Uri?>

    private val coroutineScope: CoroutineScope =
        MainScope()

    private var lastSaveTime: Long = 0

    var totalTimeOfAudio: String = "00:00:00"

    var currentTranscribeSegment: String = "00:00:00"

    private val transcriptionMap: MutableMap<String, String> = mutableMapOf()

    private lateinit var transcriptionRange: List<String>

    private val locatorMap: MutableMap<String, List<Locator>> = mutableMapOf()

    // Finds each transcribed slot in the book (see SyncTracker) and fills locatorMap
    private var bookIndexer: BookIndexer? = null
    private var syncTracker: SyncTracker<Locator>? = null

    // Where each slot was found in the book; locatorMap holds the highlights built from these
    private val placements: MutableMap<String, SyncTracker.Placement> = mutableMapOf()

    /** Finds [transcript] in the book and records what to highlight for [slot]. */
    private fun placeSlot(slot: String, transcript: String) {
        val tracker = syncTracker ?: return
        val placement = tracker.place(convertTimestampToSeconds(slot), segmentLength, transcript) ?: return
        placements[slot] = placement
        // This slot's highlight runs up to the next slot, and the previous one now runs up to this
        refreshSlotHighlight(slot)
        refreshSlotHighlight(offsetSlot(slot, -1))
        Timber.d("Slot $slot: ${placement.kind} at ${placement.start}")
    }

    /** Rebuilds [slot]'s highlight so it meets the next slot's (see WordCursor.contiguousEnd). */
    private fun refreshSlotHighlight(slot: String) {
        val indexer = bookIndexer ?: return
        val placement = placements[slot] ?: return
        val end = WordCursor.contiguousEnd(placement, placements[offsetSlot(slot, 1)])
        locatorMap[slot] = indexer.locatorsFor(placement.copy(endWord = end))
    }

    /** Forgets every slot's placement and highlight, e.g. on Anchor. */
    private fun clearPlacements() {
        placements.clear()
        locatorMap.clear()
        lastCursorWord = null
    }

    /** Places every transcribed slot from [from] on (all if null), in time order. */
    private fun placeTranscribedSlots(from: String? = null) {
        transcriptionMap.keys.sorted()
            .filter { from == null || it >= from }
            .forEach { slot -> transcriptionMap[slot]?.let { placeSlot(slot, it) } }
    }

    private var isLoadingInitTranscription: Boolean = false

    private lateinit var latestPlaybackStatus: TimeBasedMediaNavigator.Playback

    private var isFollowingAudio: Boolean = true
    private var lastHighlightUpdate: Long = 0


    lateinit var audioDialog: AudioPreferencesBottomSheetDialogFragment


    // The audiobook's tracks as one timeline: slots, the timeline bar and Anchor all use book
    // time, so a book split into chapter files syncs like a single file
    private var audioTimeline = AudiobookTimeline(emptyList())

    // Reads the audiobook in place; opened in loadAudiobookNavigator, closed in onDestroy
    private var audioSegmentExtractor: AudiobookSegmentExtractor? = null
    private var isExtractingSegment = false

    // Two WAV files take turns so the next slot can be cut while Whisper reads the current one
    private var whisperWavPath: String? = null
    private var prefetchedSegment: String? = null
    private var prefetchedWavPath: String? = null

    var currentHighlight: List<Locator> = listOf<Locator>()

    var lastColour: Int = Color.rgb(124, 198, 247)

    var segmentLength: Int = 10

    lateinit var CachedWebView: List<WebView>

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            isSearchViewIconified = savedInstanceState.getBoolean(IS_SEARCH_VIEW_ICONIFIED)
        }

        application = requireActivity().application as Application
        readium = application.readium

        // Register for activity result here
        sharedStoragePickerLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                uri?.let {
                    requireContext().contentResolver.persistDocumentAccess(uri)
                    addPublicationFromStorage(it.toUrl()!! as AbsoluteUrl)
                }
            }
        folderPickerLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                uri?.let {
                    requireContext().contentResolver.persistDocumentAccess(it)
                    addAudiobookFolder(it)
                }
            }

        context?.let { ctx ->
            lifecycleScope.launch(Dispatchers.IO) {
                val sdcardDataFolder = ctx.getExternalFilesDir(null)
                if (sdcardDataFolder != null) {
                    Whisper.deleteLegacyModelCopies(sdcardDataFolder)
                    // Load here on the IO thread; the model is memory-mapped from the APK
                    val loadedWhisper = Whisper(ctx).apply {
                        loadModelFromAssets(Whisper.MODEL_ASSET, Whisper.VOCAB_ASSET, false)
                    }

                    withContext(Dispatchers.Main) {
                        if (mWhisper != null) {
                            // Already have one; free the extra model off the main thread
                            CoroutineScope(Dispatchers.IO).launch { loadedWhisper.release() }
                        } else {
                            mWhisper = loadedWhisper

                            mWhisper?.setListener(object : Whisper.WhisperListener {
                                override fun onUpdateReceived(message: String) {
                                    Timber.d("Update is received, Message: $message");

                                    when (message) {
                                        Whisper.MSG_PROCESSING -> {
                                            startTime = System.currentTimeMillis()
                                        }

                                        Whisper.MSG_PROCESSING_DONE -> {
                                        }

                                        Whisper.MSG_FILE_NOT_FOUND -> {
                                            Timber.d("File not found error...!")
                                        }
                                    }
                                }

                                override fun onResultReceived(result: String) {
                                    val cachedCurrentTranscribeSegment = currentTranscribeSegment
                                    val timeTaken = System.currentTimeMillis() - startTime

                                    lifecycleScope.launch(Dispatchers.Main) {
                                        transcriptionMap[cachedCurrentTranscribeSegment] = result
                                        recordSlotTime(timeTaken)
                                        // Start the next slot now instead of on the next playback
                                        // tick. Only while playing, so it doesn't run on while paused
                                        if (view != null && ::latestPlaybackStatus.isInitialized &&
                                            latestPlaybackStatus.playWhenReady
                                        ) {
                                            getNextTranscriptionSegment()
                                        }
                                        placeSlot(cachedCurrentTranscribeSegment, result)
                                        application.bookRepository.insertAudioBookTranscript(
                                            epubBookId,
                                            result,
                                            cachedCurrentTranscribeSegment
                                        )

                                    }

                                    Timber.d("Result: $result")
                                    Timber.d("timeTaken: $timeTaken ms")
                                }
                            })

                            Timber.d("Loaded Model");
                        }
                    }
                }
            }

        }


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

        // Index the book's text for syncing: the chapter being read first, so Anchor is ready
        // straight away, then the rest of the book in the background
        val indexer = BookIndexer(readerData.publication)
        bookIndexer = indexer
        syncTracker = SyncTracker(indexer.index)
        val startChapter = readerData.initialLocation?.let { indexer.chapterOf(it) } ?: 0
        lifecycleScope.launch(Dispatchers.Default) {
            indexer.indexChapter(startChapter)
            // Place transcripts saved from earlier sessions; with no Anchor, the tracker finds
            // where they are in the book by itself
            withContext(Dispatchers.Main) { placeTranscribedSlots() }
            indexer.indexOutwardsFrom(startChapter)
        }

        lifecycleScope.launch {
            try {
                if (::audioNavigator.isInitialized) {
                    // yourLateinitVar is initialized
                } else {
                    // yourLateinitVar is NOT initialized
                    loadAudiobookNavigator(epubBookId)
                }

            } catch (e: Exception) {
                Timber.d("")
                Timber.e(e, "Failed to load audiobook navigator")
                // Handle the exception, e.g., show an error message to the user
            }
        }

        lifecycleScope.launch {
            try {
                if (transcriptionMap.isEmpty()) {

                    val transcriptFlow =
                        application.bookRepository.getAllAudiobooksTranscripts(epubBookId)

                    var transcriptTimestamp = "00:00:00"

                    transcriptFlow.collect { transcriptList ->
                        transcriptList.forEach { transcript ->
                            // Do something with each AudioBookTranscript
                            try {
                                transcriptTimestamp = transcript.timestamp.toString()
                                transcriptionMap[transcriptTimestamp] =
                                    transcript.transcript.toString()

                            } catch (e: Exception) {
                                Timber.d("Transcription for transcript load" + transcript)
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                Timber.d("")
                Timber.e(e, "Failed to load cached transcript")
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
                    decorationTemplates[DecorationStyleAnnotationMark::class] =
                        annotationMarkTemplate()
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
        navigator =
            childFragmentManager.findFragmentByTag(NAVIGATOR_FRAGMENT_TAG) as EpubNavigatorFragment

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
        binding.loadAudioBook.setOnClickListener(this::onLoadAudioBook)
        binding.setSpeed.setOnClickListener(this::onSetSpeed)
        // Every touch, including those the book's WebViews take, so scrolling by hand is noticed
        (activity as? ReaderActivity)?.onAnyTouch = this::onReaderTouched
        updateControlsLayoutBackground(false)

        CachedWebView = findAllWebViews(requireView())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        applyControlsLayout(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        // A new font size, spacing etc. lays the page out again: bring the spoken word back
        viewLifecycleOwner.lifecycleScope.launch {
            navigator.settings.drop(1).collect { recenterSoon() }
        }

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
                            Timber.d(
                                "Sync: anchored=${syncTracker?.isAnchored}, " +
                                    "pace=${syncTracker?.wordsPerSecond}, " +
                                    "indexed chapters=${bookIndexer?.index?.chapters()?.keys?.sorted()}, " +
                                    "placed slots=${locatorMap.size}"
                            )

                            return true
                        }
                    }
                    return false
                }
            },
            viewLifecycleOwner
        )

        CachedWebView = findAllWebViews(requireView())
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

    override fun onDestroy() {
        // close() waits for an in-flight extraction, so keep it off the main thread
        audioSegmentExtractor?.let { extractor ->
            CoroutineScope(Dispatchers.IO).launch { extractor.close() }
        }
        audioSegmentExtractor = null
        // Each reader creates its own Whisper; free its model and stop its threads, after any
        // slot still being transcribed (so also off the main thread)
        mWhisper?.let { whisper ->
            CoroutineScope(Dispatchers.IO).launch { whisper.release() }
        }
        mWhisper = null
        super.onDestroy()
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

        menuSearchView.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)
            .setOnClickListener {
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

        // Longest Anchor will keep the loading overlay up waiting for the first slots
        private const val ANCHOR_TIMEOUT_MS = 60_000L

        // Fastest setting measured on a Galaxy S24+; 8 threads was slower than 4
        private const val ANCHOR_WHISPER_THREADS = 4

        // Word cursor: its own decoration group so moving it doesn't redraw the slot highlights,
        // a warm colour that stands out from the blue and teal slot highlights, and how often it
        // is moved (several times a second, so it keeps up even at 5x)
        private const val WORD_CURSOR_GROUP = "tts-word"
        private val WORD_CURSOR_COLOR = Color.rgb(255, 183, 77)
        private const val WORD_CURSOR_INTERVAL_MS = 120L

        // Recent Whisper times averaged when choosing a thread count (see TranscriptionPacing)
        private const val SLOT_TIME_WINDOW = 3

        private const val CURRENT_HIGHLIGHT_ID_PREFIX = "tts-now-"

        // Following the spoken word in scroll mode (see ScrollFollower)
        private const val FOLLOW_CHECK_INTERVAL_MS = 150L
        private const val FOLLOW_FRAME_MS = 16L
        private const val USER_SCROLL_PAUSE_MS = 3000L
        // Time for the page to lay itself out again after a rotation or font change
        private const val RECENTER_DELAY_MS = 800L

        // Middle of the word cursor (or, before it appears, the top of the current slot's
        // highlight) as a fraction of the WebView's height. Off-page positions are below 0 or
        // above 1, which is how a rotation or font change that moved the text is noticed.
        private const val SPOKEN_WORD_POSITION_JS = """
            (function() {
                if (!window.readium || !readium.getDecorations) return null;
                var rect = null;
                var words = readium.getDecorations('$WORD_CURSOR_GROUP').items;
                if (words.length > 0) {
                    var r = words[0].range.getBoundingClientRect();
                    if (r.height > 0) rect = r;
                }
                if (rect === null) {
                    readium.getDecorations('tts').items.forEach(function(item) {
                        if (String(item.decoration.id).indexOf('$CURRENT_HIGHLIGHT_ID_PREFIX') !== 0) return;
                        var r = item.range.getBoundingClientRect();
                        if (r.height > 0 && (rect === null || r.top < rect.top)) rect = r;
                    });
                }
                return rect === null ? null : (rect.top + rect.bottom) / 2 / window.innerHeight;
            })()
        """
    }

    private suspend fun openAudio(
        bookId: Long,
        publication: Publication,
        initialLocator: Locator?
    ): Try<AudioNavigator<ExoPlayerSettings, ExoPlayerPreferences>, OpeningError> {

        val preferencesManager =
            ExoPlayerPreferencesManagerFactory(application.readerRepository.preferencesDataStoreRepo)
                .createPreferenceManager(bookId)
        val initialPreferences = preferencesManager.preferences.value
        // The speed the user chose; the ramp eases up to it without changing it
        audioPreferences = preferencesManager.preferences

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

        speedRampNavigator = navigator

        val initData = MediaReaderInitData(
            bookId,
            publication,
            navigator,
            preferencesManager,
            navigatorFactory
        )

        val preferencesModel = UserPreferencesViewModel(model.viewModelScope, initData)

        @Suppress("Unchecked_cast")
        (navigator as? Configurable<ExoPlayerSettings, ExoPlayerPreferences>)
            ?.let { navigator ->
                @Suppress("Unchecked_cast")
                (preferencesModel as UserPreferencesViewModel<ExoPlayerSettings, ExoPlayerPreferences>)
                    .bind(navigator, viewLifecycleOwner)
            }

        audioDialog = AudioPreferencesBottomSheetDialogFragment()
        audioDialog.setAudioPreferencesModelFunc(preferencesModel!!)


        return Try.success(navigator)
    }

    private fun onPlaybackChanged(
        playback: TimeBasedMediaNavigator.Playback
    ) {
        Timber.v("onPlaybackChanged $playback")
        latestPlaybackStatus = playback
        // For the word cursor, which estimates the position between these updates
        playbackOffsetMs = audioTimeline.bookTime(playback.index, playback.offset).inWholeMilliseconds
        playbackOffsetAt = SystemClock.elapsedRealtime()
        val failureState = playback.state as? AudioNavigator.State.Failure<*>
        val endedState = playback.state as? AudioNavigator.State.Ended
        if (failureState != null) {
            val error = failureState.error as ExoPlayerEngine.Error
            onPlayerError(error)
            return
        }

        if (endedState != null) {
            audioNavigator.close()
            model.viewModelScope.launch {
                application.bookRepository.deleteAudiobookTranscript(epubBookId)
            }
            transcriptionMap.clear()
            clearPlacements()
            // The next audiobook has its own timeline
            syncTracker?.reset()
            chooseAudiobook()
            binding.loadAudioBook.visibility = View.VISIBLE

            return
        }

        // Ease up to the chosen speed whenever playback starts, whatever started it (the play
        // button, Anchor, or a headset or notification control)
        if (playback.playWhenReady && !wasPlaying) {
            startSpeedRamp()
            startWordCursor()
        } else if (!playback.playWhenReady && wasPlaying) {
            speedRampJob?.cancel()
            // The cursor stays on the last word while paused
            stopWordCursor()
        }
        wasPlaying = playback.playWhenReady

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

        val playbackTranscribeSegment =
            roundDownToSlot(timelineAudioPosition.formatElapsedTime())
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastHighlightUpdate >= 500) {
            lastHighlightUpdate = currentTime
            if (locatorMap.containsKey(playbackTranscribeSegment)) {
                model.viewModelScope.launch {
                    if (locatorMap.containsKey(
                            offsetSlot(
                                playbackTranscribeSegment,
                                -2
                            )
                        )
                    ) {
                        highlightText(
                            locatorMap[playbackTranscribeSegment], playbackTranscribeSegment,
                            locatorMap[offsetSlot(playbackTranscribeSegment, -2)],
                            locatorMap[offsetSlot(playbackTranscribeSegment, -1)]
                        )
                    } else if (locatorMap.containsKey(
                            offsetSlot(
                                playbackTranscribeSegment,
                                -1
                            )
                        )
                    ) {
                        highlightText(
                            locatorMap[playbackTranscribeSegment], playbackTranscribeSegment,
                            locatorMap[offsetSlot(playbackTranscribeSegment, -1)],
                            locatorMap[offsetSlot(playbackTranscribeSegment, -1)]
                        )
                    } else {
                        highlightText(
                            locatorMap[playbackTranscribeSegment],
                            playbackTranscribeSegment
                        )
                    }
                }
            } else {
                // Transcribed but not placed yet, e.g. it arrived before its chapter was indexed
                transcriptionMap[playbackTranscribeSegment]?.let { placeSlot(playbackTranscribeSegment, it) }
            }
        }

        if (currentTime - lastSaveTime >= 1000) { // 5 seconds
            lastSaveTime = currentTime
            model.viewModelScope.launch {
                application.bookRepository.saveAudiobookProgression(
                    audioNavigator.currentLocator.value,
                    epubBookId
                )

                getNextTranscriptionSegment()

                val settingsSpeed =
                    (audioDialog.audioPreferencesModel.editor.value.preferences as ExoPlayerPreferences).speed

                val roundedSpeed = if (settingsSpeed == null) {
                    1.0
                } else {
                    when {
                        settingsSpeed <= 0.75 -> 0.5
                        settingsSpeed <= 0.95 -> 0.8
                        settingsSpeed <= 1.15 -> 1.0
                        settingsSpeed <= 1.45 -> 1.2
                        settingsSpeed <= 1.75 -> 1.5
                        settingsSpeed <= 1.95 -> 1.8
                        settingsSpeed <= 2.15 -> 2.0
                        settingsSpeed <= 2.45 -> 2.2
                        settingsSpeed <= 2.75 -> 2.5
                        settingsSpeed <= 2.95 -> 2.8
                        settingsSpeed <= 3.15 -> 3.0
                        settingsSpeed <= 3.45 -> 3.2
                        settingsSpeed <= 3.75 -> 3.5
                        settingsSpeed <= 3.95 -> 3.8
                        settingsSpeed <= 4.15 -> 4.0
                        settingsSpeed <= 4.45 -> 4.2
                        settingsSpeed <= 4.75 -> 4.5
                        settingsSpeed <= 4.95 -> 4.8
                        else -> 5.0
                    }
                }

                // Map the settingsSpeed to the corresponding drawable resource
                val speedDrawable = when (roundedSpeed) {
                    0.5 -> R.drawable.ic_icon_playback_speed_0_5
                    0.8 -> R.drawable.ic_icon_playback_speed_0_8
                    1.0 -> R.drawable.ic_icon_playback_speed_1_0
                    1.2 -> R.drawable.ic_icon_playback_speed_1_2
                    1.5 -> R.drawable.ic_icon_playback_speed_1_5
                    1.8 -> R.drawable.ic_icon_playback_speed_1_8
                    2.0 -> R.drawable.ic_icon_playback_speed_2_0
                    2.2 -> R.drawable.ic_icon_playback_speed_2_2
                    2.5 -> R.drawable.ic_icon_playback_speed_2_5
                    2.8 -> R.drawable.ic_icon_playback_speed_2_8
                    3.0 -> R.drawable.ic_icon_playback_speed_3_0
                    3.2 -> R.drawable.ic_icon_playback_speed_3_2
                    3.5 -> R.drawable.ic_icon_playback_speed_3_5
                    3.8 -> R.drawable.ic_icon_playback_speed_3_8
                    4.0 -> R.drawable.ic_icon_playback_speed_4_0
                    4.2 -> R.drawable.ic_icon_playback_speed_4_2
                    4.5 -> R.drawable.ic_icon_playback_speed_4_5
                    4.8 -> R.drawable.ic_icon_playback_speed_4_8
                    5.0 -> R.drawable.ic_icon_playback_speed_5_0
                    else -> R.drawable.ic_icon_playback_speed_1_0 // Default to 1.0x if no match
                }

                // Set the image of the setSpeed button
                binding.setSpeed.setImageResource(speedDrawable)


            }
        }

        if (navigator.settings.value.scroll && latestPlaybackStatus.playWhenReady) {
            startFollowing()
        } else {
            stopFollowing()
        }

    }

    /**
     * Starts transcribing the first slot without a transcript, from the playback position up to
     * [maxSlotsAhead] slots ahead. Once that far ahead, Whisper idles until playback catches up.
     */
    private suspend fun getNextTranscriptionSegment() {
        val playbackSegment = getPlaybackTranscribeSegment()
        for (ahead in 0..maxSlotsAhead()) {
            val segment = offsetSlot(playbackSegment, ahead)
            if (!transcriptionMap.containsKey(segment)) {
                if (ahead == 0) checkFallingBehind(segment)
                Timber.d("No transcription found for $segment")
                transcribeAudio(segment)
                return
            }
        }
    }

    // Playback speed ramp (see SpeedRamp): playback starts at 1x and rises to the chosen speed
    private var audioPreferences: StateFlow<ExoPlayerPreferences>? = null
    private var speedRampNavigator: AudioNavigator<ExoPlayerSettings, ExoPlayerPreferences>? = null
    private var speedRampJob: Job? = null
    private var wasPlaying = false

    /**
     * Submits gradually rising speeds to the player, then the user's own preferences once the
     * chosen speed is reached. The saved setting is never changed, and the target is re-read on
     * every step, so changing the speed during the ramp still takes effect.
     */
    private fun startSpeedRamp() {
        speedRampJob?.cancel()
        val navigator = speedRampNavigator ?: return
        val preferences = audioPreferences ?: return
        if (!SpeedRamp.shouldRamp(preferences.value.speed ?: 1.0)) return

        Timber.d(
            "Speed ramp: %.1fx -> %.1fx over %d ms".format(
                SpeedRamp.START_SPEED, preferences.value.speed ?: 1.0, SpeedRamp.durationMs(preferences.value.speed ?: 1.0)
            )
        )
        speedRampJob = viewLifecycleOwner.lifecycleScope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                val chosen = preferences.value
                val target = chosen.speed ?: 1.0
                val speed = SpeedRamp.speedAt(System.currentTimeMillis() - startedAt, target)
                if (speed >= target) {
                    navigator.submitPreferences(chosen)
                    Timber.d("Speed ramp reached %.1fx".format(target))
                    break
                }
                navigator.submitPreferences(chosen.copy(speed = speed))
                delay(SpeedRamp.STEP_INTERVAL_MS)
            }
        }
    }

    // Word cursor: a small highlight on the word being spoken, estimated from how far playback is
    // into its slot (see WordCursor.wordAt). Playback position updates arrive irregularly, so the
    // position is extrapolated from the last one.
    private var wordCursorJob: Job? = null
    private var lastCursorWord: BookPosition? = null
    private var playbackOffsetMs = 0L
    private var playbackOffsetAt = 0L

    private fun startWordCursor() {
        wordCursorJob?.cancel()
        wordCursorJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                updateWordCursor()
                delay(WORD_CURSOR_INTERVAL_MS)
            }
        }
    }

    private fun stopWordCursor() {
        wordCursorJob?.cancel()
        wordCursorJob = null
    }

    private suspend fun updateWordCursor() {
        val indexer = bookIndexer ?: return
        val sinceUpdate = if (isPlaying()) SystemClock.elapsedRealtime() - playbackOffsetAt else 0L
        val positionMs = playbackOffsetMs + (sinceUpdate * playbackSpeed()).toLong()
        val slotSeconds = (positionMs / 1000 / segmentLength) * segmentLength
        val slot = offsetSlot("00:00:00", (slotSeconds / segmentLength).toInt())

        val placement = placements[slot]
        if (placement == null) {
            // Not placed yet (e.g. still transcribing): hide the cursor rather than show a stale word
            if (lastCursorWord != null) {
                lastCursorWord = null
                navigator.applyDecorations(emptyList(), WORD_CURSOR_GROUP)
            }
            return
        }
        val chapter = indexer.index.chapter(placement.start.chapter) ?: return
        val end = WordCursor.contiguousEnd(placement, placements[offsetSlot(slot, 1)])
        val fraction = (positionMs - slotSeconds * 1000.0) / (segmentLength * 1000.0)
        val word = WordCursor.wordAt(fraction, placement.start.word, end) { chapter.wordCharCount(it) }
        val position = BookPosition(placement.start.chapter, word)
        if (position == lastCursorWord) return
        lastCursorWord = position

        val locator = indexer.locatorsFor(placement.copy(start = position, endWord = word)).firstOrNull() ?: return
        navigator.applyDecorations(
            listOf(Decoration(id = WORD_CURSOR_GROUP, locator = locator, style = Decoration.Style.Highlight(tint = WORD_CURSOR_COLOR))),
            WORD_CURSOR_GROUP
        )
    }

    // Applied playback speed, which the settings dialog doesn't report until it is changed
    private fun playbackSpeed(): Double =
        if (::audioNavigator.isInitialized) {
            (audioNavigator.settings.value as? ExoPlayerSettings)?.speed ?: 1.0
        } else {
            1.0
        }

    /**
     * How many slots past the playback position to keep transcribed: a fixed amount of listening
     * time, so more slots at higher speeds (6 at 1x, 30 at 5x) for a bigger cushion.
     */
    private fun maxSlotsAhead(): Int = TranscriptionPacing.maxSlotsAhead(playbackSpeed(), segmentLength)

    // Adaptive thread count for steady playback: Whisper times for recent slots decide whether
    // it has enough headroom over the playback speed
    private var transcriptionThreads = WhisperEngineJava.DEFAULT_NUM_THREADS
    private val recentSlotTimesMs = ArrayDeque<Long>()
    private var skipNextSlotTime = false
    private var isAnchoring = false
    private var lastBehindSegment: String? = null

    private fun isPlaying() = ::latestPlaybackStatus.isInitialized && latestPlaybackStatus.playWhenReady

    /**
     * Called with how long Whisper took for a slot. Once there are [SLOT_TIME_WINDOW] samples,
     * lets [TranscriptionPacing.nextThreadCount] step the thread count up or down.
     */
    private fun recordSlotTime(timeTakenMs: Long) {
        // Anchor runs at its own thread count, and while paused there is no deadline
        if (isAnchoring || !isPlaying()) return
        // The slot after a change may have started at the old thread count
        if (skipNextSlotTime) {
            skipNextSlotTime = false
            return
        }
        recentSlotTimesMs.addLast(timeTakenMs)
        if (recentSlotTimesMs.size > SLOT_TIME_WINDOW) recentSlotTimesMs.removeFirst()
        if (recentSlotTimesMs.size < SLOT_TIME_WINDOW) return

        val headroom = TranscriptionPacing.headroom(recentSlotTimesMs.average(), playbackSpeed(), segmentLength)
        val threads = TranscriptionPacing.nextThreadCount(transcriptionThreads, headroom)
        if (threads != transcriptionThreads) {
            setTranscriptionThreads(threads, "headroom %.1fx".format(headroom))
        }
    }

    /**
     * Playback reaching a slot with no transcript, right after one that had one, means
     * transcription fell behind during continuous playback (unlike a seek or a fresh start).
     */
    private fun checkFallingBehind(segment: String) {
        if (isAnchoring || !isPlaying() || segment == lastBehindSegment) return
        if (!transcriptionMap.containsKey(offsetSlot(segment, -1))) return
        lastBehindSegment = segment
        Timber.w(
            "Transcription is behind playback at $segment (%.1fx speed, $transcriptionThreads threads)"
                .format(playbackSpeed())
        )
        val threads = TranscriptionPacing.threadsAfterFallingBehind(transcriptionThreads)
        if (threads != transcriptionThreads) {
            setTranscriptionThreads(threads, "fell behind playback")
        }
    }

    private fun setTranscriptionThreads(threads: Int, reason: String) {
        Timber.i("Whisper threads $transcriptionThreads -> $threads ($reason, %.1fx speed)".format(playbackSpeed()))
        transcriptionThreads = threads
        recentSlotTimesMs.clear()
        skipNextSlotTime = true
        if (!isAnchoring) mWhisper?.setNumThreads(threads)
    }

    private fun getPlaybackTranscribeSegment(): String {
        return roundDownToSlot(timelineAudioPosition.formatElapsedTime())
    }

    // Where the timeline is in the book's audio (the dragged position while seeking), in book
    // time across all its tracks. The labels show times at the chosen speed instead, so slots are
    // worked out from this, not the label.
    private var timelineAudioPosition: Duration = Duration.ZERO

    /**
     * The speed chosen in the audio settings, used for the times shown: a 12 hour book at 2x
     * shows 6 hours. Not the speed of the start-up ramp, so the times don't change as it eases up.
     */
    private fun displaySpeed(): Double =
        audioPreferences?.value?.speed?.takeIf { it > 0 } ?: 1.0

    private fun updateTimeline(
        playback: TimeBasedMediaNavigator.Playback
    ) {
        // The whole book, not just the track playing
        val position = audioTimeline.bookTime(playback.index, playback.offset)
        binding.timelineBar.max = audioTimeline.duration.inWholeSeconds.toInt()
        val speed = displaySpeed()
        binding.timelineDuration.text = (audioTimeline.duration / speed).formatElapsedTime()
        binding.timelineBar.progress = position.inWholeSeconds.toInt()
        timelineAudioPosition = position
        binding.timelinePosition.text = (position / speed).formatElapsedTime()
        binding.timelineBar.secondaryProgress = playback.buffered
            ?.let { audioTimeline.bookTime(playback.index, it).inWholeSeconds.toInt() } ?: 0
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
                        if (transcriptionMap.isEmpty()) {
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
            timelineAudioPosition = progress.seconds
            binding.timelinePosition.text = (progress.seconds / displaySpeed()).formatElapsedTime()
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        Timber.d("onStartTrackingTouch")
        seekingItem = audioNavigator.playback.value.index
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        Timber.d("onStopTrackingTouch")
        val (track, offset) = audioTimeline.trackAt(seekBar.progress.seconds)
        audioNavigator.skipTo(track, offset)
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

    @Suppress("UNUSED_PARAMETER")
    private fun forbidUserSeeking(view: View, event: MotionEvent): Boolean {
        // Check if audioNavigator is initialized
        if (!::audioNavigator.isInitialized) {
            return false
        }
        return audioNavigator.playback.value.state is MediaNavigator.State.Ended
    }

    private fun onReaderTouched(event: MotionEvent) {
        // Pressing the audio controls isn't scrolling
        val controls = binding.audioOverlay
        val controlsTop = IntArray(2).also { controls.getLocationOnScreen(it) }[1]
        if (controls.isShown && event.rawY >= controlsTop) return
        // The reader is scrolling or tapping the page: leave the scrolling to them for a moment
        followPausedUntil = SystemClock.uptimeMillis() + USER_SCROLL_PAUSE_MS
        scrollFollower.reset()
    }

    override fun onDestroyView() {
        (activity as? ReaderActivity)?.onAnyTouch = null
        super.onDestroyView()
    }

    private fun onLoadAudioBook(@Suppress("UNUSED_PARAMETER") view: View) {
        model.viewModelScope.launch {
            Timber.d("Load Audio Book")
            chooseAudiobook()
        }
    }

    /** Asks whether the audiobook is a folder of files (one per chapter, say) or a single file. */
    private fun chooseAudiobook() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.audiobook_choose_title)
            .setItems(
                arrayOf(
                    getString(R.string.audiobook_choose_folder),
                    getString(R.string.audiobook_choose_file)
                )
            ) { _, which ->
                if (which == 0) {
                    folderPickerLauncher.launch(null)
                } else {
                    sharedStoragePickerLauncher.launch(arrayOf("*/*"))
                }
            }
            .show()
    }

    private fun onSetSpeed(@Suppress("UNUSED_PARAMETER") view: View) {
        model.viewModelScope.launch {

            audioDialog.show(childFragmentManager, "Settings")
        }
    }

    private suspend fun transcribeAudio(transcriptionTimestamp: String) {
        try {
            if (mWhisper?.isInProgress() == false && !transcriptionMap.containsKey(
                    transcriptionTimestamp
                )
            ) {

                val sdcardDataFolder = withContext(Dispatchers.IO) {
                    context?.getExternalFilesDir(null)
                }

                if (transcriptionMap.isEmpty())
                    currentTranscribeSegment = transcriptionTimestamp
                if (transcriptionMap.containsKey(currentTranscribeSegment))
                    currentTranscribeSegment = transcriptionTimestamp

                if (sdcardDataFolder != null && !isExtractingSegment && !transcriptionMap.containsKey(transcriptionTimestamp) && currentTranscribeSegment == transcriptionTimestamp) {

                    val timestamp = withContext(Dispatchers.Main) {
                        timelineAudioPosition.formatElapsedTime()
                    }
                    val duration = segmentLength

                    val startTimeInSeconds = convertTimestampToSeconds(currentTranscribeSegment)
                    val startTimeFormatted = String.format(
                        "%02d:%02d:%02d",
                        startTimeInSeconds / 3600,
                        (startTimeInSeconds % 3600) / 60,
                        startTimeInSeconds % 60
                    )

                    Timber.d(timestamp)

                    val wavPath = if (prefetchedSegment == currentTranscribeSegment && prefetchedWavPath != null) {
                        // Already cut while Whisper was busy with the previous slot
                        prefetchedWavPath!!.also {
                            prefetchedSegment = null
                            prefetchedWavPath = null
                        }
                    } else {
                        val outputFilePath = freeWavBase(sdcardDataFolder)
                        if (!extractAudioSegment(outputFilePath, startTimeFormatted, duration)) return
                        "$outputFilePath.wav"
                    }

                    whisperWavPath = wavPath
                    mWhisper?.apply {
                        setFilePath(wavPath)
                        Timber.d(wavPath)
                        setAction(Whisper.ACTION_TRANSCRIBE)
                        start()
                    }
                } else {
                    Timber.d("Transcription for currentTranscribeSegment: Still Adding currentTranscribeSegment")
                    currentTranscribeSegment = transcriptionTimestamp
                }
            } else {
                Timber.d("Whisper is already in progress...!")
                if (transcriptionMap.containsKey(currentTranscribeSegment)) {
                    mWhisper?.stop()
                    Timber.d("Transcription for currentTranscribeSegment: STOPPPPPP")
                }
                // Use the wait to cut the next slot, so Whisper can start it as soon as it's free
                lifecycleScope.launch { prefetchNextSegment() }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error occurred while transcribing audio")
        }
    }

    /**
     * While Whisper works on [currentTranscribeSegment], cuts the slot after it into the WAV file
     * Whisper isn't reading. transcribeAudio() then starts that slot without cutting it again.
     */
    private suspend fun prefetchNextSegment() {
        val folder = withContext(Dispatchers.IO) { context?.getExternalFilesDir(null) } ?: return
        val next = offsetSlot(currentTranscribeSegment, 1)
        // Checked after the suspension above and with no suspension before extractAudioSegment()
        // sets isExtractingSegment, so two prefetches can't start at once
        if (isExtractingSegment || mWhisper?.isInProgress() != true ||
            transcriptionMap.containsKey(next) || prefetchedSegment == next
        ) return
        // Don't cut a slot beyond the lookahead; it won't be transcribed until playback gets closer
        val slotsAhead = (convertTimestampToSeconds(next) -
            convertTimestampToSeconds(getPlaybackTranscribeSegment())) / segmentLength
        if (!isAnchoring && slotsAhead > maxSlotsAhead()) return

        val outputFilePath = freeWavBase(folder)
        if (prefetchedWavPath == "$outputFilePath.wav") {
            // Overwriting an older prefetch that wasn't used
            prefetchedSegment = null
            prefetchedWavPath = null
        }
        if (extractAudioSegment(outputFilePath, next, segmentLength)) {
            prefetchedSegment = next
            prefetchedWavPath = "$outputFilePath.wav"
            Timber.d("Prefetched slot $next while Whisper was busy")
            // Whisper may have finished while this was cutting; start the slot now rather than
            // waiting for the next tick (Anchor polls on its own)
            if (mWhisper?.isInProgress() == false && view != null &&
                ::latestPlaybackStatus.isInitialized && latestPlaybackStatus.playWhenReady
            ) {
                getNextTranscriptionSegment()
            }
        }
    }

    /**
     * Returns a WAV path (without ".wav") that Whisper isn't reading, preferring the one that
     * doesn't hold a prefetched slot.
     */
    private fun freeWavBase(folder: File): String {
        val bases = listOf("extracted_segment_a", "extracted_segment_b").map { "${folder.absolutePath}/$it" }
        val inUse = if (mWhisper?.isInProgress() == true) whisperWavPath else null
        return bases.firstOrNull { "$it.wav" != inUse && "$it.wav" != prefetchedWavPath }
            ?: bases.first { "$it.wav" != inUse }
    }

    /** Writes the clip to "$outputFilePath.wav" off the main thread. Returns false if nothing was written. */
    private suspend fun extractAudioSegment(
        outputFilePath: String,
        startTime: String,
        duration: Int
    ): Boolean {
        val extractor = audioSegmentExtractor ?: return false
        isExtractingSegment = true
        try {
            return withContext(Dispatchers.IO) {
                extractor.extractToWav(
                    "$outputFilePath.wav",
                    convertTimestampToSeconds(startTime),
                    duration
                )
            }
        } finally {
            isExtractingSegment = false
        }
    }

    private fun convertTimestampToSeconds(timestamp: String): Int {
        var parts = timestamp.split(":").map { it.toInt() }.toMutableList()
        if (parts.size != 3) {
            if (parts.size == 2) {
                parts.add(0, 0)
            } else if (parts.size == 1) {
                parts.add(0, 0)
                parts.add(0, 0)
            } else {
                parts = mutableListOf(0, 0, 0)
            }
        }
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    }

    /** Rounds an "HH:MM:SS" position down to the start of its slot (segmentLength seconds). */
    private fun roundDownToSlot(timestamp: String): String {

        var parts = timestamp.split(":").map { it.toInt() }.toMutableList()
        if (parts.size != 3) {
            if (parts.size == 2) {
                parts.add(0, 0)
            } else if (parts.size == 1) {
                parts.add(0, 0)
                parts.add(0, 0)
            } else {
                parts = mutableListOf(0, 0, 0)
            }
        }

        val totalSeconds = parts[0] * 3600 + parts[1] * 60 + parts[2]
        val roundedSeconds = (totalSeconds / segmentLength) * segmentLength

        val hours = roundedSeconds / 3600
        val minutes = (roundedSeconds % 3600) / 60
        val seconds = roundedSeconds % 60

        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun generateTranscriptionRanges(currentTranscribeSegment: String): List<String> {
        val baseTimeInSeconds = convertTimestampToSeconds(currentTranscribeSegment)
        val ranges = mutableListOf<String>()

        for (i in 0..3 * segmentLength step segmentLength) {
            val newTimeInSeconds = baseTimeInSeconds + i
            val hours = newTimeInSeconds / 3600
            val minutes = (newTimeInSeconds % 3600) / 60
            val seconds = newTimeInSeconds % 60
            ranges.add(String.format("%02d:%02d:%02d", hours, minutes, seconds))
        }

        Timber.d("Transcription Range: " + ranges.toString())

        return ranges
    }

    /** The slot [intervals] slots after (or before, if negative) the given "HH:MM:SS" slot. */
    private fun offsetSlot(currentTranscribeSegment: String, intervals: Int): String {
        val baseTimeInSeconds = convertTimestampToSeconds(currentTranscribeSegment)
        val newTimeInSeconds = baseTimeInSeconds + (intervals * segmentLength)
        val hours = newTimeInSeconds / 3600
        val minutes = (newTimeInSeconds % 3600) / 60
        val seconds = newTimeInSeconds % 60

        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private suspend fun loadAudiobookNavigator(bookId: Long) {

        val allaudiobooks = application.bookRepository.audiobooks()
        val allbooks = application.bookRepository.books()
        allaudiobooks.onEach {
            Timber.d(it.toString())
        }

        val book = checkNotNull(application.bookRepository.getAudiobook(bookId))
        val audioPublicationLoader = if (book.mediaType == AudiobookFolder.MEDIA_TYPE) {
            val appContext = requireContext().applicationContext
            withContext(Dispatchers.IO) {
                AudiobookFolder.open(appContext, Uri.parse(book.href), book.title)
            } ?: run {
                Timber.e("The audiobook folder ${book.href} has no audio files any more")
                return
            }
        } else {
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

            readium.publicationOpener.open(
                asset as Asset,
                allowUserInteraction = true
            ).getOrElse {
                return@getOrElse Try.failure(
                    OpeningError.PublicationError(
                        PublicationError(it)
                    )
                )
            }
        }

        val initialLocator = book.progression
            ?.let { Locator.fromJSON(JSONObject(it)) }

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
        audioTimeline = AudiobookTimeline(
            audioNavigator.readingOrder.items.mapIndexed { index, item ->
                item.duration ?: Duration.ZERO.also { Timber.w("Track $index has no duration: ${item.href}") }
            }
        )

        // The times are shown at the chosen speed: refresh them when it changes, even while paused
        audioPreferences?.let { preferences ->
            viewLifecycleOwner.lifecycleScope.launch {
                preferences.collect { if (seekingItem == null) updateTimeline(audioNavigator.playback.value) }
            }
        }

        totalTimeOfAudio = audioTimeline.duration.formatElapsedTime()

        audioNavigator.playback
            .onEach { onPlaybackChanged(it) }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        binding.loadAudioBook.visibility = View.GONE

        // LOAD TRANSCRIPT

        val trackUris = audioPublication.readingOrder.mapNotNull { link ->
            audioPublication.get(link.url())?.let { resource ->
                resource.sourceUrl.also { resource.close() }
            }?.let { Uri.parse(it.toString()) }
        }
        val appContext = requireContext().applicationContext
        val previousExtractor = audioSegmentExtractor
        // A clip cut ahead from a previous audiobook must not be transcribed for this one
        prefetchedSegment = null
        prefetchedWavPath = null
        audioSegmentExtractor = withContext(Dispatchers.IO) {
            previousExtractor?.close()
            if (trackUris.size == audioPublication.readingOrder.size) {
                AudiobookSegmentExtractor(appContext, trackUris, audioTimeline)
            } else {
                Timber.e("Cannot transcribe the audiobook: some tracks can't be read in place")
                null
            }
        }

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

            val id = application.bookRepository.insertAudioBook(
                url,
                asset.format.mediaType,
                audioPublication,
                epubBookId
            )
            if (id == -1L) {
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

    /** Uses the audio files in the picked folder, in file name order, as this book's audiobook. */
    private fun addAudiobookFolder(treeUri: Uri) {
        val appContext = requireContext().applicationContext
        coroutineScope.launch {
            val title = withContext(Dispatchers.IO) { AudiobookFolder.name(appContext, treeUri) }
            val publication = withContext(Dispatchers.IO) { AudiobookFolder.open(appContext, treeUri, title) }
            if (publication == null) {
                Toast.makeText(appContext, R.string.audiobook_folder_empty, Toast.LENGTH_LONG).show()
                return@launch
            }
            application.bookRepository.insertAudioBook(
                treeUri.toUrl()!!,
                AudiobookFolder.MEDIA_TYPE,
                publication,
                epubBookId
            )
            try {
                loadAudiobookNavigator(epubBookId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load audiobook navigator")
            }
        }
    }

    fun onReanchorTranscriptionLocator(@Suppress("UNUSED_PARAMETER") view: View) {
        audioNavigator.pause()

        model.viewModelScope.launch {
            clearPlacements()
            binding.loadingOverlay.visibility = View.VISIBLE
            val playbackTime = timelineAudioPosition.formatElapsedTime()
            val currentTime = roundDownToSlot(playbackTime)

            // Tell the tracker the narrator is at the first visible paragraph right now
            val firstLocator = (navigator as? VisualNavigator)?.firstVisibleElementLocator()
            val indexer = bookIndexer
            val tracker = syncTracker
            if (firstLocator != null && indexer != null && tracker != null) {
                // Normally indexed already, when the book opened; if not, this takes well under a second
                indexer.chapterOf(firstLocator)?.let { chapter ->
                    withContext(Dispatchers.Default) { indexer.indexChapter(chapter) }
                }
                val position = indexer.positionOf(firstLocator)
                if (position != null) {
                    tracker.anchor(convertTimestampToSeconds(playbackTime), position)
                    Timber.d("Anchored $playbackTime at $position")
                }
                placeTranscribedSlots(from = currentTime)
            }

            loadThenPlayStart(
                generateTranscriptionRanges(
                    roundDownToSlot(
                        currentTime
                    )
                )
            )

            CachedWebView = findAllWebViews(requireView())
        }
    }

    private fun updateControlsLayoutBackground(playWhenReady: Boolean) {
        val currentColor =
            (binding.controlsLayout.background as? ColorDrawable)?.color ?: Color.WHITE
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

        animator.start()
    }

    suspend fun loadThenPlayStart(timestamps: List<String>) {
        binding.loadingOverlay.visibility = View.VISIBLE
        audioNavigator.pause()
        Timber.d("Transcription for loadThenPlayStart")
        // The user is waiting on the overlay, so trade some battery for speed until playback
        // starts, then drop back to the low-power default for steady transcription
        isAnchoring = true
        mWhisper?.setNumThreads(ANCHOR_WHISPER_THREADS)
        val finished = try {
            withTimeoutOrNull(ANCHOR_TIMEOUT_MS) {
                for (timestamp in timestamps) {
                    // transcribeAudio() ignores requests while Whisper is busy, so keep asking.
                    // delay() frees the main thread, which is where Whisper's result is delivered.
                    while (!transcriptionMap.containsKey(timestamp)) {
                        transcribeAudio(timestamp)
                        Timber.d("Transcription for loadThenPlayStart: " + timestamp)
                        if (timestamp == timestamps.last()) {
                            break
                        }
                        delay(100)
                    }
                    Timber.d("Transcription for timestamp: " + timestamp)
                }
            }
        } finally {
            // Back to the adaptive level for steady playback
            isAnchoring = false
            mWhisper?.setNumThreads(transcriptionThreads)
        }
        if (finished == null) {
            Timber.w("Anchor gave up waiting for transcripts after $ANCHOR_TIMEOUT_MS ms; playing anyway")
        }
        audioNavigator.play()
        updateControlsLayoutBackground(true)
        binding.loadingOverlay.visibility = View.GONE
    }

    suspend fun loadThenPlayEnd() {
        isLoadingInitTranscription = false
        binding.loadingOverlay.visibility = View.GONE
        audioNavigator.play()
    }

    suspend fun highlightText(
        matchedLocators: List<Locator>?,
        playbackTranscribeSegment: String,
        pageLocators: List<Locator>? = null,
        prevLocators: List<Locator>? = null
    ) {

        val currentAudioPosition = audioNavigator.currentLocator.value
        val currentEPubPosition = navigator.currentLocator.value

        val settingsScroll = navigator.settings.value.scroll

        val nextSegment = offsetSlot(playbackTranscribeSegment, 1)
        val nextLocators = locatorMap.getOrDefault(nextSegment, listOf<Locator>())

        matchedLocators?.isEmpty()?.let {
            if (!it) {

                var pageLocator = matchedLocators[0]

                if (settingsScroll && pageLocators != null)
                    if (pageLocators.isNotEmpty())
                        if ((pageLocators[0].href == pageLocator.href))
                            pageLocator = pageLocators[0]

                // In scroll mode the follower keeps the spoken word on screen within a chapter,
                // so the navigator only needs to open the chapter when the narrator moves on
                if (settingsScroll && pageLocator.href != navigator.currentLocator.value.href) {
                    Timber.d("Opening ${pageLocator.href} for the spoken text")
                    navigator.go(pageLocator, true)
                    CachedWebView = findAllWebViews(requireView())
                    scrollFollower.reset()
                }

                if (!settingsScroll) {
                    navigator.go(pageLocator, true)
                }

                // Placements are exact now, so the previous slot's text isn't added to the
                // current highlight any more (it used to smooth over loose chunk matches)
                val updatedMatchedLocators = matchedLocators

                if (currentHighlight != updatedMatchedLocators) {
                    currentHighlight = updatedMatchedLocators

                    val random = Random.Default

                    (navigator as? DecorableNavigator)?.applyPageNumberDecorations()
                    navigator.applyDecorations(
                        listOfNotNull(null),
                        "tts"
                    )

                    // Unique ids, so Readium can tell the decorations apart and the auto-scroll
                    // can find the current slot's highlight (see SPOKEN_WORD_POSITION_JS)
                    val decorations: List<Decoration> = updatedMatchedLocators.mapIndexed { index, locator ->
                        Decoration(
                            id = "$CURRENT_HIGHLIGHT_ID_PREFIX$index",
                            locator = locator,
                            style = Decoration.Style.Highlight(
                                tint = lastColour
                            )
                        )
                    }

                    lastColour = if (lastColour == Color.rgb(124, 198, 247)) {
                        Color.rgb(124, 228, 227)
                    } else {
                        Color.rgb(124, 198, 247)
                    }

                    val decorations2: List<Decoration> = nextLocators.distinct().mapIndexed { index, locator ->
                        Decoration(
                            id = "tts-next-$index",
                            locator = locator,
                            style = Decoration.Style.Highlight(
                                tint = lastColour
                            )
                        )
                    }

                    navigator.applyDecorations(
                        decorations + decorations2,
                        "tts"
                    )
                }
            }
        }
    }

    private fun findAllWebViews(
        view: View,
        found: MutableList<android.webkit.WebView> = mutableListOf()
    ): List<android.webkit.WebView> {
        if (view is android.webkit.WebView) {
            found.add(view)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findAllWebViews(view.getChildAt(i), found)
            }
        }
        return found
    }

    // Keeps the spoken word at a fixed height on the page while playing in scroll mode
    private val scrollFollower = ScrollFollower()
    private var followJob: Job? = null
    private var followedWebView: WebView? = null
    // Until then the reader is scrolling by hand, so the follower leaves the page alone
    private var followPausedUntil = 0L

    private fun startFollowing() {
        if (followJob?.isActive == true) return
        scrollFollower.reset()
        followJob = viewLifecycleOwner.lifecycleScope.launch {
            // Measure where the word is a few times a second...
            launch {
                while (isActive) {
                    if (SystemClock.uptimeMillis() >= followPausedUntil) {
                        val found = spokenWordPosition()
                        followedWebView = found?.first
                        val jump = scrollFollower.onPosition(found?.second, readableHeight())
                        if (jump != 0 && found != null) {
                            Timber.d("Follow: word at %.2f of the page, jumping %d px".format(found.second, jump))
                            found.first.scrollBy(0, jump)
                        }
                    }
                    delay(FOLLOW_CHECK_INTERVAL_MS)
                }
            }
            // ...and scroll smoothly at the speed that keeps it at the target
            var last = SystemClock.uptimeMillis()
            while (isActive) {
                delay(FOLLOW_FRAME_MS)
                val now = SystemClock.uptimeMillis()
                val pixels = scrollFollower.pixelsFor(now - last)
                last = now
                if (pixels != 0 && now >= followPausedUntil) followedWebView?.scrollBy(0, pixels)
            }
        }
    }

    private fun stopFollowing() {
        followJob?.cancel()
        followJob = null
        scrollFollower.reset()
    }

    /**
     * Puts the spoken word back at the target height once the page has been laid out again after
     * a rotation or a settings change, also while paused, since the reader didn't move the page.
     */
    private fun recenterSoon() {
        if (!navigator.settings.value.scroll) return
        viewLifecycleOwner.lifecycleScope.launch {
            delay(RECENTER_DELAY_MS)
            CachedWebView = findAllWebViews(requireView())
            val (webView, position) = spokenWordPosition() ?: return@launch
            Timber.d("Recentring: word at %.2f of the page".format(position))
            webView.scrollBy(0, scrollFollower.recenter(position, readableHeight()))
        }
    }

    /** Height of the page above the audio controls, in pixels. */
    private fun readableHeight(): Int {
        val root = view ?: return 0
        return if (binding.audioOverlay.isShown) binding.audioOverlay.top else root.height
    }

    /**
     * The WebView showing the spoken word, and where the word is as a fraction of the readable
     * area (0 is the top, 1 the top of the audio controls). Null if no visible page has it.
     */
    private suspend fun spokenWordPosition(): Pair<WebView, Double>? {
        val readable = readableHeight()
        if (readable <= 0) return null
        val visible = Rect()
        for (webView in CachedWebView) {
            if (!webView.isShown || !webView.getGlobalVisibleRect(visible)) continue
            val result = withTimeoutOrNull(250) {
                suspendCancellableCoroutine<String?> { continuation ->
                    webView.evaluateJavascript(SPOKEN_WORD_POSITION_JS) { continuation.resume(it) }
                }
            }
            val fraction = result?.toDoubleOrNull() ?: continue
            return webView to fraction * webView.height / readable
        }
        // The pages may have been replaced (another chapter opened): look again next time
        CachedWebView = findAllWebViews(view ?: return null)
        return null
    }

    // In landscape the timeline and the buttons sit side by side, so the controls take one row
    // instead of covering a third of the screen
    private fun applyControlsLayout(landscape: Boolean) {
        val controls = binding.controlsLayout
        controls.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (part in listOf(controls.getChildAt(0), binding.controls)) {
            part.layoutParams = if (landscape) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The reader handles rotation itself (see the manifest), so playback and transcription
        // carry on; only the layout changes
        applyControlsLayout(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
        recenterSoon()
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

