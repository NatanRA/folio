package com.natan.mediaportal

enum class LibrarySort(val label: String) { Title("Title"), Author("Author"), Series("Series"), Added("Date added") }

data class SeriesInfo(val name: String, val index: Double? = null)

/** Orders the library grid. Series come from explicit title markers first, then from titles that share a leading phrase. */
object LibrarySorting {
    private const val NUMBER = """(\d+(?:\.\d+)?)"""
    private const val VOLUME = """(?:#|book|vol\.?|volume|part)"""
    private val bracketed = Regex("""^(.*?)\s*[(\[]\s*(.+?),?\s*$VOLUME?\s*$NUMBER\s*[)\]]\s*$""", RegexOption.IGNORE_CASE)
    private val leading = Regex("""^(.+?)\s*$VOLUME\s*$NUMBER\s*[:\-–—]\s*.+$""", RegexOption.IGNORE_CASE)
    private val trailing = Regex("""^(.+?)\s*[,:\-–—]?\s*$VOLUME\s*$NUMBER\s*$""", RegexOption.IGNORE_CASE)
    private val volumeWords = setOf("book", "vol", "volume", "part", "no", "number")
    private val connectors = setOf("and", "the", "of", "a", "an", "in", "&", "to", "for", "with")
    private val articles = Regex("""^(the|a|an)\s+""", RegexOption.IGNORE_CASE)

    fun sortKey(title: String) = title.trim().replace(articles, "").lowercase()

    fun parseSeries(title: String): SeriesInfo? = named(title) ?: numbered(title)

    /** "Title (Series #3)" and "Series #3: Title" name the series outright. */
    private fun named(title: String) = (bracketed.find(title) ?: leading.find(title))?.let(::info)
    /** "Title, Book 3" only numbers the book; the text before the marker is its title, which doubles as the series name when it has no siblings. */
    private fun numbered(title: String) = trailing.find(title)?.let(::info)
    private fun info(match: MatchResult): SeriesInfo? {
        val name = match.groupValues[match.groupValues.size - 2].trim().trimEnd(',', ':', '-', '–', '—', ' ')
        if (name.none { it.isLetter() } || name.lowercase().trimEnd('.') in volumeWords) return null
        return SeriesInfo(name, match.groupValues.last().toDoubleOrNull())
    }

    fun series(books: List<Title>): Map<String, SeriesInfo> {
        val named = books.mapNotNull { book -> named(book.name)?.let { book.id to it } }.toMap()
        val numbered = books.filterNot { it.id in named }.mapNotNull { book -> numbered(book.name)?.let { book.id to it } }.toMap()
        val words = books.associate { it.id to words(numbered[it.id]?.name ?: it.name) }
        val inferred = books.filterNot { it.id in named }.mapNotNull { book ->
            val mine = words.getValue(book.id)
            val longest = books.filter { it.id != book.id && it.id !in named && sameAuthor(book, it) }
                .maxOfOrNull { other -> commonPrefix(mine, words.getValue(other.id)) } ?: 0
            when {
                longest >= 2 -> book.id to SeriesInfo(prefixText(book.name, longest), numbered[book.id]?.index)
                else -> numbered[book.id]?.let { book.id to it }
            }
        }.toMap()
        return named + inferred
    }

    fun sorted(books: List<Title>, sort: LibrarySort): List<Title> = when (sort) {
        LibrarySort.Title -> books.sortedBy { sortKey(it.name) }
        LibrarySort.Author -> books.sortedWith(compareBy({ it.author.isBlank() }, { it.author.trim().lowercase() }, { sortKey(it.name) }))
        LibrarySort.Added -> books.sortedWith(compareBy({ it.addedAt <= 0 }, { -it.addedAt }))
        LibrarySort.Series -> {
            val series = series(books)
            books.sortedWith(compareBy({ series[it.id] == null }, { series[it.id]?.name?.let(::sortKey) ?: "" }, { series[it.id]?.index ?: Double.MAX_VALUE }, { sortKey(it.name) }))
        }
    }

    private fun words(title: String) = title.lowercase().split(Regex("""[^\p{L}\p{N}']+""")).filter { it.isNotBlank() }
    private fun sameAuthor(a: Title, b: Title) = a.author.isBlank() || b.author.isBlank() || a.author.trim().equals(b.author.trim(), ignoreCase = true)
    /** Number of leading words two titles share, with trailing connector words dropped so "Harry Potter and the" reads as "Harry Potter". */
    private fun commonPrefix(a: List<String>, b: List<String>): Int {
        var n = 0
        while (n < a.size && n < b.size && a[n] == b[n]) n++
        if (n == a.size && n == b.size) return 0
        while (n > 0 && a[n - 1] in connectors) n--
        return n
    }
    private fun prefixText(title: String, count: Int): String {
        val tokens = Regex("""[\p{L}\p{N}']+""").findAll(title).take(count).toList()
        return title.substring(tokens.first().range.first, tokens.last().range.last + 1)
    }
}
