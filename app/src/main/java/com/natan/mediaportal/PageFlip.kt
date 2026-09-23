package com.natan.mediaportal

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The page picker's open book: page arithmetic, chapters and gesture decisions, kept free of Compose and Readium so
 * JVM tests can check them. Speeds are in dp per second and distances in dp. The numbers are the flipbook design's,
 * which the iOS picker shares.
 */

/** A released page finishes its turn when flung onward faster than this, even short of the vertical. */
internal const val CommitSpeed = 350f
/** A fling faster than this riffles extra pages after the one being turned, one more for every [RiffleSpeedStep]. */
internal const val RiffleSpeed = 1100f
internal const val RiffleSpeedStep = 110f
internal const val MaxRiffle = 19
internal const val RiffleStaggerMillis = 55L
internal const val RiffleTurnMillis = 240
internal const val DragFinishMillis = 220
internal const val SpringBackMillis = 200
internal const val KeyTurnMillis = 300
/** A scrub shows a quick leaf at most this often, so dragging the edges still feels like pages going by. */
internal const val ScrubFlutterMillis = 160
internal const val ScrubFlutterGapMillis = 45L
/** How far above the edges the finger goes before scrubbing turns fine, and how much slower fine scrubbing moves. */
internal const val FineScrubLift = 44f
internal const val FineScrubRate = .2f
internal const val PageTickGapMillis = 32L
internal const val MaxEdgeLayers = 7
/** Characters of a page's own text shown as its first words. */
internal const val ExcerptLength = 140

/** A table-of-contents entry placed on the estimated page (1-based position) where its resource starts. */
internal data class BookChapter(val title: String, val start: Int) {
    private val parts = splitChapterTitle(title)
    /** The heading label the title carries, such as "CHAPTER I"; null when it has none. */
    val label: String? get() = parts.label
    /** The chapter's name without its label ("Down the Rabbit-Hole"), or the whole title. */
    val name: String get() = parts.name
    /** One line for the header: "I · Down the Rabbit-Hole" when the title is numbered, otherwise the title. */
    val caption: String get() = parts.caption ?: title
}

internal data class ChapterTitle(val label: String?, val name: String, val caption: String?)

// A well-formed roman numeral, so words such as "Mild" or "Civil" aren't read as numbers.
private const val Roman = "M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})"
private val numberedHeading = Regex("""^(chapter|part|book|section)\s+([0-9]+|(?=[ivxlcdm])$Roman)(?=$|[\s.:\-–—])\.?\s*(?:[:\-–—]\s*)?(.*)$""", RegexOption.IGNORE_CASE)
private val bareNumber = Regex("""^([0-9]{1,3}|(?=[IVXLCDM])$Roman)\s*[.:\-–—]\s+(.+)$""")

/**
 * Splits a numbered title ("CHAPTER I. Down the Rabbit-Hole", "Part 2: The Sea", "IV. Pig and Pepper") into its
 * label and name, so a chapter opening can set the label small above the name. A title without its own number keeps
 * no label: a table of contents also lists covers and contents pages, which an invented number would mislabel.
 */
internal fun splitChapterTitle(title: String): ChapterTitle {
    val text = title.trim().replace(Regex("\\s+"), " ")
    numberedHeading.matchEntire(text)?.let { match ->
        val (kind, number, rest) = match.destructured
        if (rest.isBlank()) return ChapterTitle(null, text, null)
        val word = kind.lowercase().replaceFirstChar(Char::uppercaseChar)
        val numeral = number.uppercase()
        return ChapterTitle("${kind.uppercase()} $numeral", rest.trim(),
            if (word == "Chapter") "$numeral · ${rest.trim()}" else "$word $numeral · ${rest.trim()}")
    }
    bareNumber.matchEntire(text)?.let { match ->
        val (number, rest) = match.destructured
        return ChapterTitle("CHAPTER $number", rest.trim(), "$number · ${rest.trim()}")
    }
    return ChapterTitle(null, text, null)
}

/**
 * Places table-of-contents entries (title to href) on the estimated pages: each starts at the first position whose
 * resource is the entry's href without its fragment. Entries whose resource has no position are left out. Entries
 * that land on the same page keep the first, as a file's own sections all begin where the file does. Sorted by page.
 */
internal fun bookChapters(entries: List<Pair<String, String>>, positionHrefs: List<String>): List<BookChapter> {
    val firstPage = HashMap<String, Int>()
    positionHrefs.forEachIndexed { index, href -> firstPage.putIfAbsent(href.substringBefore('#'), index + 1) }
    val taken = HashSet<Int>()
    return entries.mapNotNull { (title, href) -> firstPage[href.substringBefore('#')]?.let { BookChapter(title.trim(), it) } }
        .sortedBy { it.start }
        .filter { taken.add(it.start) }
}

/** Index in [chapters] (sorted by start) of the chapter [page] is in: the last one starting at or before it, or -1. */
internal fun chapterIndexAt(chapters: List<BookChapter>, page: Int): Int {
    var low = 0
    var high = chapters.lastIndex
    var found = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (chapters[middle].start <= page) { found = middle; low = middle + 1 } else high = middle - 1
    }
    return found
}

/** Degrees a page has turned when the finger has moved [distance] toward the turn across a page [pageWidth] wide. */
internal fun turnedDegrees(distance: Float, pageWidth: Float): Float =
    if (pageWidth <= 0f) 0f else (distance / pageWidth * 180f * .95f).coerceIn(0f, 180f)

/** Whether a released page finishes its turn: past the vertical, or flung onward at over [CommitSpeed]. [speed] is positive toward the turn. */
internal fun commitsTurn(turned: Float, speed: Float): Boolean = turned > 90f || speed > CommitSpeed

/** Extra pages a fling riffles after its own turn. [speed] is positive toward the turn. */
internal fun riffleCount(speed: Float): Int =
    if (speed <= RiffleSpeed) 0 else ((speed - RiffleSpeed) / RiffleSpeedStep).roundToInt().coerceAtMost(MaxRiffle)

/** The page under [x] on an edge strip [width] wide: the strip's ends are the first and last pages. */
internal fun scrubPage(x: Float, width: Float, total: Int): Int =
    if (total <= 1 || width <= 0f) 1 else ((x / width).coerceIn(0f, 1f) * (total - 1)).roundToInt() + 1

/** Whether a finger [lift] dp above where the scrub began has moved into fine scrubbing. */
internal fun isFineScrub(lift: Float): Boolean = lift > FineScrubLift

/** Fine scrubbing: pages move at [FineScrubRate] of the strip's speed, from the page and x where fine mode began. */
internal fun fineScrubPage(anchorPage: Int, dx: Float, width: Float, total: Int): Int =
    if (width <= 0f) anchorPage.coerceIn(1, total)
    else (anchorPage + dx / width * (total - 1) * FineScrubRate).roundToInt().coerceIn(1, total)

/**
 * The page an accessibility adjustment to [requested] lands on: the nearest page, but at least one page on from
 * [current] in the direction asked. TalkBack steps a range in small fractions, which alone would round back.
 */
internal fun adjustedPage(current: Int, requested: Float, total: Int): Int {
    val nearest = requested.roundToInt()
    val page = when {
        requested > current -> maxOf(nearest, current + 1)
        requested < current -> minOf(nearest, current - 1)
        else -> current
    }
    return page.coerceIn(1, total.coerceAtLeast(1))
}

/** What one side of a page shows. Page 1 is the front cover: the cover when it lies on the right, the inside cover on the left. */
internal enum class PageFace { None, Cover, InsideCover, Text }

/** The face of [page] where it lies: a spread's left page and a turning leaf's back are [onLeft]. Page 1's text is never shown. */
internal fun pageFace(page: Int, onLeft: Boolean): PageFace = when {
    page < 1 -> PageFace.None
    page == 1 -> if (onLeft) PageFace.InsideCover else PageFace.Cover
    else -> PageFace.Text
}

/** How shut the book is while its cover turns [turned] degrees from where it lay: 1 shut on page 1, 0 open. */
internal fun coverClosedness(forward: Boolean, turned: Float): Float =
    (if (forward) 1f - turned / 180f else turned / 180f).coerceIn(0f, 1f)

/** How far left the book slides at [closedness], so a shut book's cover sits in the middle: half a page when shut. */
internal fun closedOffset(closedness: Float, pageWidth: Float): Float = pageWidth / 2f * closedness.coerceIn(0f, 1f)

internal enum class BookTap { Open, Back, None }

/**
 * What a tap at [x] across a book [width] wide does, with the book slid left by [closedOffset]: the right-hand page
 * (or the shut cover) opens, the left-hand page turns back, and the empty table beside a shut book does nothing.
 */
internal fun bookTap(x: Float, width: Float, closedness: Float): BookTap {
    val shift = closedOffset(closedness, width / 2f)
    val spine = width / 2f - shift
    return when {
        x >= spine && x <= width - shift -> BookTap.Open
        x < spine && closedness < 1f -> BookTap.Back
        else -> BookTap.None
    }
}

/** Layers of page edges drawn under a stack of [pages] pages, growing with the share of the book in it. */
internal fun edgeLayers(pages: Int, total: Int): Int =
    if (total <= 0) 0 else ceil(pages.coerceAtLeast(0).toDouble() / total * MaxEdgeLayers).toInt().coerceIn(0, MaxEdgeLayers)

/** Pages whose first words to load, nearest first: the selected page, the rest of its spread, then either side. */
internal fun pagesToPreview(page: Int, total: Int): List<Int> =
    listOf(page, page - 1, page + 1, page - 2, page + 2).filter { it in 1..total }

/**
 * Up to [length] characters of [text] from [progression] (0 to 1) through it, starting on a word and ending before
 * a word the limit would cut. Readium's positions are estimates by resource size, so this is the page's likely start.
 */
internal fun excerptAt(text: String, progression: Double, length: Int = ExcerptLength): String {
    if (text.isEmpty() || length <= 0) return ""
    var start = (progression.coerceIn(0.0, 1.0) * text.length).toInt().coerceAtMost(text.length)
    while (start < text.length && text[start].isWhitespace()) start++
    // Landing inside a word: begin at the next one rather than on a fragment. Scripts written without spaces
    // (Chinese, Japanese, Thai) have no next word to find nearby, so they start where they land.
    if (start in 1 until text.length && !text[start - 1].isWhitespace()) {
        val next = text.indexOfFirst(start) { it.isWhitespace() }
        if (next in start..start + 24) {
            start = next
            while (start < text.length && text[start].isWhitespace()) start++
        }
    }
    val rest = text.substring(start)
    if (rest.length <= length) return rest.trimEnd()
    val cut = rest.lastIndexOf(' ', length).takeIf { it > length / 2 } ?: length
    return rest.substring(0, cut).trimEnd()
}

/**
 * [excerpt] without a leading repeat of the chapter's [title]: a chapter opening sets its title large already, and
 * books often start the chapter's text with the same heading, which Readium can't always tell apart from the prose
 * (a heading wrapped in an anchor reads as body text). Letters and digits are compared, as headings break lines
 * and punctuate differently from the contents entry.
 */
internal fun withoutTitle(excerpt: String, title: String): String {
    val wanted = title.filter(Char::isLetterOrDigit).lowercase()
    if (wanted.isEmpty()) return excerpt
    var matched = 0
    for ((index, char) in excerpt.withIndex()) {
        if (!char.isLetterOrDigit()) continue
        if (char.lowercaseChar() != wanted[matched]) return excerpt
        if (++matched == wanted.length) return excerpt.substring(index + 1).trimStart { it.isWhitespace() || it in ".:;,-–—" }
    }
    return excerpt
}

private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
    for (index in from until length) if (predicate(this[index])) return index
    return -1
}

/** A skeleton text line's width as a share of the column: most run full, about one in six ends a paragraph. Seeded per page. */
internal fun skeletonLineWidth(page: Int, line: Int): Float =
    if (seeded(page, line) < .18f) .30f + seeded(page, line + 40) * .40f else .88f + seeded(page, line + 80) * .12f

private fun seeded(page: Int, line: Int): Float {
    val x = sin(page * 127.1 + line * 311.7) * 43758.5453
    return (x - floor(x)).toFloat()
}

/** The book's haptic feels: the same moments in every gesture, so the book always feels the same under the thumb. */
internal enum class FlipFeel { PagePassed, PageLanded, ChapterCrossed, PageOpened }

/** The Android haptic constant for a feel on API level [sdk]. Newer constants are only returned for the levels that have them. */
@SuppressLint("InlinedApi")
internal fun FlipFeel.constant(sdk: Int): Int = when (this) {
    FlipFeel.PagePassed -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    FlipFeel.PageLanded -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    FlipFeel.ChapterCrossed -> HapticFeedbackConstants.CONTEXT_CLICK
    FlipFeel.PageOpened -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
}

/** The feel of passing from page [from] to [to] while riffling or scrubbing: a tick, or a heavier one into another chapter. */
internal fun moveFeel(chapters: List<BookChapter>, from: Int, to: Int): FlipFeel =
    if (chapterIndexAt(chapters, from) != chapterIndexAt(chapters, to)) FlipFeel.ChapterCrossed else FlipFeel.PagePassed

/** The feel of a turned page landing, heavier when it lands in another chapter. */
internal fun landFeel(chapters: List<BookChapter>, from: Int, to: Int): FlipFeel =
    if (chapterIndexAt(chapters, from) != chapterIndexAt(chapters, to)) FlipFeel.ChapterCrossed else FlipFeel.PageLanded

/** Caps page ticks at one per [PageTickGapMillis], as the system pickers do; other feels always fire. */
internal class TickThrottle {
    private var last: Long? = null
    fun allow(feel: FlipFeel, nowMillis: Long): Boolean {
        if (feel != FlipFeel.PagePassed) return true
        last?.let { if (nowMillis - it < PageTickGapMillis) return false }
        last = nowMillis
        return true
    }
}
