package app.folio.library

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs

/** The page picker's book on its own: gestures, keys and TalkBack actions land on the pages the design's numbers give. */
@OptIn(ExperimentalTestApi::class)
class FlipBookPickerTest {
    @get:Rule val compose = createComposeRule()
    private val chapters = listOf(BookChapter("CHAPTER I. Down the Rabbit-Hole", 3), BookChapter("CHAPTER II. The Pool of Tears", 10),
        BookChapter("CHAPTER V. Advice from a Caterpillar", 31), BookChapter("CHAPTER VI. Pig and Pepper", 38))
    private val opened = mutableListOf<Int>()

    private var accent = Color.Unspecified
    private var sheet = Color.Unspecified

    private fun show(reduceMotion: Boolean, current: Int = 34, paper: ReaderPaper = ReaderPaper.White) = compose.setContent {
        ReaderTheme(paper) {
            val colors = MaterialTheme.colorScheme
            SideEffect { accent = colors.primary; sheet = colors.surfaceContainerLow }
            PagePicker("Alice", "Lewis Carroll", null, paper, 82, current, chapters, { "Words on page $it" }, { opened += it }, {}, reduceMotion)
        }
    }
    private fun faces() = book.fetchSemanticsNode().config[BookFaces]
    private fun awaitFaces(expected: String) = compose.waitUntil(5000) { faces() == expected }
    /** Asserts the pixel at a fraction across and down the book is [color], give or take [tolerance] per channel. */
    private fun Bitmap.assertAt(what: String, x: Float, y: Float, color: Color, tolerance: Int) {
        val pixel = getPixel((width * x).toInt(), (height * y).toInt())
        val argb = color.toArgb()
        val near = listOf(16, 8, 0).all { shift -> abs((pixel shr shift and 0xFF) - (argb shr shift and 0xFF)) <= tolerance }
        assertTrue("$what at ($x, $y): #%06X, expected #%06X".format(pixel and 0xFFFFFF, argb and 0xFFFFFF), near)
    }
    private fun Bitmap.save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }
        dir.resolve("$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun awaitPage(page: Int) = compose.waitUntil(5000) { compose.onAllNodesWithText("Page $page of 82").fetchSemanticsNodes().isNotEmpty() }
    private val book get() = compose.onNodeWithTag("page-flip-book")
    private val edges get() = compose.onNodeWithTag("page-edge-scrubber")
    private fun swipe(forward: Boolean, millis: Long) = book.performTouchInput {
        if (forward) swipeLeft(right - 10f, left + width * .1f, millis) else swipeRight(left + 10f, right - width * .1f, millis)
    }

    @Test fun swipesKeysTalkBackAndEdgesTurnToTheRightPages() {
        show(reduceMotion = false)
        awaitPage(34)
        compose.onNodeWithText("V · Advice from a Caterpillar").assertIsDisplayed()
        // TalkBack reads "Book" with the page and chapter as its value, as VoiceOver does.
        book.assert(hasContentDescription("Book"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Page 34 of 82, V · Advice from a Caterpillar"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(34f, 1f..82f, 80)))

        // A swipe across the page turns it; the other way brings it back.
        swipe(forward = true, millis = 600); awaitPage(35)
        swipe(forward = false, millis = 600); awaitPage(34)

        // A page lifted quickly to about 51 degrees and held still before letting go falls back: the speed it had
        // before the pause isn't a fling. Held past upright, it turns.
        book.performTouchInput {
            down(Offset(right - 10f, centerY)); repeat(5) { moveBy(Offset(-width * .03f, 0f), 16) }; advanceEventTime(300); up()
        }
        compose.waitForIdle()
        awaitPage(34)
        book.performTouchInput {
            down(Offset(right - 10f, centerY)); repeat(20) { moveBy(Offset(-width * .03f, 0f), 16) }; advanceEventTime(300); up()
        }
        awaitPage(35)
        swipe(forward = false, millis = 600); awaitPage(34)

        // TalkBack adjusts the book directly, and its actions step a page.
        book.performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }
        awaitPage(40)
        compose.onNodeWithText("VI · Pig and Pepper").assertIsDisplayed()
        val actions = book.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread { actions.single { it.label == "Previous page" }.action() }
        awaitPage(39)

        // Arrow keys turn one page, or ten with Shift.
        book.requestFocus()
        book.performKeyInput { pressKey(Key.DirectionRight) }
        awaitPage(40)
        book.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
        awaitPage(50)

        // The edges jump to the page under the finger: the strip's ends are the first and last pages.
        edges.performTouchInput { down(Offset(0f, centerY)); up() }
        awaitPage(1)
        edges.performTouchInput { down(Offset(width - 1f, centerY)); up() }
        awaitPage(82)
        // Halfway along is page 42 (0.5 × 81 rounds to 41); lifting 60 dp switches to fine scrubbing, where the
        // remaining half-width moves a fifth as far: 42 + 0.5 × 81 × 0.2 = 50.1.
        edges.performTouchInput {
            down(Offset(width / 2f, centerY)); moveBy(Offset(0f, -60.dp.toPx())); moveBy(Offset(width / 2f - 1f, 0f)); up()
        }
        awaitPage(50)

        // Tapping the left page turns back; the right page, or the button, opens the page.
        book.performTouchInput { click(Offset(width * .25f, centerY)) }
        awaitPage(49)
        book.performTouchInput { click(Offset(width * .75f, centerY)) }
        compose.onNodeWithText("Open page 49").performClick()
        compose.runOnIdle { assertEquals(listOf(49, 49), opened) }
    }

    @Test fun pageOneIsAShutCoverThatOpensOntoTheInsideCover() {
        // Night: a black page on a dark sheet, so the cover, the pages and the bare table all differ.
        show(reduceMotion = false, current = 1, paper = ReaderPaper.Night)
        awaitPage(1)
        compose.onNode(hasText("Page 1 of 82") and hasText("Alice")).assertIsDisplayed()
        book.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Cover, page 1 of 82"))
        assertEquals("shut: none | cover (cloth)", faces())
        val cloth = clothColor(accent)
        val page = ReaderPaper.Night.page
        // Shut: the cover sits in the middle, a quarter to three quarters across; the table beside it is bare.
        book.captureToImage().asAndroidBitmap().let { shut ->
            shut.save("flipbook-cover-shut")
            for (x in listOf(.3f, .5f, .7f)) shut.assertAt("cover in the middle", x, .5f, cloth, 14)
            shut.assertAt("nothing left of the cover", .1f, .5f, sheet, 10)
        }

        // One turn opens it onto the back of the cover and page 2, never page 1's text.
        swipe(forward = true, millis = 600); awaitPage(2)
        awaitFaces("open: inside cover | page 2")
        book.captureToImage().asAndroidBitmap().let { open ->
            open.save("flipbook-inside-cover")
            for (x in listOf(.1f, .3f)) open.assertAt("inside cover on the left", x, .5f, page, 30)
            // An endpaper has only faint dots: no text line crosses the middle of the left page.
            for (step in 0..60) open.assertAt("no text on the inside cover", .25f, .2f + step * .01f, page, 30)
        }

        // Turning back shuts it again; so does scrubbing back to page 1 after leaving it.
        swipe(forward = false, millis = 600); awaitPage(1)
        awaitFaces("shut: none | cover (cloth)")
        edges.performTouchInput { down(Offset(width - 1f, centerY)); up() }
        awaitPage(82)
        awaitFaces("open: page 81 | page 82")
        edges.performTouchInput { down(Offset(0f, centerY)); up() }
        awaitPage(1)
        awaitFaces("shut: none | cover (cloth)")

        // Tapping the shut cover opens page 1.
        book.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf(1), opened) }
    }

    @Test fun aHardFlingRifflesNineteenPagesAfterItsOwn() {
        show(reduceMotion = false)
        awaitPage(34)
        swipe(forward = true, millis = 40)
        awaitPage(54)
    }

    @Test fun withAnimationsRemovedTurnsFadeAndRifflesJumpToTheSamePages() {
        show(reduceMotion = true)
        awaitPage(34)
        // Page 1 still shuts and opens, as a cross-fade.
        book.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        awaitPage(2)
        swipe(forward = false, millis = 600); awaitPage(1)
        awaitFaces("shut: none | cover (cloth)")
        swipe(forward = true, millis = 600); awaitPage(2)
        awaitFaces("open: inside cover | page 2")
        book.performSemanticsAction(SemanticsActions.SetProgress) { it(34f) }
        awaitPage(34)
        swipe(forward = true, millis = 600); awaitPage(35)
        swipe(forward = false, millis = 600); awaitPage(34)
        swipe(forward = true, millis = 40); awaitPage(54)
        book.requestFocus()
        book.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionLeft) } }
        awaitPage(44)
    }
}
