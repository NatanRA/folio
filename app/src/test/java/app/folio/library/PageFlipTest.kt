package app.folio.library

import android.view.HapticFeedbackConstants
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** The page picker's book: its numbers are the flipbook design's, shared with the iOS picker. */
class PageFlipTest {
    // Alice's table of contents, shaped as Project Gutenberg's EPUB gives it: three entries in the front matter file.
    private val toc = listOf(
        "Alice’s Adventures in Wonderland" to "OEBPS/h-0.xhtml#pgepubid00000",
        "THE MILLENNIUM FULCRUM EDITION 3.0" to "OEBPS/h-0.xhtml#pgepubid00001",
        "Contents" to "OEBPS/h-0.xhtml#pgepubid00002",
        "CHAPTER I. Down the Rabbit-Hole" to "OEBPS/h-1.xhtml#pgepubid00003",
        "CHAPTER II. The Pool of Tears" to "OEBPS/h-2.xhtml#pgepubid00004",
        "Not in the book" to "OEBPS/missing.xhtml",
    )
    // Positions: a cover wrapper, then 3 pages of front matter, 5 of chapter I and 4 of chapter II.
    private val positions = listOf("OEBPS/wrap.xhtml") + List(3) { "OEBPS/h-0.xhtml" } + List(5) { "OEBPS/h-1.xhtml" } + List(4) { "OEBPS/h-2.xhtml" }

    @Test fun chaptersStartAtTheFirstPageOfTheirResource() {
        val chapters = bookChapters(toc, positions)
        assertEquals(listOf(2, 5, 10), chapters.map { it.start })
        // Entries sharing a page keep the first; an entry outside the reading order is left out.
        assertEquals(listOf("Alice’s Adventures in Wonderland", "CHAPTER I. Down the Rabbit-Hole", "CHAPTER II. The Pool of Tears"), chapters.map { it.title })
        // Out-of-order entries are placed by page.
        assertEquals(listOf(5, 10), bookChapters(listOf(toc[4], toc[3]), positions).map { it.start })
    }

    @Test fun aPageBelongsToTheLastChapterStartingAtOrBeforeIt() {
        val chapters = bookChapters(toc, positions)
        assertEquals(-1, chapterIndexAt(chapters, 1))
        assertEquals(0, chapterIndexAt(chapters, 2))
        assertEquals(0, chapterIndexAt(chapters, 4))
        assertEquals(1, chapterIndexAt(chapters, 5))
        assertEquals(1, chapterIndexAt(chapters, 9))
        assertEquals(2, chapterIndexAt(chapters, 10))
        assertEquals(2, chapterIndexAt(chapters, 99))
        assertEquals(-1, chapterIndexAt(emptyList(), 3))
    }

    @Test fun numberedTitlesSplitIntoLabelAndName() {
        BookChapter("CHAPTER I. Down the Rabbit-Hole", 5).let {
            assertEquals("CHAPTER I", it.label); assertEquals("Down the Rabbit-Hole", it.name); assertEquals("I · Down the Rabbit-Hole", it.caption)
        }
        BookChapter("Chapter 3: The Storm", 1).let { assertEquals("CHAPTER 3", it.label); assertEquals("The Storm", it.name); assertEquals("3 · The Storm", it.caption) }
        BookChapter("Part ii — The Sea", 1).let { assertEquals("PART II", it.label); assertEquals("The Sea", it.name); assertEquals("Part II · The Sea", it.caption) }
        BookChapter("IV. Pig and Pepper", 1).let { assertEquals("CHAPTER IV", it.label); assertEquals("Pig and Pepper", it.name) }
        BookChapter("Chapter XIV: Home", 1).let { assertEquals("CHAPTER XIV", it.label); assertEquals("Home", it.name) }
        // Nothing to split: no invented numbers, no words read as numerals, and no label repeated as the name.
        for (title in listOf("Contents", "Chapter 12", "Part of the Plan", "1984: A Novel", "Down the Rabbit-Hole",
            "Chapter Mild Mischief", "Section Civil Law", "Mild. Up")) {
            val chapter = BookChapter(title, 1)
            assertNull(title, chapter.label); assertEquals(title, chapter.name); assertEquals(title, chapter.caption)
        }
    }

    @Test fun aReleasedPageFinishesPastUprightOrWhenFlungOn() {
        assertTrue(commitsTurn(91f, 0f))
        assertFalse(commitsTurn(90f, 0f))
        assertTrue(commitsTurn(10f, 351f))
        assertFalse(commitsTurn(10f, 350f))
        // A fling back the other way doesn't finish a turn that hasn't passed upright.
        assertFalse(commitsTurn(80f, -2000f))
        assertTrue(commitsTurn(120f, -2000f))
    }

    @Test fun theFingerTurnsThePageAlmostAsFarAsItMoves() {
        assertEquals(0f, turnedDegrees(0f, 300f), 0f)
        assertEquals(85.5f, turnedDegrees(150f, 300f), .001f)
        assertEquals(171f, turnedDegrees(300f, 300f), .001f)
        assertEquals(180f, turnedDegrees(900f, 300f), 0f)
        assertEquals(0f, turnedDegrees(-40f, 300f), 0f)
    }

    @Test fun hardFlingsRiffleExtraPages() {
        // extra = min(19, round((v - 1100) / 110)) for v over 1100 dp/s.
        assertEquals(0, riffleCount(0f))
        assertEquals(0, riffleCount(1100f))
        assertEquals(0, riffleCount(1150f))   // 0.45 rounds down
        assertEquals(1, riffleCount(1210f))
        assertEquals(5, riffleCount(1650f))
        assertEquals(19, riffleCount(3190f))
        assertEquals(19, riffleCount(9000f))
    }

    @Test fun scrubbingJumpsAbsolutelyOrFinelyOnceTheFingerRises() {
        assertEquals(1, scrubPage(0f, 400f, 82))
        assertEquals(82, scrubPage(400f, 400f, 82))
        assertEquals(42, scrubPage(200f, 400f, 82))   // 0.5 × 81 = 40.5, rounded up, plus 1
        assertEquals(1, scrubPage(-30f, 400f, 82))
        assertEquals(82, scrubPage(500f, 400f, 82))
        assertEquals(1, scrubPage(123f, 400f, 1))
        assertFalse(isFineScrub(44f))
        assertTrue(isFineScrub(45f))
        // Fine: a whole strip's width moves a fifth of the book from where fine mode began.
        assertEquals(50 + 16, fineScrubPage(50, 400f, 400f, 82))   // 81 × 0.2 = 16.2
        assertEquals(50 - 8, fineScrubPage(50, -200f, 400f, 82))   // 8.1
        assertEquals(1, fineScrubPage(3, -400f, 400f, 82))
        assertEquals(82, fineScrubPage(80, 400f, 400f, 82))
    }

    @Test fun edgesThickenWithThePagesInEachStack() {
        assertEquals(0, edgeLayers(0, 82))
        assertEquals(1, edgeLayers(1, 82))
        assertEquals(4, edgeLayers(41, 82))   // 3.5 rounds up
        assertEquals(7, edgeLayers(82, 82))
        assertEquals(0, edgeLayers(5, 0))
    }

    @Test fun previewsLoadForTheSpreadThenItsNeighbours() {
        assertEquals(listOf(34, 33, 35, 32, 36), pagesToPreview(34, 82))
        assertEquals(listOf(1, 2, 3), pagesToPreview(1, 82))
        assertEquals(listOf(82, 81, 80), pagesToPreview(82, 82))
    }

    @Test fun excerptsStartOnAWordAtTheProgressionAndEndBeforeACutWord() {
        val text = "Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do: " +
            "once or twice she had peeped into the book her sister was reading, but it had no pictures or conversations in it"
        assertEquals("Alice was beginning", excerptAt(text, 0.0, 20))
        // 0.035 × 217 = 7.6: character 7 is inside "was", so start at the next word.
        assertEquals("beginning to get", excerptAt(text, 0.035, 18))
        // Landing on a word's first letter keeps it.
        assertEquals("was beginning to get", excerptAt(text, 6.0 / text.length, 20))
        val full = excerptAt(text, 0.0)
        assertTrue(full.length <= ExcerptLength)
        assertTrue(text.startsWith(full) && text[full.length] == ' ')
        assertEquals("", excerptAt("", .5))
        assertEquals("", excerptAt(text, 1.0))
    }

    @Test fun aChapterOpeningDropsItsRepeatedHeading() {
        // Project Gutenberg's heading: "CHAPTER II.<br/>The Pool of Tears", read as prose before the first paragraph.
        assertEquals("“Curiouser and curiouser!” cried Alice",
            withoutTitle("CHAPTER II. The Pool of Tears “Curiouser and curiouser!” cried Alice", "CHAPTER II. The Pool of Tears"))
        assertEquals("Alice was beginning", withoutTitle("Down the Rabbit-Hole: Alice was beginning", "Down the Rabbit Hole"))
        // Prose that merely starts like the title, or doesn't, is left alone.
        assertEquals("The Pool was deep", withoutTitle("The Pool was deep", "The Pool of Tears"))
        assertEquals("Curiouser", withoutTitle("Curiouser", "The Pool of Tears"))
        assertEquals("Anything", withoutTitle("Anything", "…"))
    }

    @Test fun textWithoutSpacesStartsWhereItLands() {
        // Chinese, Japanese and Thai are written without spaces between words: there is no next word to skip to.
        val text = "爱丽丝靠着姐姐坐在河岸边很久了，由于没有什么事情可做，她开始感到厌倦。她一次又一次地瞧瞧姐姐正在读的那本书"
        assertEquals(text.substring(10, 20), excerptAt(text, 10.0 / text.length, 10))
        assertTrue(excerptAt(text, .5).isNotEmpty())
    }

    @Test fun talkBackAdjustmentsAlwaysMoveAtLeastAPage() {
        // TalkBack steps a range by a fraction; on a two-page book that fraction alone would round back.
        assertEquals(2, adjustedPage(1, 1.05f, 2))
        assertEquals(1, adjustedPage(2, 1.95f, 2))
        assertEquals(40, adjustedPage(34, 40f, 82))
        assertEquals(35, adjustedPage(34, 34.2f, 82))
        assertEquals(34, adjustedPage(34, 34f, 82))
        assertEquals(82, adjustedPage(81, 90f, 82))
    }

    @Test fun pageOneIsTheCoverOnTheRightAndTheInsideCoverOnTheLeft() {
        // Shut on page 1: nothing on the left, the cover on the right.
        assertEquals(PageFace.None, pageFace(0, onLeft = true))
        assertEquals(PageFace.Cover, pageFace(1, onLeft = false))
        // Page 2's spread: the back of the cover, never page 1's text, then page 2.
        assertEquals(PageFace.InsideCover, pageFace(1, onLeft = true))
        assertEquals(PageFace.Text, pageFace(2, onLeft = false))
        // Page 3 onward: page n − 1 and page n.
        assertEquals(PageFace.Text, pageFace(2, onLeft = true))
        assertEquals(PageFace.Text, pageFace(3, onLeft = false))
    }

    @Test fun theBookSlidesFromShutToOpenAsTheCoverTurns() {
        // Half a page width × (1 − turned / 180) while the cover opens; pages 200 px wide.
        assertEquals(100f, closedOffset(coverClosedness(forward = true, turned = 0f), 200f), 0f)
        assertEquals(50f, closedOffset(coverClosedness(forward = true, turned = 90f), 200f), .001f)
        assertEquals(25f, closedOffset(coverClosedness(forward = true, turned = 135f), 200f), .001f)
        assertEquals(0f, closedOffset(coverClosedness(forward = true, turned = 180f), 200f), 0f)
        // Closing from page 2 runs the other way, sliding back to the middle as the cover lands.
        assertEquals(0f, closedOffset(coverClosedness(forward = false, turned = 0f), 200f), 0f)
        assertEquals(75f, closedOffset(coverClosedness(forward = false, turned = 135f), 200f), .001f)
        assertEquals(100f, closedOffset(coverClosedness(forward = false, turned = 180f), 200f), 0f)
    }

    @Test fun tappingAShutBookOpensItsCoverAndTheTableBesideItDoesNothing() {
        // A book 400 px wide; shut, it slides 100 px left, so the cover spans 100 to 300.
        assertEquals(BookTap.Open, bookTap(110f, 400f, 1f))
        assertEquals(BookTap.Open, bookTap(290f, 400f, 1f))
        assertEquals(BookTap.None, bookTap(60f, 400f, 1f))
        assertEquals(BookTap.None, bookTap(340f, 400f, 1f))
        // Open: the right-hand page opens, the left-hand one turns back.
        assertEquals(BookTap.Open, bookTap(300f, 400f, 0f))
        assertEquals(BookTap.Back, bookTap(100f, 400f, 0f))
    }

    @Test fun clothCoversKeepTheirLetteringReadable() {
        val accents = ReaderPaper.entries.mapNotNull { it.accent } +
            PortalPalettes.flatMap { listOf(paletteAccent(it, dark = false), paletteAccent(it, dark = true)) } +
            listOf(Color.White, Color(0xFFFFFF00), Color(0xFF00FFFF))
        for (accent in accents) {
            val cloth = clothColor(accent)
            assertTrue("title on the cloth for $accent", contrast(CoverInk, cloth) >= 4.5)
            assertTrue("author on the cloth for $accent", contrast(CoverInk.copy(alpha = .85f).compositeOver(cloth), cloth) >= 4.5)
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        fun channel(c: Float) = if (c <= .03928f) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
        fun luminance(c: Color) = .2126 * channel(c.red) + .7152 * channel(c.green) + .0722 * channel(c.blue)
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (light + .05) / (dark + .05)
    }

    @Test fun skeletonLinesAreSeededAndInRange() {
        for (page in 1..40) for (line in 0..20) {
            val width = skeletonLineWidth(page, line)
            assertTrue(width in .30f..1f)
            assertEquals(width, skeletonLineWidth(page, line), 0f)
        }
    }

    @Test fun movesTickAndChapterCrossingsThud() {
        val chapters = bookChapters(toc, positions)
        assertEquals(FlipFeel.PagePassed, moveFeel(chapters, 6, 7))
        assertEquals(FlipFeel.ChapterCrossed, moveFeel(chapters, 9, 10))
        assertEquals(FlipFeel.ChapterCrossed, moveFeel(chapters, 10, 9))   // either direction
        assertEquals(FlipFeel.ChapterCrossed, moveFeel(chapters, 3, 11))
        assertEquals(FlipFeel.ChapterCrossed, moveFeel(chapters, 1, 2))    // from before the first entry
        assertEquals(FlipFeel.PageLanded, landFeel(chapters, 6, 7))
        assertEquals(FlipFeel.ChapterCrossed, landFeel(chapters, 5, 4))
    }

    @Test fun feelsUseTheRichestConstantEachAndroidHas() {
        assertEquals(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, FlipFeel.PagePassed.constant(34))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, FlipFeel.PagePassed.constant(33))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, FlipFeel.PageLanded.constant(34))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, FlipFeel.PageLanded.constant(33))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, FlipFeel.ChapterCrossed.constant(26))
        assertEquals(HapticFeedbackConstants.CONFIRM, FlipFeel.PageOpened.constant(30))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, FlipFeel.PageOpened.constant(29))
    }

    @Test fun pageTicksAreCappedButOtherFeelsAlwaysFire() {
        val throttle = TickThrottle()
        assertTrue(throttle.allow(FlipFeel.PagePassed, 1000))
        assertFalse(throttle.allow(FlipFeel.PagePassed, 1031))
        assertTrue(throttle.allow(FlipFeel.ChapterCrossed, 1031))
        assertTrue(throttle.allow(FlipFeel.PageLanded, 1031))
        assertTrue(throttle.allow(FlipFeel.PagePassed, 1032))
        assertFalse(throttle.allow(FlipFeel.PagePassed, 1040))
    }
}
