package com.natan.mediaportal

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The mini player and the full player are one card on one spring: every tap, Back and drag turns it around from
 * wherever it is, and nothing waits for an animation to finish. The test clock steps through the motion.
 */
class PlayerMorphTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun card(): Rect? = compose.onAllNodesWithTag("audio-player").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
    private val screen get() = compose.onRoot().fetchSemanticsNode().boundsInRoot
    private val mini get() = compose.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
    private fun assertOpen() {
        val card = card()
        assertTrue("The player should cover the screen, is at $card", card != null && card.height >= screen.height - 1f && card.top <= screen.top + 1f)
        compose.onNodeWithContentDescription("Close player").assertExists()
    }
    private fun assertClosed() {
        assertEquals("The card leaves nothing over the app", null, card())
        compose.onNodeWithTag("mini-player").assertIsDisplayed()
    }
    private fun openFromMini() = compose.onNodeWithContentDescription("Open player", substring = true).performClick()

    /** Opens a fresh audiobook's player from its page, closes it into the mini player, then runs [check] from there. */
    private fun fromTheMiniPlayer(check: () -> Unit) = runBlocking {
        val store = LibraryStore(context); val before = store.all()
        val file = File(context.cacheDir, "morph.mp3").also { copy -> instrumentation.context.assets.open("chaptered.mp3").use { input -> copy.outputStream().use { input.copyTo(it) } } }
        val title = store.import(Uri.fromFile(file), true, null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
                compose.onNodeWithText(title.name).performClick()
                // With nothing playing there is no mini player: the card grows up from the bottom edge.
                assertTrue(compose.onAllNodesWithTag("mini-player").fetchSemanticsNodes().isEmpty())
                compose.mainClock.autoAdvance = false
                compose.onNodeWithTag("audio-format").performScrollTo().performClick()
                compose.mainClock.advanceTimeBy(80)
                card()!!.let { rising ->
                    assertEquals(screen.width, rising.width, 1f)
                    assertEquals(screen.bottom, rising.bottom, 1f)
                    assertTrue("The card should be rising, is at $rising", rising.top > screen.top + 1f && rising.top < screen.bottom)
                }
                compose.mainClock.autoAdvance = true
                compose.waitUntil(10000) { compose.onAllNodesWithText("First chapter").fetchSemanticsNodes().isNotEmpty() }
                compose.waitForIdle()
                assertOpen()
                compose.onNodeWithContentDescription("Close player").performClick()
                compose.waitForIdle()
                assertClosed()
                check()
            }
        } finally {
            compose.mainClock.autoAdvance = true
            store.save(before); file.delete(); title.audio?.let { store.file(it).delete() }; store.file("${title.id}.jpg").delete()
            context.getSharedPreferences("progress", 0).edit().remove("audio:${title.id}").remove("audioModified:${title.id}").remove("lastOpened:${title.id}").commit()
        }
    }

    @Test fun closingEightyMillisecondsIntoTheOpeningShrinksItBackFromThere() = fromTheMiniPlayer {
        compose.mainClock.autoAdvance = false
        openFromMini()
        compose.mainClock.advanceTimeBy(80)
        val opening = card()!!
        assertTrue("The card should be part-way open, is at $opening", opening.height > mini.height + 1f && opening.height < screen.height * .9f)
        compose.onNodeWithContentDescription("Close player").performClick()
        // The spring keeps its velocity, so the card may rise a little further before turning; it never opens fully.
        var tallest = opening.height
        repeat(90) { compose.mainClock.advanceTimeByFrame(); card()?.let { tallest = maxOf(tallest, it.height) } }
        assertTrue("After the close the card should not open fully: reached $tallest of ${screen.height}", tallest < screen.height * .97f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertClosed()
    }

    @Test fun tappingTheShrinkingCardOrTheMiniPlayerOpensItAgainAtOnce() = fromTheMiniPlayer {
        openFromMini()
        compose.waitForIdle()
        assertOpen()
        for (target in listOf("the card", "the mini player")) {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("Close player").performClick()
            compose.mainClock.advanceTimeBy(80)
            val shrinking = card()!!
            assertTrue("$target: the card should be shrinking, is at $shrinking", shrinking.height < screen.height * .97f)
            // The shrinking card covers the mini player, so either tap lands on the card and reopens it.
            if (target == "the card") compose.onNodeWithTag("audio-player").performClick()
            else compose.onNodeWithTag("mini-player").performTouchInput { click() }
            compose.mainClock.advanceTimeBy(1000)
            assertOpen()
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun aDragFollowsTheFingerAndSettlesByHowFarItWent() = fromTheMiniPlayer {
        openFromMini()
        compose.waitForIdle()
        assertOpen()
        val player = compose.onNodeWithTag("audio-player")
        // Held, the card's top moves with the finger, one to one.
        compose.mainClock.autoAdvance = false
        player.performTouchInput { down(Offset(centerX, height * .3f)); moveBy(Offset(0f, 60f)); moveBy(Offset(0f, 60f)) }
        compose.mainClock.advanceTimeByFrame()
        val first = card()!!.top
        player.performTouchInput { moveBy(Offset(0f, 200f)) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(first + 200f, card()!!.top, 2f)
        // Let go slowly less than a third of the way down: it springs back open.
        compose.mainClock.advanceTimeBy(300)
        player.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertOpen()
        // A finger catches a card that is still shrinking: it stops under the finger and follows it from there.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Close player").performClick()
        compose.mainClock.advanceTimeBy(80)
        player.performTouchInput { down(Offset(centerX, height * .3f)); moveBy(Offset(0f, -60f)); moveBy(Offset(0f, -60f)) }
        compose.mainClock.advanceTimeByFrame()
        val caught = card()!!.top
        compose.mainClock.advanceTimeBy(200)
        assertEquals("Held, the card stays under the finger", caught, card()!!.top, 1f)
        player.performTouchInput { moveBy(Offset(0f, -300f)) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(caught - 300f, card()!!.top, 2f)
        // Pulled back up and let go on the way up, it opens again.
        player.performTouchInput { moveBy(Offset(0f, -600f)); up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertOpen()
        // Dragged past a third of the way, slower than a fling, it collapses into the mini player.
        player.performTouchInput { swipeDown(height * .2f, height * .2f + height * .45f, 600) }
        compose.waitForIdle()
        assertClosed()
        // A short, fast flick down collapses it too.
        openFromMini()
        compose.waitForIdle()
        player.performTouchInput { swipeDown(height * .3f, height * .3f + height * .1f, 60) }
        compose.waitForIdle()
        assertClosed()
    }
}
