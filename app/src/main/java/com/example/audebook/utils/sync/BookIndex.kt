package com.example.audebook.utils.sync

/**
 * Word-level index of a book's text, used to find where a transcript is in the book.
 *
 * Chapters are added one at a time (the current chapter first, so Anchor is ready quickly) and
 * can be looked up from any thread while more are being added. Each chapter keeps its words, where
 * each word sits in its paragraph, and a sorted table of every three-word sequence.
 *
 * [E] is whatever identifies a paragraph: a Readium Locator in the app, anything in tests.
 */
class BookIndex<E> {

    /** One paragraph (or other text element) of a chapter, with its text. */
    class Element<E>(val payload: E, val text: String)

    /** Part of one paragraph covered by a word range, for building a highlight. */
    class Span<E>(val element: Element<E>, val charStart: Int, val charEnd: Int)

    class Chapter<E> internal constructor(
        val number: Int,
        val elements: List<Element<E>>,
        // For each word: which element it is in, and where in that element's text
        private val wordElement: IntArray,
        private val wordCharStart: IntArray,
        private val wordCharEnd: IntArray,
        // Each three-word sequence as (key << 32 | start word), sorted, so one primitive sort
        // orders the table by key and the starts of each key come out ascending
        private val trigrams: LongArray
    ) {
        val wordCount: Int get() = wordElement.size

        /** Start positions of [key] in this chapter, ascending. */
        fun positionsOf(key: Int): IntArray {
            var low = lowerBound(key.toLong() shl 32)
            val from = low
            while (low < trigrams.size && (trigrams[low] shr 32).toInt() == key) low++
            return IntArray(low - from) { (trigrams[from + it] and 0xFFFFFFFFL).toInt() }
        }

        /** The first word of element [elementIndex], or null if that element has no words. */
        fun firstWordOf(elementIndex: Int): Int? {
            val word = wordElement.indexOfFirst { it >= elementIndex }
            return if (word >= 0) word else null
        }

        /** The parts of each element covered by words [fromWord]..[toWord], in order. */
        fun spans(fromWord: Int, toWord: Int): List<Span<E>> {
            if (wordCount == 0) return emptyList()
            val from = fromWord.coerceIn(0, wordCount - 1)
            val to = toWord.coerceIn(from, wordCount - 1)
            val spans = mutableListOf<Span<E>>()
            var word = from
            while (word <= to) {
                val element = wordElement[word]
                var last = word
                while (last + 1 <= to && wordElement[last + 1] == element) last++
                spans += Span(elements[element], wordCharStart[word], wordCharEnd[last])
                word = last + 1
            }
            return spans
        }

        private fun lowerBound(value: Long): Int {
            var low = 0
            var high = trigrams.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (trigrams[mid] < value) low = mid + 1 else high = mid
            }
            return low
        }
    }

    @Volatile
    private var chapters: Map<Int, Chapter<E>> = emptyMap()

    /** Chapters indexed so far, keyed by reading-order number. */
    fun chapters(): Map<Int, Chapter<E>> = chapters

    fun chapter(number: Int): Chapter<E>? = chapters[number]

    fun isIndexed(number: Int): Boolean = chapters.containsKey(number)

    /** Indexes one chapter. Safe to call while other threads are searching. */
    fun addChapter(number: Int, elements: List<Element<E>>): Chapter<E> {
        // Primitive arrays throughout: boxed lists and comparator sorts are slow on Android
        val tokens = elements.map { Words.tokenize(it.text) }
        val wordCount = tokens.sumOf { it.size }
        val wordElement = IntArray(wordCount)
        val wordStart = IntArray(wordCount)
        val wordEnd = IntArray(wordCount)
        val hashes = IntArray(wordCount)
        var word = 0
        tokens.forEachIndexed { index, elementTokens ->
            for (token in elementTokens) {
                wordElement[word] = index
                wordStart[word] = token.charStart
                wordEnd[word] = token.charEnd
                hashes[word] = token.word.hashCode()
                word++
            }
        }

        val trigrams = LongArray((wordCount - 2).coerceAtLeast(0)) { start ->
            val key = Words.trigramKey(hashes[start], hashes[start + 1], hashes[start + 2])
            (key.toLong() shl 32) or start.toLong()
        }
        trigrams.sort()
        val chapter = Chapter(
            number = number,
            elements = elements,
            wordElement = wordElement,
            wordCharStart = wordStart,
            wordCharEnd = wordEnd,
            trigrams = trigrams
        )
        synchronized(this) { chapters = chapters + (number to chapter) }
        return chapter
    }

    /**
     * Moves [position] forward (or back, if negative) by [words], continuing into neighbouring
     * chapters when they are indexed. Stops at the edge of what is indexed.
     */
    fun advance(position: BookPosition, words: Int): BookPosition {
        var chapterNumber = position.chapter
        var word = position.word + words
        while (true) {
            val chapter = chapters[chapterNumber] ?: return position
            if (word < 0) {
                val previous = chapters[chapterNumber - 1] ?: return BookPosition(chapterNumber, 0)
                word += previous.wordCount
                chapterNumber--
            } else if (word >= chapter.wordCount) {
                val next = chapters[chapterNumber + 1]
                    ?: return BookPosition(chapterNumber, (chapter.wordCount - 1).coerceAtLeast(0))
                word -= chapter.wordCount
                chapterNumber++
                if (next.wordCount == 0) continue
            } else {
                return BookPosition(chapterNumber, word)
            }
        }
    }

    /**
     * Words from [from] to [to] (negative if [to] is earlier), or null when an unindexed chapter
     * lies between them.
     */
    fun distance(from: BookPosition, to: BookPosition): Int? {
        if (from.chapter == to.chapter) return to.word - from.word
        if (from.chapter > to.chapter) return distance(to, from)?.let { -it }
        var total = (chapters[from.chapter] ?: return null).wordCount - from.word
        for (number in from.chapter + 1 until to.chapter) {
            total += (chapters[number] ?: return null).wordCount
        }
        if (!chapters.containsKey(to.chapter)) return null
        return total + to.word
    }
}

/** A word in the book: chapter by reading-order number, then word number within it. */
data class BookPosition(val chapter: Int, val word: Int) : Comparable<BookPosition> {
    override fun compareTo(other: BookPosition): Int =
        compareValuesBy(this, other, { it.chapter }, { it.word })
}

/** Splits text into normalized words, the same way for the book and for transcripts. */
object Words {

    class Token(val word: String, val charStart: Int, val charEnd: Int)

    /**
     * Words are runs of letters and digits, with apostrophes allowed inside a word ("don't",
     * "Kaladin’s"). Each word is lowercased with its apostrophes removed. A hand-written scan,
     * because a regex was the slowest part of indexing on Android.
     */
    fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>(text.length / 5)
        val word = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (!Character.isLetterOrDigit(text[i])) {
                i++
                continue
            }
            val start = i
            word.setLength(0)
            while (i < text.length) {
                val c = text[i]
                if (Character.isLetterOrDigit(c)) {
                    word.append(c.lowercaseChar())
                    i++
                } else if (isApostrophe(c) && i + 1 < text.length && Character.isLetterOrDigit(text[i + 1])) {
                    i++
                } else {
                    break
                }
            }
            tokens += Token(word.toString(), start, i)
        }
        return tokens
    }

    fun normalize(text: String): List<String> = tokenize(text).map { it.word }

    private fun isApostrophe(c: Char) = c == '\'' || c == '’'

    /**
     * A 32-bit key for three consecutive words. Two different sequences can very rarely share a
     * key; that only adds a stray vote, which the voting tolerates.
     */
    fun trigramKey(first: Int, second: Int, third: Int): Int {
        var key = first.toLong()
        key = key * 1_000_003L + second
        key = key * 1_000_003L + third
        return (key xor (key ushr 32)).toInt()
    }
}
