/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.example.audebook.data.db

import androidx.annotation.ColorInt
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.example.audebook.data.model.Book
import com.example.audebook.data.model.AudioBook
import com.example.audebook.data.model.Bookmark
import com.example.audebook.data.model.Highlight
import com.example.audebook.data.model.AudioBookTranscript

@Dao
interface BooksDao {

    /**
     * Inserts a book
     * @param book The book to insert
     * @return ID of the book that was added (primary key)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book): Long

    /**
     * Inserts a book
     * @param book The book to insert
     * @return ID of the book that was added (primary key)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudioBook(book: AudioBook): Long

    /**
     * Inserts a audiobookTranscript
     * @param book The book to insert
     * @return ID of the book that was added (primary key)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudioBookTranscript(book: AudioBookTranscript): Long

    /**
     * Deletes a book
     * @param bookId The ID of the book
     */
    @Query("DELETE FROM " + Book.TABLE_NAME + " WHERE " + Book.ID + " = :bookId")
    suspend fun deleteBook(bookId: Long)

    /**
     * Retrieve a book from its ID.
     */
    @Query("SELECT * FROM " + Book.TABLE_NAME + " WHERE " + Book.ID + " = :id")
    suspend fun get(id: Long): Book?

    /**
     * Retrieve an audiobook from its ID.
     */
    @Query("SELECT * FROM " + AudioBook.TABLE_NAME + " WHERE " + AudioBook.ID + " = :id")
    suspend fun getAudiobook(id: Long): AudioBook?

    /**
     * Retrieve all audiobook transcript
     * @return List of books as Flow
     */
    @Query("SELECT * FROM " + AudioBookTranscript.TABLE_NAME + " WHERE " + AudioBookTranscript.BOOKID + " = :bookid")
    fun getAllAudiobooksTranscripts(bookid: Long): Flow<List<AudioBookTranscript>>

    /**
     * Retrieve all books
     * @return List of books as Flow
     */
    @Query("SELECT * FROM " + Book.TABLE_NAME + " ORDER BY " + Book.CREATION_DATE + " desc")
    fun getAllBooks(): Flow<List<Book>>

    /**
     * Retrieve all books
     * @return List of books as Flow
     */
    @Query("SELECT * FROM " + AudioBook.TABLE_NAME + " ORDER BY " + AudioBook.CREATION_DATE + " desc")
    fun getAllAudiobooks(): Flow<List<AudioBook>>

    /**
     * Retrieve all bookmarks for a specific book
     * @param bookId The ID of the book
     * @return List of bookmarks for the book as Flow
     */
    @Query("SELECT * FROM " + Bookmark.TABLE_NAME + " WHERE " + Bookmark.BOOK_ID + " = :bookId")
    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>>

    /**
     * Retrieve all highlights for a specific book
     */
    @Query(
        "SELECT * FROM ${Highlight.TABLE_NAME} WHERE ${Highlight.BOOK_ID} = :bookId ORDER BY ${Highlight.TOTAL_PROGRESSION} ASC"
    )
    fun getHighlightsForBook(bookId: Long): Flow<List<Highlight>>

    /**
     * Retrieves the highlight with the given ID.
     */
    @Query("SELECT * FROM ${Highlight.TABLE_NAME} WHERE ${Highlight.ID} = :highlightId")
    suspend fun getHighlightById(highlightId: Long): Highlight?

    /**
     * Inserts a bookmark
     * @param bookmark The bookmark to insert
     * @return The ID of the bookmark that was added (primary key)
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookmark(bookmark: Bookmark): Long

    /**
     * Inserts a highlight
     * @param highlight The highlight to insert
     * @return The ID of the highlight that was added (primary key)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlight(highlight: Highlight): Long

    /**
     * Updates a highlight's annotation.
     */
    @Query(
        "UPDATE ${Highlight.TABLE_NAME} SET ${Highlight.ANNOTATION} = :annotation WHERE ${Highlight.ID} = :id"
    )
    suspend fun updateHighlightAnnotation(id: Long, annotation: String)

    /**
     * Updates a highlight's tint and style.
     */
    @Query(
        "UPDATE ${Highlight.TABLE_NAME} SET ${Highlight.TINT} = :tint, ${Highlight.STYLE} = :style WHERE ${Highlight.ID} = :id"
    )
    suspend fun updateHighlightStyle(id: Long, style: Highlight.Style, @ColorInt tint: Int)

    /**
     * Deletes a bookmark
     */
    @Query("DELETE FROM " + Bookmark.TABLE_NAME + " WHERE " + Bookmark.ID + " = :id")
    suspend fun deleteBookmark(id: Long)

    /**
     * Deletes the highlight with given id.
     */
    @Query("DELETE FROM ${Highlight.TABLE_NAME} WHERE ${Highlight.ID} = :id")
    suspend fun deleteHighlight(id: Long)

    /**
     * Saves book progression
     * @param locator Location of the book
     * @param id The book to update
     */
    @Query(
        "UPDATE " + Book.TABLE_NAME + " SET " + Book.PROGRESSION + " = :locator WHERE " + Book.ID + "= :id"
    )
    suspend fun saveProgression(locator: String, id: Long)

    /**
     * Saves audiobook progression
     * @param locator Location of the book
     * @param id The book to update
     */
    @Query(
        "UPDATE " + AudioBook.TABLE_NAME + " SET " + AudioBook.PROGRESSION + " = :locator WHERE " + AudioBook.ID + "= :id"
    )
    suspend fun saveAudiobookProgression(locator: String, id: Long)

    @Query(
        "UPDATE " + AudioBookTranscript.TABLE_NAME + " SET " + AudioBookTranscript.TRANSCRIPT + " = :locator WHERE " + AudioBookTranscript.ID + "= :id"
    )
    suspend fun saveAudiobookTranscript(locator: String, id: Long)

    /**
     * Deletes a bookmark
     */
    @Query("DELETE FROM " + AudioBookTranscript.TABLE_NAME + " WHERE " + AudioBookTranscript.BOOKID + " = :id")
    suspend fun deleteAudiobookTranscript(id: Long)
}
