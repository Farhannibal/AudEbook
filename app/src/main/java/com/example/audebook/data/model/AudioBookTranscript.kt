/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.example.audebook.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

@Entity(tableName = AudioBookTranscript.TABLE_NAME)
data class AudioBookTranscript(
    @PrimaryKey
    @ColumnInfo(name = ID)
    var id: Long? = null,
    @ColumnInfo(name = TRANSCRIPTS)
    val transcripts: String?
) {

//    constructor(
//        id: Long? = null,
//        transcripts: String?
//    ) : this(
//        id = id,
//        transcripts = transcripts
//    )


    companion object {

        const val TABLE_NAME = "audiobooktranscript"
        const val ID = "id"
        const val TRANSCRIPTS = "transcripts"
    }
}
