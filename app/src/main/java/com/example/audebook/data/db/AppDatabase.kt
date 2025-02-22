/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.example.audebook.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.audebook.data.model.*
import com.example.audebook.data.model.Book
import com.example.audebook.data.model.AudioBook
import com.example.audebook.data.model.Bookmark
import com.example.audebook.data.model.Catalog
import com.example.audebook.data.model.Highlight

@Database(
    entities = [Book::class,AudioBook::class, Bookmark::class, Highlight::class, Catalog::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(
    HighlightConverters::class
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun booksDao(): BooksDao

    abstract fun catalogDao(): CatalogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Perform necessary migration steps here
                // For example, adding a new table:
                database.execSQL("""
            CREATE TABLE IF NOT EXISTS `audiobooks` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `creation_date` INTEGER DEFAULT CURRENT_TIMESTAMP,
                `href` TEXT NOT NULL,
                `title` TEXT,
                `author` TEXT,
                `identifier` TEXT NOT NULL,
                `progression` TEXT,
                `media_type` TEXT NOT NULL,
                `cover` TEXT NOT NULL
            )
        """.trimIndent())
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            val tempInstance = INSTANCE
            if (tempInstance != null) {
                return tempInstance
            }
            synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "database"
                ).addMigrations(MIGRATION_1_2) // Apply the migration
                .build()
                INSTANCE = instance
                return instance
            }
        }
    }
}
