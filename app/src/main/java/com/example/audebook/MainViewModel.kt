/*
 * Copyright 2023 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.example.audebook

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import com.example.audebook.domain.Bookshelf
import com.example.audebook.domain.ImportError
import com.example.audebook.utils.EventChannel

class MainViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val app =
        getApplication<com.example.audebook.Application>()

    val channel: EventChannel<Event> =
        EventChannel(Channel(Channel.UNLIMITED), viewModelScope)

    init {
        app.bookshelf.channel.receiveAsFlow()
            .onEach { sendImportFeedback(it) }
            .launchIn(viewModelScope)
    }

    private fun sendImportFeedback(event: Bookshelf.Event) {
        when (event) {
            is Bookshelf.Event.ImportPublicationError -> {
                channel.send(Event.ImportPublicationError(event.error))
            }
            Bookshelf.Event.ImportPublicationSuccess -> {
                channel.send(Event.ImportPublicationSuccess)
            }
        }
    }

    sealed class Event {

        object ImportPublicationSuccess :
            Event()

        class ImportPublicationError(
            val error: ImportError
        ) : Event()
    }
}
