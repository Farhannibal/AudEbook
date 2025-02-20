/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.example.audebook.reader

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.appcompat.widget.SearchView
import androidx.core.os.BundleCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.FragmentResultListener
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import com.example.audebook.domain.PublicationError
import com.example.audebook.domain.PublicationError.Companion.invoke
import com.example.audebook.reader.preferences.ExoPlayerPreferencesManagerFactory
import org.json.JSONObject
import org.readium.adapter.exoplayer.audio.ExoPlayerEngineProvider
import org.readium.navigator.media.audio.AudioNavigatorFactory
import org.readium.navigator.media.audio.AudioNavigatorFactory.Companion.invoke
import org.readium.r2.shared.publication.allAreHtml
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.getOrElse

@OptIn(ExperimentalReadiumApi::class)
class EpubReaderFragment : VisualReaderFragment() {

    override lateinit var navigator: EpubNavigatorFragment

    private lateinit var readium: Readium

    lateinit var audioNavigator: TimeBasedMediaNavigator<*, *, *>
    lateinit var audioPublication: Publication

    private lateinit var menuSearch: MenuItem
    lateinit var menuSearchView: SearchView

    private lateinit var menuDebug: MenuItem

    private var isSearchViewIconified = true

    private lateinit var locators: MutableList<Locator>
    private lateinit var locatorMap: MutableMap<Locator, Boolean>

    private lateinit var application: Application

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            isSearchViewIconified = savedInstanceState.getBoolean(IS_SEARCH_VIEW_ICONIFIED)
        }

        application = requireActivity().application as Application
        readium = application.readium
//        coroutineQueue.await { audioPublication = application.readerRepository.open(1) }
//        val asset = readium.assetRetriever.retrieve(
//            book.url,
//            book.mediaType
//        )
//            .getOrElse {
//            return Try.failure(
//                OpeningError.PublicationError(
//                    PublicationError(it)
//                )
//            )
//        }



//        val audioPublication = readium.publicationOpener.open(
//            asset,
//            allowUserInteraction = true
//        )
//            .getOrElse {
//            return Try.failure(
//                OpeningError.PublicationError(
//                    PublicationError(it)
//                )
//            )
//        }
//        audioNavigator


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

                                    val book = checkNotNull(application.bookRepository.get(2))
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

                                    val readerInitData = when {
                                        (audioPublication as Publication).conformsTo(Publication.Profile.AUDIOBOOK) ->
                                            openAudio(2, audioPublication, initialLocator)
                                        else ->
                                            Try.failure(
                                                OpeningError.CannotRender(
                                                    DebugError("No navigator supports this publication.")
                                                )
                                            )
                                    }

                                    Timber.d(readerInitData.toString())
//
//
//
//                                    TODODODOODODODODODOODODO
//
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

    @OptIn(ExperimentalReadiumApi::class)
    private suspend fun openAudio(
        bookId: Long,
        publication: Publication,
        initialLocator: Locator?
    ): Try<MediaReaderInitData, OpeningError> {
        val preferencesManager = ExoPlayerPreferencesManagerFactory(preferencesDataStore)
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
        return Try.success(initData)
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

