/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.example.audebook.data

import androidx.annotation.ColorInt
import java.io.File
import kotlinx.coroutines.flow.Flow
import org.joda.time.DateTime
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import com.example.audebook.data.db.BooksDao
import com.example.audebook.data.model.Book
import com.example.audebook.data.model.AudioBook
import com.example.audebook.data.model.AudioBookTranscript
import com.example.audebook.data.model.Bookmark
import com.example.audebook.data.model.Highlight
import com.example.audebook.utils.extensions.readium.authorName
import timber.log.Timber

class BookRepository(
    private val booksDao: BooksDao
) {
    fun books(): Flow<List<Book>> = booksDao.getAllBooks()

    suspend fun get(id: Long) = booksDao.get(id)

    suspend fun getAudiobook(id: Long) = booksDao.getAudiobook(id)

    suspend fun getAllAudiobooksTranscripts(bookid: Long) = booksDao.getAllAudiobooksTranscripts(bookid)

    fun audiobooks(): Flow<List<AudioBook>> = booksDao.getAllAudiobooks()

    suspend fun saveProgression(locator: Locator, bookId: Long) =
        booksDao.saveProgression(locator.toJSON().toString(), bookId)

    suspend fun saveAudiobookProgression(locator: Locator, bookId: Long) =
        booksDao.saveAudiobookProgression(locator.toJSON().toString(), bookId)

    suspend fun insertBookmark(bookId: Long, publication: Publication, locator: Locator): Long {
        val resource = publication.readingOrder.indexOfFirstWithHref(locator.href)!!
        val bookmark = Bookmark(
            creation = DateTime().toDate().time,
            bookId = bookId,
            resourceIndex = resource.toLong(),
            resourceHref = locator.href.toString(),
            resourceType = locator.mediaType.toString(),
            resourceTitle = locator.title.orEmpty(),
            location = locator.locations.toJSON().toString(),
            locatorText = Locator.Text().toJSON().toString()
        )

        return booksDao.insertBookmark(bookmark)
    }

    fun bookmarksForBook(bookId: Long): Flow<List<Bookmark>> =
        booksDao.getBookmarksForBook(bookId)

    suspend fun deleteBookmark(bookmarkId: Long) = booksDao.deleteBookmark(bookmarkId)

    suspend fun highlightById(id: Long): Highlight? =
        booksDao.getHighlightById(id)

    fun highlightsForBook(bookId: Long): Flow<List<Highlight>> =
        booksDao.getHighlightsForBook(bookId)

    suspend fun addHighlight(
        bookId: Long,
        style: Highlight.Style,
        @ColorInt tint: Int,
        locator: Locator,
        annotation: String
    ): Long =
        booksDao.insertHighlight(Highlight(bookId, style, tint, locator, annotation))

    suspend fun deleteHighlight(id: Long) = booksDao.deleteHighlight(id)

    suspend fun updateHighlightAnnotation(id: Long, annotation: String) {
        booksDao.updateHighlightAnnotation(id, annotation)
    }

    suspend fun updateHighlightStyle(id: Long, style: Highlight.Style, @ColorInt tint: Int) {
        booksDao.updateHighlightStyle(id, style, tint)
    }

    suspend fun insertBook(
        url: Url,
        mediaType: MediaType,
        publication: Publication,
        cover: File
    ): Long {
        val book = Book(
            creation = DateTime().toDate().time,
            title = publication.metadata.title ?: url.filename,
            author = publication.metadata.authorName,
            href = url.toString(),
            identifier = publication.metadata.identifier ?: "",
            mediaType = mediaType,
            progression = "{}",
            cover = cover.path
        )

        Timber.d("FileTest "+ url.toString())

        return booksDao.insertBook(book)
    }

    suspend fun insertAudioBook(
        url: Url,
        mediaType: MediaType,
        publication: Publication,
//        cover: File,
        id: Long
    ): Long {
        val book = AudioBook(
            id = id,
            creation = DateTime().toDate().time,
            title = publication.metadata.title ?: url.filename,
            author = publication.metadata.authorName,
            href = url.toString(),
            identifier = publication.metadata.identifier ?: "",
            mediaType = mediaType,
            progression = "{}",
            cover = "cover.path"
        )

        Timber.d("FileTest "+ url.toString())

        return booksDao.insertAudioBook(book)
    }

    suspend fun insertAudioBookTranscript(

        bookid: Long,
        transcript: String?,
        timestamp: String?

    ): Long {
        val book = AudioBookTranscript(
            bookid = bookid,
            transcript = transcript,
            timestamp = timestamp
        )


        return booksDao.insertAudioBookTranscript(book)
    }

    suspend fun deleteAudiobookTranscript(id: Long) =
        booksDao.deleteAudiobookTranscript(id)

    suspend fun deleteBook(id: Long) =
        booksDao.deleteBook(id)
}
