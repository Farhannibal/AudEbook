/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.example.audebook

import android.content.Context
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/**
 * Holds the shared Readium objects and services used by the app.
 */
class Readium(context: Context) {

    val httpClient =
        DefaultHttpClient()

    val assetRetriever =
        AssetRetriever(context.contentResolver, httpClient)

    /**
     * The PublicationFactory is used to open publications.
     */
    val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context,
            assetRetriever = assetRetriever,
            httpClient = httpClient,
            // Only required if you want to support PDF files using the PDFium adapter.
            pdfFactory = PdfiumDocumentFactory(context)
        ),
        contentProtections = emptyList()
    )
}

@OptIn(ExperimentalReadiumApi::class)
val FontFamily.Companion.LITERATA: FontFamily get() = FontFamily("Literata")
