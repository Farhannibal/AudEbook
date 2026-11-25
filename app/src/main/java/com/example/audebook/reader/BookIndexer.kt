package com.example.audebook.reader

import com.example.audebook.utils.sync.BookIndex
import com.example.audebook.utils.sync.BookPosition
import com.example.audebook.utils.sync.SyncTracker
import com.example.audebook.utils.sync.Words
import java.io.ByteArrayInputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.mediatype.MediaType
import timber.log.Timber

/**
 * Fills a [BookIndex] from a Readium publication, one reading-order item (chapter) at a time, and
 * converts between Readium locators and book positions.
 *
 * Chapters are read with a plain Jsoup parse rather than Readium's content iterator, which was
 * 6-10x slower on books with large chapter files. Paragraph text is taken exactly as the browser
 * sees it (the text nodes' raw text), so Readium's page script can find highlights by their text.
 */
class BookIndexer(private val publication: Publication) {

    val index = BookIndex<Locator>()

    val chapterCount: Int get() = publication.readingOrder.size

    /** Indexes chapter [number] if it isn't already. Returns how long it took, in ms. */
    suspend fun indexChapter(number: Int): Long {
        if (index.isIndexed(number)) return 0
        val link = publication.readingOrder.getOrNull(number) ?: return 0
        val startedAt = System.currentTimeMillis()

        val resource = publication.get(link)
        val bytes = resource?.read()?.getOrNull()
        resource?.close()
        val mediaType = link.mediaType ?: MediaType.XHTML
        val texts = if (bytes != null && mediaType.isHtml) paragraphs(bytes, mediaType) else emptyList()

        // Progression through the chapter, by characters, for the "page is far away" check
        val totalChars = texts.sumOf { it.length }.coerceAtLeast(1)
        var offset = 0
        val elements = texts.map { text ->
            val locator = Locator(
                href = link.url(),
                mediaType = mediaType,
                locations = Locator.Locations(progression = offset.toDouble() / totalChars),
                text = Locator.Text(highlight = text)
            )
            offset += text.length
            BookIndex.Element(locator, text)
        }
        val extractedAt = System.currentTimeMillis()
        val chapter = index.addChapter(number, elements)
        val elapsed = System.currentTimeMillis() - startedAt
        Timber.d(
            "Indexed chapter $number: ${chapter.wordCount} words in $elapsed ms " +
                "(text ${extractedAt - startedAt} ms, index ${elapsed - (extractedAt - startedAt)} ms)"
        )
        return elapsed
    }

    /** Indexes chapter [number] first, then works outwards until the whole book is indexed. */
    suspend fun indexOutwardsFrom(number: Int) {
        indexChapter(number)
        for (distance in 1 until chapterCount) {
            indexChapter(number + distance)
            indexChapter(number - distance)
        }
    }

    /** The reading-order number of the chapter [locator] is in, or null. */
    fun chapterOf(locator: Locator): Int? {
        val href = locator.href.toString().substringBefore('#')
        return publication.readingOrder
            .indexOfFirst { it.url().toString().substringBefore('#') == href }
            .takeIf { it >= 0 }
    }

    /**
     * The book position of the paragraph [locator] points at, e.g. the first visible one for
     * Anchor. Readium reports that paragraph's text, so it is matched by its first few words, then
     * by progression.
     */
    fun positionOf(locator: Locator): BookPosition? {
        val number = chapterOf(locator) ?: return null
        val chapter = index.chapter(number) ?: return null
        val elements = chapter.elements

        val wanted = Words.normalize(locator.text.highlight.orEmpty()).take(MATCH_WORDS)
        val progression = locator.locations.progression
        var elementIndex = if (wanted.isNotEmpty()) {
            val matching = elements.indices.filter { i ->
                val words = Words.normalize(elements[i].text).take(MATCH_WORDS)
                words.isNotEmpty() && (words == wanted.take(words.size) || wanted == words.take(wanted.size))
            }
            // A short paragraph can repeat in a chapter; take the copy nearest the reported position
            if (progression != null && matching.size > 1) {
                matching.minBy { kotlin.math.abs((elements[it].payload.locations.progression ?: 0.0) - progression) }
            } else {
                matching.firstOrNull() ?: -1
            }
        } else -1
        if (elementIndex < 0) {
            progression ?: return BookPosition(number, 0)
            elementIndex = elements.indices.minByOrNull {
                kotlin.math.abs((elements[it].payload.locations.progression ?: 0.0) - progression)
            } ?: return BookPosition(number, 0)
        }
        val word = chapter.firstWordOf(elementIndex) ?: return BookPosition(number, 0)
        return BookPosition(number, word)
    }

    /** Readium locators covering a placement's words, one per paragraph, for highlighting. */
    fun locatorsFor(placement: SyncTracker.Placement): List<Locator> {
        val chapter = index.chapter(placement.start.chapter) ?: return emptyList()
        val elements = chapter.elements
        return chapter.spans(placement.start.word, placement.endWord).map { span ->
            val text = span.element.text
            val element = elements.indexOf(span.element)
            span.element.payload.copy(
                text = Locator.Text(
                    before = contextBefore(elements, element, span.charStart),
                    highlight = text.substring(span.charStart, span.charEnd),
                    after = contextAfter(elements, element, span.charEnd)
                )
            )
        }
    }

    // Readium's page script finds a highlight by its text and the text around it, so the context
    // runs on into the neighbouring paragraphs: a short paragraph that repeats in the chapter
    // ("Glurp, glurp!") has nothing else to tell its copies apart. Paragraphs are separated by a
    // line break, as they are in the page's text.
    private fun contextBefore(elements: List<BookIndex.Element<Locator>>, element: Int, charStart: Int): String? {
        val context = StringBuilder(elements[element].text.substring(0, charStart))
        var previous = element - 1
        while (context.length < CONTEXT_CHARS && previous >= 0) {
            context.insert(0, elements[previous].text + "\n")
            previous--
        }
        return context.takeLast(CONTEXT_CHARS).toString().takeIf { it.isNotEmpty() }
    }

    private fun contextAfter(elements: List<BookIndex.Element<Locator>>, element: Int, charEnd: Int): String? {
        val context = StringBuilder(elements[element].text.substring(charEnd))
        var next = element + 1
        while (context.length < CONTEXT_CHARS && next < elements.size) {
            context.append("\n").append(elements[next].text)
            next++
        }
        return context.take(CONTEXT_CHARS).toString().takeIf { it.isNotEmpty() }
    }

    /** The text of each paragraph in an HTML or XHTML chapter, in reading order. */
    private fun paragraphs(bytes: ByteArray, mediaType: MediaType): List<String> {
        // EPUB chapters are XHTML, which the WebView parses as XML; parse them the same way
        val parser = if (mediaType.matches(MediaType.XHTML)) Parser.xmlParser() else Parser.htmlParser()
        val document = Jsoup.parse(ByteArrayInputStream(bytes), null, "", parser)
        val body = document.getElementsByTag("body").firstOrNull() ?: document
        val out = mutableListOf<String>()
        collect(body, out)
        return out
    }

    /**
     * Adds the innermost blocks under [element] to [out]. Text sitting directly between blocks
     * (e.g. in a div that also holds paragraphs) becomes its own entry.
     */
    private fun collect(element: Element, out: MutableList<String>) {
        if (element.children().none { it.normalName() in BLOCK_TAGS }) {
            textContent(element).takeIf { it.isNotBlank() }?.let { out += it }
            return
        }
        for (child in element.childNodes()) {
            when (child) {
                is Element -> if (child.normalName() !in SKIPPED_TAGS) collect(child, out)
                is TextNode -> child.wholeText.takeIf { it.isNotBlank() }?.let { out += it }
            }
        }
    }

    /** The element's text as the browser's textContent reports it: every text node, unaltered. */
    private fun textContent(node: Node): String {
        val sb = StringBuilder()
        fun append(n: Node) {
            when (n) {
                is TextNode -> sb.append(n.wholeText)
                is Element -> if (n.normalName() !in SKIPPED_TAGS) n.childNodes().forEach(::append)
            }
        }
        append(node)
        return sb.toString()
    }

    companion object {
        // Text before and after a highlight, so Readium can find it even when it repeats
        private const val CONTEXT_CHARS = 50

        // Words compared when matching Anchor's paragraph to an indexed one
        private const val MATCH_WORDS = 8

        private val BLOCK_TAGS = setOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "blockquote", "section",
            "article", "header", "footer", "aside", "nav", "main", "figure", "figcaption", "table",
            "thead", "tbody", "tfoot", "tr", "td", "th", "dl", "dt", "dd", "pre", "hr"
        )
        private val SKIPPED_TAGS = setOf("script", "style", "head")
    }
}
