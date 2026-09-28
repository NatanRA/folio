package app.folio.library

import org.junit.Assert.*
import org.junit.Test

/**
 * A catalogue can offer a title as an audiobook as well as an e-book. Folio used to keep such an
 * entry but leave it with no download at all, because every audio acquisition link fell through
 * [Opds.format].
 */
class ServerAudiobookTest {
    private val base = "https://books.example.com/api/v1/opds"

    private fun feed(links: String) = Opds.parse(
        """<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>urn:x:1</id><title>Harry Potter</title>$links</entry></feed>""".toByteArray(),
        base,
    )

    private fun acquisition(type: String, href: String = "/f") =
        """<link rel="http://opds-spec.org/acquisition" href="$href" type="$type"/>"""

    @Test fun readsAnAudiobookEditionFromTheCatalogue() {
        val book = feed(acquisition("audio/mpeg", "/book.mp3")).books.single()
        assertEquals("mp3", book.audioDownloadFormat)
        assertEquals("https://books.example.com/book.mp3", book.audioDownload)
        assertNull("an audiobook is not an e-book edition", book.download)
    }

    @Test fun acceptsTheAudioTypesThePlayerSupports() {
        mapOf(
            "audio/mpeg" to "mp3", "audio/mp4" to "m4b", "audio/x-m4b" to "m4b",
            "audio/x-m4a" to "m4a", "audio/aac" to "aac", "audio/flac" to "flac",
            "audio/opus" to "ogg", "audio/x-wav" to "wav",
        ).forEach { (mime, format) -> assertEquals(mime, format, Opds.audioFormat(mime)) }
        assertEquals("mp3", Opds.audioFormat("audio/mpeg; charset=utf-8"))
    }

    @Test fun rejectsWhatIsNotPlayableAudio() {
        assertNull(Opds.audioFormat("application/epub+zip"))
        assertNull(Opds.audioFormat("video/mp4"))
        assertNull(Opds.audioFormat(""))
    }

    @Test fun prefersAChapteredContainerWhateverOrderTheFeedLists() {
        val forward = feed(acquisition("audio/mpeg", "/plain.mp3") + acquisition("audio/mp4", "/chaptered.m4b"))
        val reverse = feed(acquisition("audio/mp4", "/chaptered.m4b") + acquisition("audio/mpeg", "/plain.mp3"))
        listOf(forward, reverse).forEach {
            assertEquals("m4b", it.books.single().audioDownloadFormat)
            assertEquals("https://books.example.com/chaptered.m4b", it.books.single().audioDownload)
        }
    }

    /** One title, both formats — the thing Folio is for. */
    @Test fun aTitleCanOfferBothAnEbookAndAnAudiobook() {
        val book = feed(acquisition("application/epub+zip", "/book.epub") + acquisition("audio/mpeg", "/book.mp3")).books.single()
        assertEquals("epub", book.downloadFormat)
        assertEquals("https://books.example.com/book.epub", book.download)
        assertEquals("mp3", book.audioDownloadFormat)
        assertEquals("https://books.example.com/book.mp3", book.audioDownload)
    }

    @Test fun stillDropsEntriesThatOfferNothingWeCanOpen() {
        assertTrue(feed(acquisition("application/x-mobipocket-ebook", "/book.mobi")).books.isEmpty())
        assertTrue(feed("").books.isEmpty())
    }

    /** BookOrbit emits the description as <content>; BookLore used <summary>. */
    @Test fun readsADescriptionFromEitherSummaryOrContent() {
        val summary = Opds.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>a</id><title>T</title><summary>From summary.</summary>${acquisition("application/epub+zip")}</entry></feed>""".toByteArray(), base)
        val content = Opds.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>b</id><title>T</title><content type="text">From content.</content>${acquisition("application/epub+zip")}</entry></feed>""".toByteArray(), base)
        assertEquals("From summary.", summary.books.single().description)
        assertEquals("From content.", content.books.single().description)
    }
}
