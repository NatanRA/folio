package app.folio.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The audiobook progress bar's motion: its rules and numbers are shared with the iOS player. */
class PlaybackMotionTest {
    /** The bar as drawn every 16 ms from [from] to [to], for checks that it never moves backwards. */
    private fun PlaybackProgress.frames(from: Long, to: Long) = (from..to step 16).map { shownAt(it) }
    private fun assertNeverBackwards(frames: List<Double>) = frames.zipWithNext().forEachIndexed { index, (before, after) ->
        assertTrue("Frame ${index + 1} went back from $before to $after", after >= before - 1e-9)
    }

    @Test fun numbersMatchTheIosPlayer() {
        assertEquals(180L, PlaybackProgress.EASE_MS)
        assertEquals(1500L, PlaybackProgress.HOLD_MS)
        assertEquals(1000L, PlaybackProgress.ARRIVED_MS)
        assertEquals(1500L, PlaybackProgress.CONVERGE_MS)
        assertEquals(1000.0, PlaybackProgress.CATCH_UP_MS, 0.0)
    }

    @Test fun threeQuickSkipsStackFromThePendingTarget() {
        // The player still reports 10 s for a while after each tap; the taps build on the jump, not on that.
        var progress = PlaybackProgress().report(10_000, 0.0, 0)
        for (tap in 0 until 3) {
            val now = 100L + tap * 120
            progress = progress.seek(progress.skipBase(10_000, now) + 30_000, 0.0, now)
            progress = progress.report(10_000, 0.0, now + 40)
        }
        assertEquals(100_000L, progress.pending!!.to)
        assertEquals(100_000.0, progress.shownAt(1000), 0.0)
        // Once the hold has run out a skip counts from the player again.
        assertEquals(10_000L, progress.skipBase(10_000, 340 + PlaybackProgress.HOLD_MS))
    }

    @Test fun aJumpEasesInOver180msAndNeverShowsTheOldPositionAgain() {
        val progress = PlaybackProgress().report(10_000, 0.0, 0).seek(40_000, 0.0, 1000)
        assertEquals(10_000.0, progress.shownAt(1000), 0.0)
        val halfway = progress.shownAt(1090)
        assertTrue("Halfway through the ease the bar is on its way, at $halfway", halfway > 25_000 && halfway < 40_000)
        assertEquals(40_000.0, progress.shownAt(1180), 0.0)
        assertNeverBackwards(progress.frames(1000, 1400))
        // Stale reports of where playback was are ignored while the jump is pending…
        var held = progress
        for (at in 1200L..2400L step 100) {
            held = held.report(10_000, 0.0, at)
            assertEquals("A stale report at $at pulled the bar back", 40_000.0, held.shownAt(at), 0.0)
        }
        // …and one near the target lands it.
        assertEquals(null, held.report(40_020, 0.0, 2450).pending)
    }

    @Test fun theHoldEndsAfterOneAndAHalfSeconds() {
        // The counterexample: a player that never reaches the jump is believed once the hold runs out.
        val progress = PlaybackProgress().seek(40_000, 0.0, 0)
        assertEquals(40_000.0, progress.report(10_000, 0.0, 1499).shownAt(1499), 0.0)
        assertEquals(10_000.0, progress.report(10_000, 0.0, 1500).shownAt(1500), 0.0)
    }

    @Test fun extrapolatesBetweenReportsAtThePlaybackSpeed() {
        val progress = PlaybackProgress().report(10_000, 1.5, 0)
        assertEquals(11_500.0, progress.shownAt(1000), 1e-9)
        assertTrue(progress.movingAt(1000))
        assertFalse(PlaybackProgress().report(10_000, 0.0, 0).movingAt(0))
    }

    @Test fun aLateStartAfterAJumpSlowsTheBarInsteadOfPullingItBack() {
        // Playing at 1×, jump to 40 s; the audio only restarts 350 ms later, so the first reports trail the bar.
        var progress = PlaybackProgress().report(10_000, 1.0, 0).seek(40_000, 1.0, 1000)
        val frames = mutableListOf<Double>()
        var time = 1000L
        for (report in 1500L..4000L step 500) {
            frames += progress.frames(time, report - 1)
            val audio = 40_000 + (report - 1350).coerceAtLeast(0)
            progress = progress.report(audio, 1.0, report)
            val gap = audio - progress.shownAt(report)
            assertTrue("The bar never moves backwards to meet a report, rate ${progress.clock.rate}", progress.clock.rate >= 0)
            if (report == 1500L) assertEquals(-350.0, gap, 1.0)
            time = report
        }
        assertNeverBackwards(frames)
        // A few reports later it draws where the audio is.
        assertEquals(40_000.0 + 4000 - 1350, progress.shownAt(4000), 25.0)
    }

    @Test fun aSmallDriftIsMetOneSecondLaterWithoutAStep() {
        // Drawn at 20.1 s, the player says 20 s: the bar slows to 0.9× so it meets playback a second later.
        val progress = PlaybackProgress().report(20_100, 1.0, 0).report(20_000, 1.0, 0)
        assertEquals(20_100.0, progress.shownAt(0), 1e-9)
        assertEquals(0.9, progress.clock.rate, 1e-9)
        assertEquals(21_000.0, progress.shownAt(1000), 1e-9)
        // A bar more than a second ahead stops until playback catches up; it never runs backwards.
        assertEquals(0.0, PlaybackProgress().report(21_400, 1.0, 0).report(20_000, 1.0, 0).clock.rate, 0.0)
    }

    @Test fun aBigDifferenceJumpsAndAPausedBarShowsExactlyThePlayer() {
        // The lock screen or another device moved playback 60 s on.
        assertEquals(70_500.0, PlaybackProgress().report(10_000, 1.0, 0).report(70_500, 1.0, 500).shownAt(500), 0.0)
        // Paused, even a small difference is shown as it is.
        assertEquals(10_300.0, PlaybackProgress().report(10_000, 1.0, 0).report(10_300, 0.0, 400).shownAt(400), 0.0)
    }

    @Test fun aJumpIntoAnotherChapterLandsDirectly() {
        val chapters = listOf(AudioChapter("One", 0), AudioChapter("Two", 60_000))
        val within = PlaybackProgress().report(20_000, 0.0, 0).seek(50_000, 0.0, 0, chapters = chapters)
        assertEquals(20_000.0, within.shownAt(0), 0.0)
        val across = PlaybackProgress().report(50_000, 0.0, 0).seek(65_000, 0.0, 0, chapters = chapters)
        assertEquals(65_000.0, across.shownAt(0), 0.0)
        // A drag already put the bar where it lets go.
        assertEquals(50_000.0, PlaybackProgress().report(20_000, 0.0, 0).seek(50_000, 0.0, 0, eased = false).shownAt(0), 0.0)
        assertEquals(0, chapterIndexAt(chapters, 59_999.0))
        assertEquals(1, chapterIndexAt(chapters, 60_000.0))
        assertEquals(0, chapterIndexAt(emptyList(), 5_000.0))
    }

    @Test fun easingOnlyAsksForFramesWhileItRuns() {
        val progress = PlaybackProgress().seek(40_000, 0.0, 0)
        assertTrue(progress.movingAt(100))
        assertFalse(progress.movingAt(PlaybackProgress.EASE_MS))
    }

    @Test fun labelsCountWholeSecondsWithinTheChapter() {
        assertEquals(12_000L, elapsedLabelMs(72_999.0, 60_000, 30_000))
        assertEquals(0L, elapsedLabelMs(59_000.0, 60_000, 30_000))
        assertEquals(30_000L, elapsedLabelMs(95_000.0, 60_000, 30_700))
        assertEquals(0L, elapsedLabelMs(5_000.0, 0, 0))
    }
}
