package com.natan.mediaportal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryTest {
    @Test fun epubImportsAndLinkedAudioKeepsEbookAndIndependentProgress() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryStore(context)
        val before = store.all()
        val fixture = File(context.cacheDir, "test-book.epub")
        ZipOutputStream(fixture.outputStream()).use { zip ->
            fun entry(name: String, value: String) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">test-book</dc:identifier><dc:title>Test story</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-09-05T00:00:00Z</meta></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="chapter"/></spine></package>""")
            entry("chapter.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>First chapter</title></head><body><h1>First chapter</h1><p>A story can be read and heard, each at its own pace.</p></body></html>""")
            entry("nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">First chapter</a></li></ol></nav></body></html>""")
        }
        var imported: Title? = null
        var linked: Title? = null
        val audio = File(context.cacheDir, "test-audio.mp3").apply { writeBytes(byteArrayOf(73, 68, 51)) }
        try {
            imported = store.import(Uri.fromFile(fixture), false, null)
            assertEquals("Test story", imported.name)
            val prefs = context.getSharedPreferences("progress", 0)
            prefs.edit().putString("ebook:${imported.id}", "saved-locator").putLong("audio:${imported.id}", 12345).commit()
            linked = store.import(Uri.fromFile(audio), true, imported.id)
            assertEquals(imported.id, linked.id)
            assertEquals(imported.ebook, linked.ebook)
            assertNotNull(linked.audio)
            assertTrue(store.file(linked.ebook!!).exists())
            assertTrue(store.file(linked.audio!!).exists())
            assertEquals(before.size + 1, LibraryStore(context).all().size)
            assertEquals("saved-locator", prefs.getString("ebook:${linked.id}", null))
            assertEquals(12345L, prefs.getLong("audio:${linked.id}", 0))
        } finally {
            store.save(before)
            imported?.ebook?.let { store.file(it).delete() }
            linked?.audio?.let { store.file(it).delete() }
            imported?.let { context.getSharedPreferences("progress", 0).edit().remove("ebook:${it.id}").remove("audio:${it.id}").commit() }
            fixture.delete(); audio.delete()
        }
    }
}
