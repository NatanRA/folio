package com.natan.mediaportal

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

internal data class FreeBook(val id: String, val title: String, val author: String, val audio: Boolean, val download: String?, val tracks: List<Pair<String, String>> = emptyList())
internal object PublicCatalog {
    private fun address(input: String): URI {
        val uri = URI(input.replace(" ", "%20"))
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme == "https" && uri.userInfo == null && (host == "gutendex.com" || host == "librivox.org" || host == "gutenberg.org" || host.endsWith(".gutenberg.org") || host == "archive.org" || host.endsWith(".archive.org"))) { "The catalogue returned an unsupported download address." }
        return uri
    }
    suspend fun fetch(input: String, destination: File, limit: Long) = withContext(Dispatchers.IO) {
        var url = address(input)
        repeat(6) {
            ensureActive()
            val connection = url.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false; connection.connectTimeout = 15000; connection.readTimeout = 30000
                val status = connection.responseCode
                if (status in 300..399) { url = address(url.resolve(connection.getHeaderField("Location")).toString()); return@repeat }
                check(status == 200) { "The catalogue is unavailable ($status). Try again later." }
                connection.inputStream.use { source -> destination.outputStream().use { output ->
                    val buffer = ByteArray(65536); var size = 0L
                    while (true) { ensureActive(); val count = source.read(buffer); if (count < 0) break; size += count; require(size <= limit) { "Download exceeds the size limit." }; output.write(buffer, 0, count) }
                } }
                return@withContext
            } finally { connection.disconnect() }
        }
        error("Too many download redirects.")
    }
    suspend fun search(context: Context, audio: Boolean, query: String, page: Int): List<FreeBook> {
        val term = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (audio) "https://librivox.org/api/feed/audiobooks/?format=json&extended=1&limit=20&offset=${(page - 1) * 20}" + if (term.isEmpty()) "" else "&title=%5E$term"
            else "https://gutendex.com/books/?copyright=false&mime_type=application%2Fepub%2Bzip&search=$term&page=$page"
        val temp = File.createTempFile("discovery", ".json", context.cacheDir)
        try { fetch(url, temp, 8_000_000); return parse(JSONObject(temp.readText()), audio) } finally { temp.delete() }
    }
    fun parse(json: JSONObject, audio: Boolean): List<FreeBook> {
        val array = json.optJSONArray(if (audio) "books" else "results") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val book = array.getJSONObject(index); val authors = book.optJSONArray("authors") ?: JSONArray()
            val author = (0 until authors.length()).joinToString(", ") { authors.getJSONObject(it).let { a -> if (audio) "${a.optString("first_name")} ${a.optString("last_name")}".trim() else a.optString("name") } }
            val tracks = book.optJSONArray("sections") ?: JSONArray()
            val sections = (0 until tracks.length()).map { tracks.getJSONObject(it) }.sortedBy { it.optInt("section_number") }.map { it.optString("title") to it.optString("listen_url") }.filter { it.second.isNotBlank() }
            val download = book.optJSONObject("formats")?.optString("application/epub+zip")?.takeIf { it.isNotEmpty() }
            if (!audio && download == null || audio && sections.isEmpty()) null else FreeBook((if (audio) "lv-" else "pg-") + book.getString("id"), book.getString("title"), author, audio, download, sections)
        }
    }
    suspend fun add(context: Context, book: FreeBook, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val store = LibraryStore(context); val created = mutableListOf<File>()
        try {
            if (!book.audio) {
                val file = File.createTempFile("free-book", ".epub", context.cacheDir); created += file
                fetch(requireNotNull(book.download), file, 268_435_456)
                store.import(Uri.fromFile(file), false, null)
            } else {
                require(book.tracks.size in 1..1000)
                val tracks = JSONArray(); var downloaded = 0L
                book.tracks.forEachIndexed { index, (name, url) ->
                    withContext(Dispatchers.Main) { progress("Downloading chapter ${index + 1} of ${book.tracks.size}") }
                    val file = store.file(UUID.randomUUID().toString() + ".mp3"); created += file
                    fetch(url, file, minOf(268_435_456L, 2_147_483_648L - downloaded)); downloaded += file.length()
                    val duration = MediaMetadataRetriever().use { reader -> reader.setDataSource(file.path); reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0 }
                    require(duration > 0) { "One of the chapters could not be played." }
                    tracks.put(JSONObject().put("file", file.name).put("name", name).put("durationMs", duration))
                }
                val id = UUID.randomUUID().toString(); val manifest = store.file("$id.audiobook.json"); created += manifest
                manifest.writeText(JSONObject().put("tracks", tracks).toString())
                store.save(listOf(Title(id, book.title, book.author, audio = manifest.name, addedAt = System.currentTimeMillis())) + store.all())
                created.clear() // Ownership moves to the durable library only after its index is saved.
            }
        } finally { created.forEach { it.delete() } }
    }
}

@Composable internal fun DiscoverScreen(query: String, changed: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var audio by remember { mutableStateOf(false) }; var submitted by remember { mutableStateOf(query) }
    var page by remember { mutableIntStateOf(1) }; var rows by remember { mutableStateOf<List<FreeBook>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var download by remember { mutableStateOf<Job?>(null) }; var status by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(query) { delay(350); submitted = query.trim(); page = 1 }
    LaunchedEffect(audio, submitted, page, retry) {
        loading = true; message = null; rows = emptyList()
        try { rows = PublicCatalog.search(context, audio, submitted, page) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = "Can’t reach the free book catalogue. Check your connection and try again." }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!audio, { if (download == null) { audio = false; page = 1 } }, label = { Text("E-books") })
            FilterChip(audio, { if (download == null) { audio = true; page = 1 } }, label = { Text("Audiobooks") })
        }
        Text(if (audio) "LibriVox · volunteer-read classics" else "Free e-books from Project Gutenberg", style = MaterialTheme.typography.bodySmall)
        if (audio) Text("Audiobook titles starting with your search", style = MaterialTheme.typography.bodySmall)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, Modifier.padding(vertical = 12.dp)); if (!loading && download == null && rows.isEmpty()) TextButton(onClick = { retry++ }) { Text("Retry") } }
        status?.let { Text(it); TextButton(onClick = { download?.cancel() }) { Text("Cancel download") } }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (!loading && rows.isEmpty() && message == null) item { Text("No matching titles.", Modifier.padding(vertical = 24.dp)) }
            items(rows, key = { it.id }) { book ->
                ListItem(colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent), headlineContent = { Text(book.title) }, supportingContent = { Text(book.author) }, trailingContent = {
                    TextButton(enabled = download == null && book.id !in added, onClick = {
                        download = scope.launch {
                            status = "Downloading…"; message = null
                            try { PublicCatalog.add(context, book) { status = it }; added = added + book.id; changed(); message = "Added to your library." }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = e.message ?: "Download failed. Try again." }
                            finally { download = null; status = null }
                        }
                    }) { Text(if (book.id in added) "Added" else "Download") }
                }); HorizontalDivider()
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = page > 1 && !loading && download == null, onClick = { page-- }) { Text("Previous") }
                Text("Page $page", Modifier.padding(12.dp))
                TextButton(enabled = rows.isNotEmpty() && !loading && download == null, onClick = { page++ }) { Text("Next") }
            }; Text("Keep this screen open while downloading. Availability varies by country.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
