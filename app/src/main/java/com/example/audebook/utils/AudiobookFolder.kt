package com.example.audebook.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.content.ContentResource
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.streamer.parser.audio.AudioLocatorService
import timber.log.Timber

/**
 * An audiobook made of the audio files in a folder the user picked, played in file name order
 * ("Chapter 2" before "Chapter 10"). Anything else in the folder, such as the EPUB or a cover
 * image, is left out, so the book and its audio can be kept together.
 *
 * The files are read in place through the folder's document tree, never copied.
 */
object AudiobookFolder {

    /** How an audiobook folder is recorded in the database, in place of a file's media type. */
    val MEDIA_TYPE: MediaType = MediaType("inode/directory")!!

    class Track(val name: String, val uri: Uri, val mediaType: MediaType)

    /** The folder's audio files in play order. */
    fun tracks(context: Context, treeUri: Uri): List<Track> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val tracks = mutableListOf<Track>()
        try {
            context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    val mediaType = audioMediaType(name, cursor.getString(2)) ?: continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                    tracks += Track(name, uri, mediaType)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Cannot list the audiobook folder $treeUri")
        }
        return tracks.sortedWith(compareBy(AudiobookTimeline.naturalOrder) { it.name })
    }

    /** The folder as an audiobook publication, or null if it has no audio files. */
    fun open(context: Context, treeUri: Uri, title: String?): Publication? {
        val tracks = tracks(context, treeUri)
        if (tracks.isEmpty()) return null
        Timber.d("Audiobook folder: ${tracks.size} tracks, ${tracks.first().name} to ${tracks.last().name}")

        val entries: Map<Url, Track> = tracks.associateBy { Url.fromDecodedPath(it.name)!! }
        val manifest = Manifest(
            metadata = Metadata(
                conformsTo = setOf(Publication.Profile.AUDIOBOOK),
                localizedTitle = title?.let { LocalizedString(it) }
            ),
            readingOrder = entries.map { (url, track) -> Link(href = url, mediaType = track.mediaType) }
        )
        return Publication(
            manifest = manifest,
            container = TracksContainer(context, entries),
            servicesBuilder = Publication.ServicesBuilder(locator = AudioLocatorService.createFactory())
        )
    }

    /** The folder's name, for the audiobook's title. */
    fun name(context: Context, treeUri: Uri): String? = try {
        val folder = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        context.contentResolver.query(
            folder, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    // Some providers report audio files by extension only, as application/octet-stream
    private fun audioMediaType(name: String, mimeType: String?): MediaType? {
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) return null
        mimeType?.takeIf { it.startsWith("audio/") }?.let { MediaType(it) }?.let { return it }
        return AUDIO_EXTENSIONS[name.substringAfterLast('.', "").lowercase()]?.let { MediaType(it) }
    }

    private val AUDIO_EXTENSIONS = mapOf(
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "m4b" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "flac" to "audio/flac",
        "wav" to "audio/wav"
    )

    /** Serves each track from its document; Readium closes each resource after reading. */
    private class TracksContainer(
        private val context: Context,
        private val tracks: Map<Url, Track>
    ) : Container<Resource> {

        override val entries: Set<Url> = tracks.keys

        override fun get(url: Url): Resource? {
            val href = url.removeFragment().removeQuery()
            val track = tracks.entries.firstOrNull { it.key.isEquivalent(href) }?.value ?: return null
            return ContentResource(track.uri, context.contentResolver)
        }

        override fun close() {}
    }
}
