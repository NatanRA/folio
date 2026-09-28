package app.folio.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.util.Url

/**
 * What the page picker's typeset previews show for a book: its chapters placed on the estimated pages, and each
 * page's first words, read with Readium's content reader as the picker nears them.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class PagePreviews(private val publication: Publication, private val positions: List<Locator>) {
    val chapters: List<BookChapter> = bookChapters(
        chapterLinks(publication.tableOfContents.ifEmpty { publication.readingOrder }).mapIndexed { index, (link, _) ->
            // The same fallback name as the Chapters list in ReaderNavigation.
            (link.title?.takeIf { it.isNotBlank() } ?: "Section ${index + 1}") to link.url().resourceKey()
        },
        positions.map { it.href.resourceKey() })

    private val parsing = Mutex()
    /** Recently read resources' text by resource; a resource holds many pages, so neighbouring pages reuse it. */
    private val texts = object : LinkedHashMap<String, String>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 6
    }

    /** The first words of [page] (1-based), or null when the book has no text there. Reads off the main thread. */
    suspend fun excerpt(page: Int): String? {
        val locator = positions.getOrNull(page - 1) ?: return null
        val resource = locator.href.resourceKey()
        return withContext(Dispatchers.IO) {
            val text = synchronized(texts) { texts[resource] } ?: parsing.withLock {
                synchronized(texts) { texts[resource] }
                    ?: resourceText(locator, resource).also { parsed -> synchronized(texts) { texts[resource] = parsed } }
            }
            val progression = locator.locations.progression ?: 0.0
            val opening = chapters.getOrNull(chapterIndexAt(chapters, page))?.takeIf { it.start == page }
            // A chapter's first page already shows its title, so its words begin after any repeated heading.
            (if (opening == null) excerptAt(text, progression)
            else excerptAt(withoutTitle(excerptAt(text, progression, ExcerptLength + opening.title.length), opening.title), 0.0)).ifBlank { null }
        }
    }

    /** Lets go of the text read for the picker once it closes: a long book's resources can hold megabytes of it. */
    fun release() = synchronized(texts) { texts.clear() }

    /**
     * The body text of the resource holding [locator]. Readium's HTML content reader starts a resource at a CSS
     * selector and ignores a position's progression, so the whole resource is read once and cut by progression.
     */
    private suspend fun resourceText(locator: Locator, resource: String): String {
        val start = locator.copy(locations = Locator.Locations(progression = 0.0), text = Locator.Text())
        val iterator = publication.content(start)?.iterator() ?: return ""
        val text = StringBuilder()
        // The reader finds this resource's end by reading into the next one; that one's first element ends the loop.
        while (iterator.hasNext()) {
            val element = iterator.next()
            if (element.locator.href.resourceKey() != resource) break
            // Headings are drawn from the table of contents; the page shows the prose.
            if (element is Content.TextElement && element.role !is Content.TextElement.Role.Heading) text.append(element.text).append(' ')
        }
        return text.toString().replace(Regex("\\s+"), " ").trim()
    }
}

/** A resource's identity for matching: its URL without the fragment, normalised so differently encoded links agree. */
private fun Url.resourceKey() = removeFragment().normalize().toString()
