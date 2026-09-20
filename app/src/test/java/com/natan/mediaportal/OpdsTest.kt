package com.natan.mediaportal

import org.junit.Assert.*
import org.junit.Test

class OpdsTest {
    private val base = "https://books.example.com/api/v1/opds"
    @Test fun findsCatalogThenParsesRelativeLinksAndMultipleFormats() {
        val root = Opds.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>urn:booklore:catalog:all</id><title>All Books</title><link rel="subsection" href="/api/v1/opds/catalog?page=1&amp;size=50"/></entry></feed>""".toByteArray(), base)
        assertEquals("$base/catalog?page=1&size=50", root.catalog)
        val page = Opds.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><link rel="next" href="?page=2"/><entry><id>urn:booklore:book:1</id><title>Alice &amp; friends</title><author><name>Lewis Carroll</name></author><summary>A story.</summary><link rel="http://opds-spec.org/acquisition" href="/book.pdf" type="application/pdf"/><link rel="http://opds-spec.org/acquisition" href="/book.epub" type="application/epub+zip"/><link rel="http://opds-spec.org/image" href="/cover.jpg"/></entry></feed>""".toByteArray(), base)
        assertEquals(1, page.books.size)
        assertEquals("Alice & friends", page.books.single().name)
        assertEquals("Lewis Carroll", page.books.single().author)
        assertEquals("https://books.example.com/book.epub", page.books.single().download)
        assertEquals("https://books.example.com/cover.jpg", page.books.single().coverUrl)
        assertTrue(page.next!!.endsWith("?page=2"))
        assertEquals(page.books.single().id, Opds.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>urn:booklore:book:1</id><title>New title</title><link rel="http://opds-spec.org/acquisition" href="/book.epub" type="application/epub+zip"/></entry></feed>""".toByteArray(), "$base/catalog?page=2").books.single().id)
    }
    @Test fun rejectsCredentialLeaksAndInsecureEndpoints() {
        listOf("http://books.example.com", "https://user:pass@books.example.com", "invalid").forEach { assertTrue(runCatching { Opds.endpoint(it) }.isFailure) }
        listOf("https://evil.example/book", "http://books.example.com/book", "//evil.example/book", "https://books.example.com:444/book").forEach { assertTrue(runCatching { Opds.resolve(base, it) }.isFailure) }
        assertEquals(base, Opds.endpoint("https://books.example.com/"))
    }
    @Test fun rejectsDoctypeAndHtmlInsteadOfCatalog() {
        assertTrue(runCatching { Opds.parse("<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><feed>&x;</feed>".toByteArray(), base) }.isFailure)
        assertTrue(runCatching { Opds.parse("<html><body>Login</body></html>".toByteArray(), base) }.isFailure)
    }
}
