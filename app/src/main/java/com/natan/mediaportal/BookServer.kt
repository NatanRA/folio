package com.natan.mediaportal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.xml.parsers.DocumentBuilderFactory

data class ServerAccount(val url: String, val user: String, val password: String) {
    override fun toString() = "ServerAccount(url=$url, user=$user, password=<redacted>)"
}
data class CatalogPage(val books: List<Title>, val next: String?, val catalog: String?)

object Opds {
    /**
     * Audiobook editions a catalogue may offer. Only formats Media3 can actually play are listed:
     * offering a download the player cannot open would be worse than not offering it.
     */
    fun audioFormat(mime: String): String? = when (mime.substringBefore(';').trim().lowercase()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/x-m4b", "audio/m4b" -> "m4b"
        "audio/x-m4a", "audio/m4a" -> "m4a"
        "audio/aac", "audio/aacp" -> "aac"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        else -> null
    }

    /** Chaptered containers first, so a book offering both arrives with its chapters intact. */
    val audioPreference = listOf("m4b", "m4a", "mp3", "aac", "flac", "ogg", "wav")

    fun format(mime: String): String? = when (mime.substringBefore(';').trim().lowercase()) {
        "application/epub+zip" -> "epub"
        "application/pdf" -> "pdf"
        "application/vnd.comicbook+zip", "application/x-cbz" -> "cbz"
        else -> null
    }
    fun endpoint(input: String): String {
        val uri = URI(input.trim().trimEnd('/'))
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null) { "Enter an HTTPS server address, without a password or query in the URL." }
        return if (uri.path.isNullOrEmpty()) "$uri/api/v1/opds" else uri.toString()
    }
    fun resolve(base: String, href: String): String {
        val origin = URI(base)
        val target = origin.resolve(href)
        require(target.scheme == "https" && target.host.equals(origin.host, true) && target.port == origin.port && target.userInfo == null) { "The server returned a link outside its secure address." }
        return target.toString()
    }
    fun parse(xml: ByteArray, base: String): CatalogPage {
        // Reject document declarations before parsing; never resolve external entities.
        val text = xml.toString(Charsets.UTF_8)
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "Unsupported server feed." }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        val root = builder.parse(xml.inputStream()).documentElement
        require(root.localName == "feed") { "This address did not return an OPDS catalogue." }
        fun Element.children(name: String): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.localName == name }
        fun Element.value(name: String) = children(name).firstOrNull()?.textContent.orEmpty()
        fun Element.link() = resolve(base, getAttribute("href"))
        val entries = root.children("entry")
        val books = entries.mapNotNull { entry ->
            val links = entry.children("link")
            val acquisitions = links.filter { it.getAttribute("rel").startsWith("http://opds-spec.org/acquisition") }
            if (acquisitions.isEmpty()) return@mapNotNull null
            val edition = acquisitions.filter { format(it.getAttribute("type")) != null }
                .minByOrNull { listOf("epub", "pdf", "cbz").indexOf(format(it.getAttribute("type"))) }
            val listened = acquisitions.filter { audioFormat(it.getAttribute("type")) != null }
                .minByOrNull { audioPreference.indexOf(audioFormat(it.getAttribute("type"))) }
            if (edition == null && listened == null) return@mapNotNull null
            val id = UUID.nameUUIDFromBytes((URI(base).let { "${it.scheme}://${it.authority}" } + entry.value("id")).toByteArray()).toString()
            Title(id, entry.value("title"), entry.children("author").joinToString { it.value("name") },
                download = edition?.link(),
                coverUrl = links.firstOrNull { it.getAttribute("rel") == "http://opds-spec.org/image" }?.link(),
                description = entry.value("summary").ifBlank { entry.value("content") },
                downloadFormat = edition?.let { format(it.getAttribute("type")) } ?: "epub",
                audioDownload = listened?.link(), audioDownloadFormat = listened?.let { audioFormat(it.getAttribute("type")) })
        }
        val catalog = entries.firstOrNull { it.value("id") == "urn:booklore:catalog:all" || it.value("title").equals("All Books", true) }
            ?.children("link")?.firstOrNull { it.getAttribute("rel") == "subsection" }?.link()
        return CatalogPage(books, root.children("link").firstOrNull { it.getAttribute("rel") == "next" }?.link(), catalog)
    }
}

class BookServer(private val context: Context, private val credentialNamespace: String = "book-server", private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection }) {
    private val prefs = context.getSharedPreferences(credentialNamespace, Context.MODE_PRIVATE)
    private val cache = AtomicFile(File(context.filesDir, if (credentialNamespace == "book-server") "server-catalog.json" else "$credentialNamespace-catalog.json"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(credentialNamespace, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(credentialNamespace, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun account(): ServerAccount? {
        val encoded = prefs.getString("secret", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
            ServerAccount(prefs.getString("url", "")!!, prefs.getString("user", "")!!, String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8))
        }.getOrNull()
    }
    fun save(account: ServerAccount) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(account.password.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("url", account.url).putString("user", account.user)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("secret", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    fun disconnect() { check(prefs.edit().clear().commit()) { "Could not remove the saved connection. Please try again." }; cache.delete() }
    fun cached(): List<Title> = runCatching {
        val array = JSONArray(cache.openRead().bufferedReader().use { it.readText() })
        (0 until array.length()).map { array.getJSONObject(it).let { b ->
            Title(b.getString("id"), b.getString("name"), b.optString("author"), download = b.optString("download").takeIf(String::isNotEmpty), coverUrl = b.optString("cover").takeIf(String::isNotEmpty), description = b.optString("description"), downloadFormat = b.optString("downloadFormat", "epub"))
        } }
    }.getOrDefault(emptyList())
    private fun persist(books: List<Title>) {
        val array = JSONArray()
        books.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("author", it.author).put("download", it.download ?: "").put("cover", it.coverUrl ?: "").put("description", it.description).put("downloadFormat", it.downloadFormat)) }
        val stream = cache.startWrite()
        try { stream.write(array.toString().toByteArray()); cache.finishWrite(stream) }
        catch (e: Exception) { cache.failWrite(stream); throw e }
    }
    private fun request(account: ServerAccount, address: String, destination: File, limit: Long) {
        var url = Opds.resolve(account.url, address)
        repeat(6) {
            val connection = openConnection(url)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000; connection.readTimeout = 30000
                connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("${account.user}:${account.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                when (connection.responseCode) {
                    301, 302, 303, 307, 308 -> { url = Opds.resolve(account.url, URI(url).resolve(connection.getHeaderField("Location") ?: error("Invalid redirect.")).toString()); return@repeat }
                    401, 403 -> error("Sign-in failed. Use your separate BookLore OPDS username and password.")
                    200 -> {
                        require(connection.contentLengthLong <= limit) { "This file is too large to download in this app." }
                        connection.inputStream.use { input -> destination.outputStream().use { output ->
                            val buffer = ByteArray(32768); var count: Int; var total = 0L
                            while (input.read(buffer).also { count = it } != -1) { total += count; require(total <= limit) { "Download size limit exceeded." }; output.write(buffer, 0, count) }
                        } }
                        return
                    }
                    else -> error("Server returned HTTP ${connection.responseCode}. Please try again.")
                }
            } finally { connection.disconnect() }
        }
        error("Too many server redirects.")
    }
    suspend fun refresh(account: ServerAccount): List<Title> = withContext(Dispatchers.IO) {
        val books = linkedMapOf<String, Title>(); val visited = mutableSetOf<String>()
        var url: String? = account.url
        val temp = File.createTempFile("catalog-", ".xml", context.cacheDir)
        try {
            while (url != null) {
                check(visited.add(url) && visited.size <= 1000) { "The server returned a looping or oversized catalogue." }
                request(account, url, temp, 8L * 1024 * 1024)
                val page = Opds.parse(temp.readBytes(), url)
                page.books.forEach { books[it.id] = it }
                url = page.next ?: if (visited.size == 1) page.catalog else null
            }
            books.values.toList().also { persist(it) }
        } finally { temp.delete() }
    }
    suspend fun cover(title: Title): File? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "${title.id}.jpg")
        if (file.exists()) return@withContext file
        val account = account() ?: return@withContext null
        val url = title.coverUrl ?: return@withContext null
        val temp = File.createTempFile("cover-", ".jpg", context.cacheDir)
        try { request(account, url, temp, 8L * 1024 * 1024); temp.copyTo(file, true); file }
        catch (_: Exception) { null } finally { temp.delete() }
    }
    /**
     * Downloads the catalogue's audiobook edition and files it against the same title, so a book
     * both read and listened to stays one entry with two formats.
     */
    suspend fun downloadAudio(title: Title, store: LibraryStore): Title = withContext(Dispatchers.IO) {
        val account = account() ?: error("Connect your server first.")
        val link = title.audioDownload ?: error("This title has no supported audiobook edition.")
        val format = title.audioDownloadFormat ?: error("This title has no supported audiobook edition.")
        require(format in Opds.audioPreference) { "Unsupported audiobook format." }
        val temp = File.createTempFile("audiobook-", ".${format}", context.cacheDir)
        try {
            // An audiobook is an order of magnitude larger than an e-book; the e-book cap would
            // reject almost every real one.
            request(account, link, temp, 2L * 1024 * 1024 * 1024)
            store.import(android.net.Uri.fromFile(temp), true, title.id, title)
        } finally { temp.delete() }
    }
    suspend fun download(title: Title, store: LibraryStore): Title = withContext(Dispatchers.IO) {
        val account = account() ?: error("Connect your server first.")
        require(title.downloadFormat in setOf("epub", "pdf", "cbz")) { "Unsupported e-book format." }
        val temp = File.createTempFile("book-", ".${title.downloadFormat}", context.cacheDir)
        try {
            request(account, title.download ?: error("This title has no supported e-book edition."), temp, 256L * 1024 * 1024)
            store.keepDownload(title, temp)
        } finally { temp.delete() }
    }
}
