package app.folio.library

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.shared.publication.services.positions
import java.io.File

class NotesExchangeTest {
    @Test fun annotationsMergeAcrossBookIdsAndKeepDeletions() = runBlocking<Unit> {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val store = LibraryStore(context); val before = store.all()
        val source = File(context.cacheDir, "annotation-fixture.epub")
        inst.context.assets.open("alice.epub").use { input -> source.outputStream().use { input.copyTo(it) } }
        val a = store.import(Uri.fromFile(source), false, null)
        val b = store.import(Uri.fromFile(source), false, null)
        try {
            val pub = Books(context).open(store.file(a.ebook!!)); val location = pub.positions()[7]; pub.close()
            val note = ReadingNote(locator = location, note = "Android note")
            ReadingNotes(context, a.id).save(listOf(note))
            val exA = NotesExchange(context, a.id); val exB = NotesExchange(context, b.id)
            val bundle = exA.export(); exB.merge(bundle)
            assertEquals("Android note", ReadingNotes(context, b.id).all().single().note)
            ReadingNotes(context, b.id).save(emptyList())
            exA.merge(exB.export()); assertTrue(ReadingNotes(context, a.id).all().isEmpty())
            exA.merge(bundle); assertTrue(ReadingNotes(context, a.id).all().isEmpty())
            val invalid = org.json.JSONObject(bundle).put("document", "b".repeat(64)).toString()
            assertThrows(IllegalArgumentException::class.java) { exA.merge(invalid) }
        } finally {
            store.save(before)
            listOf(a, b).forEach { title ->
                store.file(title.ebook!!).delete(); store.file("${title.id}.jpg").delete()
                listOf("reading-notes", "reading-bookmarks", "reading-words", "annotation-journal").forEach { context.getSharedPreferences(it, 0).edit().remove(title.id).commit() }
            }
            source.delete()
        }
    }
}
