package app.folio.library

import org.junit.Assert.*
import org.junit.Test

class LibrarySortTest {
    private fun book(name: String, author: String = "", addedAt: Long = 0) = Title(name.lowercase().replace(' ', '-'), name, author, addedAt = addedAt)
    private fun names(books: List<Title>) = books.map { it.name }

    @Test fun titleSortIgnoresLeadingArticles() {
        val books = listOf(book("The Hobbit"), book("A Wizard of Earthsea"), book("Dune"), book("an Unkindness of Ghosts"))
        assertEquals(listOf("Dune", "The Hobbit", "an Unkindness of Ghosts", "A Wizard of Earthsea"), names(LibrarySorting.sorted(books, LibrarySort.Title)))
    }
    @Test fun authorSortGroupsByAuthorThenTitleAndPutsUnknownAuthorsLast() {
        val books = listOf(book("Dune", "Frank Herbert"), book("Mystery"), book("The Hobbit", "J. R. R. Tolkien"), book("Emma", "Jane Austen"), book("Dune Messiah", "Frank Herbert"))
        assertEquals(listOf("Dune", "Dune Messiah", "The Hobbit", "Emma", "Mystery"), names(LibrarySorting.sorted(books, LibrarySort.Author)))
    }
    @Test fun dateAddedSortsNewestFirstAndKeepsUndatedBooksLastInOrder() {
        val books = listOf(book("Old", addedAt = 100), book("Undated one"), book("New", addedAt = 300), book("Undated two"), book("Middle", addedAt = 200))
        assertEquals(listOf("New", "Middle", "Old", "Undated one", "Undated two"), names(LibrarySorting.sorted(books, LibrarySort.Added)))
    }
    @Test fun explicitSeriesMarkersGiveNameAndIndex() {
        assertEquals(SeriesInfo("Discworld", 3.0), LibrarySorting.parseSeries("Equal Rites (Discworld #3)"))
        assertEquals(SeriesInfo("The Expanse", 2.0), LibrarySorting.parseSeries("Caliban's War (The Expanse, Book 2)"))
        assertEquals(SeriesInfo("Discworld", 3.0), LibrarySorting.parseSeries("Discworld #3: Equal Rites"))
        assertEquals(SeriesInfo("Dune", 2.0), LibrarySorting.parseSeries("Dune Book 2 - Dune Messiah"))
        assertEquals(SeriesInfo("Harry Potter", 3.0), LibrarySorting.parseSeries("Harry Potter, Book 3"))
        assertEquals(SeriesInfo("Wayfarers", 1.5), LibrarySorting.parseSeries("A Closed and Common Orbit (Wayfarers #1.5)"))
    }
    @Test fun yearsAndBareVolumeWordsAreNotSeries() {
        assertNull(LibrarySorting.parseSeries("Nineteen Eighty-Four (1949)"))
        assertNull(LibrarySorting.parseSeries("Collected Poems (Book 1)"))
        assertNull(LibrarySorting.parseSeries("The Hobbit"))
    }
    @Test fun sharedTitlePrefixByTheSameAuthorBecomesASeries() {
        val books = listOf(
            book("Harry Potter and the Chamber of Secrets", "J. K. Rowling"),
            book("The Hobbit", "J. R. R. Tolkien"),
            book("Harry Potter and the Philosopher's Stone", "J. K. Rowling"),
            book("Harry Potter and the Prisoner of Azkaban", "J. K. Rowling"),
        )
        val series = LibrarySorting.series(books)
        assertEquals("Harry Potter", series[books[0].id]?.name)
        assertEquals("Harry Potter", series[books[2].id]?.name)
        assertEquals("Harry Potter", series[books[3].id]?.name)
        assertNull(series[books[1].id])
    }
    @Test fun prefixesNeedTwoWordsAndMatchingAuthors() {
        val books = listOf(book("Dune", "Frank Herbert"), book("Dune Messiah", "Frank Herbert"), book("Star Wars: Heir to the Empire", "Timothy Zahn"), book("Star Wars: Thrawn", "Timothy Zahn"), book("Star Wars: Lost Stars", "Claudia Gray"))
        val series = LibrarySorting.series(books)
        assertNull(series["dune"]); assertNull(series["dune-messiah"])
        assertEquals("Star Wars", series[books[2].id]?.name); assertEquals("Star Wars", series[books[3].id]?.name)
        assertNull(series[books[4].id])
    }
    @Test fun blankAuthorsCanJoinAPrefixSeries() {
        val books = listOf(book("Harry Potter and the Goblet of Fire"), book("Harry Potter and the Order of the Phoenix", "J. K. Rowling"))
        assertEquals("Harry Potter", LibrarySorting.series(books)[books[0].id]?.name)
    }
    @Test fun seriesSortOrdersByIndexThenTitleWithStandaloneBooksLast() {
        val books = listOf(
            book("Zebra Tales"),
            book("Harry Potter and the Prisoner of Azkaban", "J. K. Rowling"),
            book("Equal Rites (Discworld #3)", "Terry Pratchett"),
            book("Harry Potter and the Chamber of Secrets", "J. K. Rowling"),
            book("The Colour of Magic (Discworld #1)", "Terry Pratchett"),
            book("Alpha Standalone"),
        )
        assertEquals(listOf("The Colour of Magic (Discworld #1)", "Equal Rites (Discworld #3)", "Harry Potter and the Chamber of Secrets", "Harry Potter and the Prisoner of Azkaban", "Alpha Standalone", "Zebra Tales"),
            names(LibrarySorting.sorted(books, LibrarySort.Series)))
    }
    @Test fun trailingBookNumbersGiveTheIndexWhileTheSeriesComesFromTheSharedPrefix() {
        val books = listOf(
            book("Harry Potter and the Prisoner of Azkaban, Book 3"),
            book("Crime and Punishment", "Fyodor Dostoyevsky"),
            book("Harry Potter and the Philosopher's Stone, Book 1"),
            book("Harry Potter and the Chamber of Secrets, Book 2"),
        )
        val series = LibrarySorting.series(books)
        assertEquals(SeriesInfo("Harry Potter", 3.0), series[books[0].id])
        assertEquals(SeriesInfo("Harry Potter", 1.0), series[books[2].id])
        assertNull(series[books[1].id])
        assertEquals(listOf("Harry Potter and the Philosopher's Stone, Book 1", "Harry Potter and the Chamber of Secrets, Book 2", "Harry Potter and the Prisoner of Azkaban, Book 3", "Crime and Punishment"),
            names(LibrarySorting.sorted(books, LibrarySort.Series)))
    }
}
