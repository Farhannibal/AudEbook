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
import com.example.audebook.reader.preferences.UserPreferencesViewModel
import com.example.audebook.search.SearchFragment
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.shared.publication.services.content.content
import timber.log.Timber

import info.debatty.java.stringsimilarity.JaroWinkler

@OptIn(ExperimentalReadiumApi::class)
class EpubReaderFragment : VisualReaderFragment() {

    override lateinit var navigator: EpubNavigatorFragment

    private lateinit var menuSearch: MenuItem
    lateinit var menuSearchView: SearchView

    private lateinit var menuDebug: MenuItem

    private var isSearchViewIconified = true

    private lateinit var locators: MutableList<Locator>
    private lateinit var locatorMap: MutableMap<Locator, Boolean>

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            isSearchViewIconified = savedInstanceState.getBoolean(IS_SEARCH_VIEW_ICONIFIED)
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

//                            navigator.applyDecorations(
//                                listOf(Decoration(
////                                    locator = location.utteranceLocator,
//                                    style = Decoration.Style.Highlight(tint = Color.RED)
//                                )),
//                                "highlight"
//                            )

                            viewLifecycleOwner.lifecycleScope.launch {
                                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    // Display page number labels if the book contains a `page-list` navigation document.
                                    (navigator as? DecorableNavigator)?.applyPageNumberDecorations()
                                    val start = (navigator as? VisualNavigator)?.firstVisibleElementLocator()
                                    val content = publication.content(start)
//                        val start2 = (navigator as? VisualNavigator)?.firstVisibleElementLocator()
//
//
//
//                                    TODODODOODODODODODOODODO
//
                                    val publicati = publication.content(start)
//                                    val wholeText = content?.text()
//                                    Timber.d(wholeText.toString())



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

                                    var i = 0;

                                    val iterator = content?.iterator()
                                    locators.clear()
                                    while (iterator!!.hasNext() && i <= 10) {
                                        val element = iterator.next()
//                                        Timber.d(element.locator.text.highlight)
                                        locators.add(element.locator)
                                        i=i+1
                                    }
                                }
                            }

                            Timber.d(locators.toString())


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
