package app.folio.library

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional live smoke: pass -e liveCatalog true. Ordinary test runs remain offline. */
class PublicCatalogTest {
    @Test fun downloadsRealEbookAndAudioChapter() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCatalog") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryStore(context); val before = store.all()
        var stage = "Gutenberg search"
        try {
            val books = PublicCatalog.search(context, false, "Alice", 1)
            val alice = books.first { it.id == "pg-11" }
            stage = "Gutenberg download"
            PublicCatalog.add(context, alice) {}
            assertTrue(store.all().any { it.id !in before.map { b -> b.id } && it.ebook != null })
            stage = "LibriVox search"
            val audio = PublicCatalog.search(context, true, "Alice", 1).first()
            assertTrue(audio.tracks.size > 1)
            stage = "LibriVox download"
            PublicCatalog.add(context, audio.copy(tracks = audio.tracks.take(1))) {}
            val imported = store.all().first { it.id !in before.map { b -> b.id } && it.audio != null }
            val tracks = AudioBundle.read(store.file(imported.audio!!))
            assertEquals(1, tracks.size); assertTrue(tracks[0].durationMs > 1000); assertTrue(tracks[0].file.length() > 1000)
        } catch (error: Exception) { throw AssertionError("Live catalogue failed at $stage", error) } finally {
            store.all().filter { item -> before.none { it.id == item.id } }.forEach { item ->
                item.audio?.let { name -> if (name.endsWith(".audiobook.json")) AudioBundle.read(store.file(name)).forEach { it.file.delete() }; store.file(name).delete() }
                item.ebook?.let { store.file(it).delete() }; store.file("${item.id}.jpg").delete()
            }
            store.save(before)
        }
    }
}
