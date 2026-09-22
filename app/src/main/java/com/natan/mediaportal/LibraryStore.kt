package com.natan.mediaportal

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.coverFitting

data class Title(val id: String, val name: String, val author: String = "", val ebook: String? = null, val audio: String? = null,
    val download: String? = null, val coverUrl: String? = null, val description: String = "", val downloadFormat: String = "epub", val telegramSource: String? = null, val addedAt: Long = 0,
    /** The server's audiobook edition, kept apart from the e-book one so a title can offer both. */
    val audioDownload: String? = null, val audioDownloadFormat: String? = null,
    /** The series a catalogue states for this book, which beats guessing from its title. */
    val seriesName: String? = null, val seriesPosition: Double? = null) {
    val streaming get() = audio?.startsWith("telegram://") == true
    val isLocal get() = ebook != null || (audio != null && !streaming)
}

class LibraryStore(private val context: Context) {
    private val catalog = AtomicFile(File(context.filesDir, "library.json"))
    fun all(): List<Title> {
        if (!catalog.baseFile.exists()) return emptyList()
        val array = JSONArray(catalog.openRead().bufferedReader().use { it.readText() })
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Title(item.getString("id"), item.getString("name"), item.optString("author"),
                item.optString("ebook").takeIf { it.isNotBlank() }, item.optString("audio").takeIf { it.isNotBlank() },
                item.optString("download").takeIf { it.isNotBlank() }, item.optString("coverUrl").takeIf { it.isNotBlank() }, item.optString("description"), item.optString("downloadFormat", "epub"), item.optString("telegramSource").takeIf { it.isNotBlank() },
                item.optLong("addedAt").takeIf { it > 0 } ?: addedBackfill(item.optString("ebook"), item.optString("audio")),
                item.optString("audioDownload").takeIf { it.isNotBlank() }, item.optString("audioDownloadFormat").takeIf { it.isNotBlank() },
                item.optString("seriesName").takeIf { it.isNotBlank() }, item.optDouble("seriesPosition").takeIf { !it.isNaN() })
        }
    }
    /** Books saved before timestamps existed take the date their file landed on this device. */
    private fun addedBackfill(vararg names: String) = names.filter { it.isNotBlank() }.map { file(it) }.filter { it.exists() }.minOfOrNull { it.lastModified() } ?: 0L
    fun save(titles: List<Title>) {
        val array = JSONArray()
        titles.forEach { t -> array.put(JSONObject().put("id", t.id).put("name", t.name).put("author", t.author).put("ebook", t.ebook ?: "").put("audio", t.audio ?: "").put("download", t.download ?: "").put("coverUrl", t.coverUrl ?: "").put("description", t.description).put("downloadFormat", t.downloadFormat).put("telegramSource", t.telegramSource ?: "").put("addedAt", t.addedAt).put("audioDownload", t.audioDownload ?: "").put("audioDownloadFormat", t.audioDownloadFormat ?: "").put("seriesName", t.seriesName ?: "")
            // Android's JSON refuses NaN, so a title without a series position leaves the key out; reading treats a missing key as none.
            .apply { t.seriesPosition?.let { put("seriesPosition", it) } }) }
        val stream = catalog.startWrite()
        try { stream.write(array.toString().toByteArray()); catalog.finishWrite(stream) }
        catch (error: Exception) { catalog.failWrite(stream); throw error }
    }
    suspend fun import(uri: Uri, asAudio: Boolean, linkTo: String?, linkedTitle: Title? = null, telegramSource: String? = null): Title = withContext(Dispatchers.IO) {
        if (telegramSource != null) all().find { it.telegramSource == telegramSource && (if (asAudio) it.audio != null && !it.streaming else it.ebook != null) }?.let { return@withContext it }
        val displayName = if (uri.scheme == "file") File(uri.path!!).name else context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Untitled"
        val id = UUID.randomUUID().toString()
        val extension = displayName.substringAfterLast('.', if (asAudio) "mp3" else "epub").lowercase()
        require(asAudio || extension in setOf("epub", "pdf", "cbz")) { "Choose an EPUB, PDF or CBZ e-book." }
        require(!asAudio || extension in setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "wav")) { "This audio file type is not supported." }
        val destination = File(context.filesDir, "$id.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> destination.outputStream().use { input.copyTo(it) } }
                ?: error("This file could not be opened.")
            var name = displayName.substringBeforeLast('.')
            var author = ""
            if (asAudio && !file("${linkTo ?: id}.jpg").exists()) Artwork.fromAudio(destination, file("${linkTo ?: id}.jpg"))
            if (!asAudio && extension in setOf("pdf", "cbz")) {
                FixedPages(destination).use { pages ->
                    val image = pages.render(0, 600)
                    try { file("${linkTo ?: id}.jpg").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) } } finally { image.recycle() }
                }
            } else if (!asAudio) {
                val publication = Books(context).open(destination)
                name = publication.metadata.title ?: name
                author = publication.metadata.authors.joinToString { it.name.toString() }
                publication.coverFitting(android.util.Size(600, 900))?.let { bitmap ->
                    File(context.filesDir, "${linkTo ?: id}.jpg").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                }
                publication.close()
            }
            val current = all().toMutableList()
            val original = current.find { it.id == linkTo } ?: linkedTitle?.takeIf { it.id == linkTo }
            val title = (original ?: Title(id, name, author)).let {
                if (asAudio) it.copy(audio = destination.name, telegramSource = telegramSource ?: it.telegramSource) else it.copy(ebook = destination.name, telegramSource = telegramSource ?: it.telegramSource)
            }.stamped()
            val index = current.indexOfFirst { it.id == title.id }
            if (index >= 0) current[index] = title else current.add(0, title)
            save(current)
            title
        } catch (error: Exception) { destination.delete(); throw error }
    }
    fun file(name: String) = File(context.filesDir, name)
    private fun Title.stamped() = if (addedAt > 0) this else copy(addedAt = System.currentTimeMillis())

    suspend fun keepDownload(title: Title, source: File): Title = withContext(Dispatchers.IO) {
        require(title.downloadFormat in setOf("epub", "pdf", "cbz")) { "Unsupported e-book format." }
        if (title.downloadFormat != "epub") {
            return@withContext import(Uri.fromFile(source), false, title.id, title)
        }
        val publication = Books(context).open(source)
        try {
            publication.coverFitting(android.util.Size(600, 900))?.let { bitmap ->
                file("${title.id}.jpg").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            }
        } finally { publication.close() }
        val destination = file("${title.id}.epub")
        source.copyTo(destination, overwrite = true)
        val current = all()
        val saved = title.copy(ebook = destination.name, audio = current.find { it.id == title.id }?.audio, addedAt = current.find { it.id == title.id }?.addedAt ?: title.addedAt).stamped()
        save(listOf(saved) + current.filterNot { it.id == title.id })
        saved
    }
}

class Books(context: Context) {
    private val http = DefaultHttpClient()
    private val assets = AssetRetriever(context.contentResolver, http)
    private val opener = PublicationOpener(DefaultPublicationParser(context, httpClient = http, assetRetriever = assets, pdfFactory = null))
    suspend fun open(file: File): Publication {
        val asset = assets.retrieve(file.toUrl(isDirectory = false)).getOrElse { error("Cannot open this ebook.") }
        return opener.open(asset, allowUserInteraction = false, onCreatePublication = { container = ReaderResources(container) }).getOrElse { error("This EPUB is unsupported or protected.") }
    }
}
