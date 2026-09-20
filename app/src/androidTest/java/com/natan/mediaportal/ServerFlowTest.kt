package com.natan.mediaportal

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ServerFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val account = ServerAccount("https://library.example/api/v1/opds", "reader", "test-password")
    private class Response(address: String, private val body: ByteArray, private val status: Int = 200, private val location: String? = null) : HttpURLConnection(URL(address)) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body.inputStream()
        override fun getContentLengthLong() = body.size.toLong()
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
    }
    @Test fun pdfAndComicServerDownloadsKeepIdentityAndFormat(): Unit = runBlocking {
        val store = LibraryStore(context); val before = store.all()
        val saved = mutableListOf<Title>()
        val server = BookServer(context, "format-test") { url ->
            val name = if (url.endsWith("pdf")) "sample.pdf" else "comic.cbz"
            Response(url, InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() })
        }
        try {
            server.save(account)
            for ((format, mime) in listOf("pdf" to "application/pdf", "cbz" to "application/vnd.comicbook+zip")) {
                val feed = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>$format-test</id><title>Server $format</title><link rel="http://opds-spec.org/acquisition" type="$mime" href="/download/$format"/></entry></feed>"""
                val title = Opds.parse(feed.toByteArray(), account.url).books.single()
                val imported = server.download(title, store); saved += imported
                assertEquals(title.id, imported.id); assertEquals(title.name, imported.name)
                assertTrue(imported.ebook!!.endsWith(".$format"))
                assertEquals(format, store.all().first { it.id == title.id }.downloadFormat)
                FixedPages(store.file(imported.ebook!!)).use { assertEquals(if (format == "pdf") 2 else 3, it.count) }
            }
        } finally {
            server.disconnect(); store.save(before)
            saved.forEach { store.file(it.ebook!!).delete(); store.file("${it.id}.jpg").delete() }
        }
    }
    @Test fun paginatedServerDownloadSurvivesDisconnectAndFailureKeepsCache(): Unit = runBlocking {
        assertNull(BookServer(context).account())
        val store = LibraryStore(context); val before = store.all()
        val epub = InstrumentationRegistry.getInstrumentation().context.assets.open("alice.epub").use { it.readBytes() }
        val responses = mutableListOf<Response>()
        var fail = false
        val server = BookServer(context) { url ->
            val body = when {
                url.endsWith("/api/v1/opds") -> """<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>urn:booklore:catalog:all</id><title>All Books</title><link rel="subsection" href="/catalog?page=1"/></entry></feed>""".toByteArray()
                url.endsWith("page=1") -> """<feed xmlns="http://www.w3.org/2005/Atom"><link rel="next" href="/catalog?page=2"/><entry><id>urn:booklore:book:1</id><title>Alice</title><author><name>Lewis Carroll</name></author><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/download/1"/></entry></feed>""".toByteArray()
                url.endsWith("page=2") -> """<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>urn:booklore:book:2</id><title>PDF edition</title><link rel="http://opds-spec.org/acquisition" type="application/pdf" href="/download/2"/></entry></feed>""".toByteArray()
                url.endsWith("/download/1") -> epub
                else -> error("Unexpected request")
            }
            Response(url, body, if (fail) 401 else 200).also { responses.add(it) }
        }
        var saved: Title? = null
        try {
            val books = server.refresh(account)
            assertEquals(2, books.size)
            assertEquals("pdf", books[1].downloadFormat)
            assertEquals("https://library.example/download/2", books[1].download)
            assertEquals(books, server.cached())
            server.save(account)
            saved = server.download(books.first(), store)
            assertTrue(store.file(saved.ebook!!).exists())
            assertTrue(store.file("${saved.id}.jpg").exists())
            assertTrue(responses.all { it.getRequestProperty("Authorization")?.startsWith("Basic ") == true })
            fail = true
            assertTrue(runCatching { server.refresh(account) }.isFailure)
            assertEquals(books, server.cached())
            server.disconnect()
            assertTrue(server.cached().isEmpty())
            assertEquals(saved, LibraryStore(context).all().first { it.id == saved.id })
            val publication = Books(context).open(store.file(saved.ebook!!))
            assertTrue(publication.metadata.title!!.contains("Alice")); publication.close()
        } finally {
            server.disconnect(); store.save(before)
            saved?.let { it.ebook?.let { file -> store.file(file).delete() }; store.file("${it.id}.jpg").delete() }
        }
    }
    @Test fun crossOriginRedirectNeverReceivesCredentials(): Unit = runBlocking {
        var requests = 0
        val server = BookServer(context) { url -> requests++; Response(url, byteArrayOf(), 302, "https://another.example/feed") }
        assertTrue(runCatching { server.refresh(account) }.isFailure)
        assertEquals(1, requests)
    }
}
