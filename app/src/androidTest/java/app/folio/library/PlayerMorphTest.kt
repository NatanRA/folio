package app.folio.library

import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    /**
     * The frame rates the app's views ask the display for, noted on every frame drawn while [block] runs. The values
     * are set as each frame is drawn and replaced by the next one, so they are read frame by frame.
     */
    private fun requestedFrameRates(block: () -> Unit): Set<Float> {
        val rates = java.util.concurrent.ConcurrentHashMap.newKeySet<Float>()
        var recording = true
        fun collect(view: View) { rates += view.requestedFrameRate; (view as? ViewGroup)?.let { group -> (0 until group.childCount).forEach { collect(group.getChildAt(it)) } } }
        val frame = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { collect(it.window.decorView) }
                if (recording) android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
        instrumentation.runOnMainSync { android.view.Choreographer.getInstance().postFrameCallback(frame) }
        try { block(); Thread.sleep(300) } finally { recording = false }
        return rates
    }
    private fun redrawEverything() = instrumentation.runOnMainSync {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { activity ->
            fun invalidate(view: View) { view.invalidate(); (view as? ViewGroup)?.let { group -> (0 until group.childCount).forEach { invalidate(group.getChildAt(it)) } } }
            invalidate(activity.window.decorView)
        }
    }

    @Test fun theCardAsksForTheDisplaysHighestRateOnlyWhileItMoves() = fromTheMiniPlayer {
        // Android 15 takes a frame rate category from the views; earlier versions choose a display mode instead.
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val moving = requestedFrameRates {
            openFromMini()
            compose.waitForIdle()
        }
        android.util.Log.i("PlayerMorphTest", "rates while moving: $moving")
        assertTrue("Moving, the card should ask for a high rate: $moving", View.REQUESTED_FRAME_RATE_CATEGORY_HIGH in moving)
        assertOpen()
        // At rest, frames drawn ask for nothing in particular, so the display can slow down again.
        val resting = requestedFrameRates { repeat(5) { redrawEverything(); Thread.sleep(50) } }
        android.util.Log.i("PlayerMorphTest", "rates at rest: $resting")
        assertFalse("At rest, the card shouldn't keep the display at a high rate: $resting", View.REQUESTED_FRAME_RATE_CATEGORY_HIGH in resting)
    }

    @Test fun theFrameRateVoteComesFromTheMotionAloneAndNeedsNoRecomposition() {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        var progress by mutableFloatStateOf(0f)
        // Stands in for the playing progress bar, which redraws every frame while the open player rests.
        var tick by mutableFloatStateOf(0f)
        var compositions = 0
        ActivityScenario.launch(androidx.activity.ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SideEffect { compositions++ }
                    HighFrameRate(moving = { true }, progress = { progress })
                    androidx.compose.foundation.layout.Box(Modifier.size(40.dp).graphicsLayer().drawBehind { drawRect(Color.Red.copy(alpha = tick % 1f)) })
                }
            }
            compose.waitForIdle()
            val composed = compositions
            // Nothing else moves or resizes, so a high vote here is the progress layer's own.
            val moving = requestedFrameRates { repeat(20) { instrumentation.runOnMainSync { progress += .05f }; Thread.sleep(16) } }
            assertTrue("A changing progress should ask for a high rate: $moving", View.REQUESTED_FRAME_RATE_CATEGORY_HIGH in moving)
            assertEquals("Following the progress mustn't recompose", composed, compositions)
            // The last moving frame's vote stays on the views until another frame is drawn.
            repeat(3) { instrumentation.runOnMainSync { tick += .1f }; Thread.sleep(50) }
            val still = requestedFrameRates { repeat(20) { instrumentation.runOnMainSync { tick += .01f }; Thread.sleep(16) } }
            android.util.Log.i("PlayerMorphTest", "isolated rates: moving $moving, still while something else redraws $still")
            assertFalse("A still progress shouldn't, even while something else redraws: $still", View.REQUESTED_FRAME_RATE_CATEGORY_HIGH in still)
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
