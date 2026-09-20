package com.natan.mediaportal

import org.junit.Assert.*
import org.junit.Test

/**
 * Folio guessed series by parsing title strings while the catalogue was already stating the
 * answer in the same feed. BookLore uses two meta elements; BookOrbit packs it into a link title.
 * Both are OPDS 1.x.
 */
class SeriesMetadataTest {
    private val base = "https://books.example.com/api/v1/opds"

    private fun entry(inner: String, title: String = "Leviathan Wakes") = Opds.parse(
        """<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>urn:x:1</id><title>$title</title>$inner<link rel="http://opds-spec.org/acquisition" href="/b.epub" type="application/epub+zip"/></entry></feed>""".toByteArray(),
        base,
    ).books.single()

    @Test fun readsBookLoreSeriesMetadata() {
        val book = entry("""<meta property="belongs-to-collection" id="series">The Expanse</meta><meta property="group-position" refines="#series">3</meta>""")
        assertEquals("The Expanse", book.seriesName)
        assertEquals(3.0, book.seriesPosition!!, 0.001)
    }

    /** BookLore stores the position as a float, so half-numbered entries are real. */
    @Test fun readsAFractionalSeriesPosition() {
        val book = entry("""<meta property="belongs-to-collection" id="series">Discworld</meta><meta property="group-position" refines="#series">3.5</meta>""")
        assertEquals(3.5, book.seriesPosition!!, 0.001)
    }

    @Test fun readsBookOrbitSeriesLink() {
        val book = entry("""<link rel="http://opds-spec.org/sort/series" href="/s" title="The Expanse #3"/>""")
        assertEquals("The Expanse", book.seriesName)
        assertEquals(3.0, book.seriesPosition!!, 0.001)
    }

    @Test fun parsesPackedLinkTitles() {
        assertEquals(SeriesInfo("The Expanse", 3.0), Opds.seriesFromLinkTitle("The Expanse #3"))
        assertEquals(SeriesInfo("The Expanse", null), Opds.seriesFromLinkTitle("The Expanse"))
        assertNull(Opds.seriesFromLinkTitle(""))
        assertNull("a number with no name is not a series", Opds.seriesFromLinkTitle(" #3"))
    }

    @Test fun aBookWithNoSeriesMetadataKeepsNone() {
        val book = entry("")
        assertNull(book.seriesName)
        assertNull(book.seriesPosition)
    }

    private fun book(id: String, name: String, series: String? = null, position: Double? = null) =
        Title(id, name, "James S. A. Corey", seriesName = series, seriesPosition = position)

    /** The title says one thing and the catalogue says another; the catalogue is authoritative. */
    @Test fun theCatalogueBeatsTheTitleGuess() {
        val info = LibrarySorting.series(listOf(book("1", "Leviathan Wakes (Some Other Series #9)", "The Expanse", 1.0)))
        assertEquals("The Expanse", info["1"]?.name)
        assertEquals(1.0, info["1"]?.index!!, 0.001)
    }

    /** A locally imported file has no catalogue behind it, so the heuristic must remain. */
    @Test fun booksWithoutCatalogueDataStillFallBackToTheTitle() {
        val info = LibrarySorting.series(listOf(book("1", "Leviathan Wakes (The Expanse #1)"), book("2", "Caliban's War (The Expanse #2)")))
        assertEquals("The Expanse", info["1"]?.name)
        assertEquals(2.0, info["2"]?.index!!, 0.001)
    }

    @Test fun catalogueAndGuessedBooksCoexist() {
        val info = LibrarySorting.series(listOf(book("1", "Leviathan Wakes", "The Expanse", 1.0), book("2", "Caliban's War (The Expanse #2)")))
        assertEquals("The Expanse", info["1"]?.name)
        assertEquals("The Expanse", info["2"]?.name)
    }
}
