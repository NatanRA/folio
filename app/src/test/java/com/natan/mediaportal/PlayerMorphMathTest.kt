package com.natan.mediaportal

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/** The mini player ↔ full player morph: its numbers are shared with the iOS player. */
class PlayerMorphMathTest {
    @Test fun theSpringAnswersInAboutFourTenthsOfASecond() {
        assertEquals(.86f, MorphDamping, 0f)
        assertEquals(224f, MorphStiffness, 0f)
        // A spring's response is 2π / √stiffness for a unit mass.
        assertEquals(.42, 2 * PI / sqrt(MorphStiffness.toDouble()), .005)
    }

    @Test fun rampsHoldToTheirWindow() {
        assertEquals(0f, ramp(.05f, .1f, .5f), 0f)
        assertEquals(.5f, ramp(.3f, .1f, .5f), 1e-6f)
        assertEquals(1f, ramp(.9f, .1f, .5f), 0f)
        assertEquals(1f, ramp(1.02f, 0f, .12f), 0f)   // a spring's overshoot stays at the end
        assertEquals(0f, ramp(-.01f, 0f, .12f), 0f)
    }

    @Test fun theCardTravelsBetweenTheCapsuleAndTheScreen() {
        val capsule = Rect(74f, 2158f, 1006f, 2305f)
        val screen = Rect(0f, 0f, 1080f, 2400f)
        assertEquals(capsule, lerpRect(capsule, screen, 0f))
        assertEquals(screen, lerpRect(capsule, screen, 1f))
        assertEquals(Rect(37f, 1079f, 1043f, 2352.5f), lerpRect(capsule, screen, .5f))
    }

    @Test fun aDragFollowsTheFingerOneToOne() {
        // The card's top travels 2158 px from open to the capsule: a finger moving 719.3 px down takes a third of it.
        assertEquals(1f - 719.3f / 2158f, draggedExpansion(1f, 719.3f, 2158f), 1e-6f)
        assertEquals(.5f, draggedExpansion(.25f, -539.5f, 2158f), 1e-6f)   // dragging up opens it
        assertEquals(0f, draggedExpansion(.2f, 900f, 2158f), 0f)
        assertEquals(1f, draggedExpansion(.9f, -900f, 2158f), 0f)
        assertEquals(.7f, draggedExpansion(.7f, 100f, 0f), 0f)
        // The finger's velocity carries into the spring in the same units: down is closing.
        assertEquals(-1f, expansionVelocity(2158f, 2158f), 1e-6f)
        assertEquals(.5f, expansionVelocity(-1079f, 2158f), 1e-6f)
    }

    @Test fun aReleaseCollapsesOnAFlingDownOrPastAThird() {
        assertTrue(settlesOpen(.7f, 0f))
        assertFalse(settlesOpen(.66f, 0f))          // dragged just over a third of the way
        assertTrue(settlesOpen(1f - MorphDismissShare, 0f))
        assertFalse(settlesOpen(.95f, 801f))        // a downward fling collapses from anywhere
        assertTrue(settlesOpen(.95f, 800f))
        assertTrue(settlesOpen(.3f, -801f))         // an upward fling opens from anywhere
        assertFalse(settlesOpen(.3f, -500f))
    }

    @Test fun beforeAndroid15TheMorphPicksTheFastestModeAtTheSameResolution() {
        val modes = listOf(DisplayModeChoice(1, 1080, 2400, 60f), DisplayModeChoice(2, 1080, 2400, 120f),
            DisplayModeChoice(3, 1440, 3200, 144f), DisplayModeChoice(4, 1080, 2400, 90f))
        // 144 Hz exists only at another resolution, which would resize the window mid-motion.
        assertEquals(2, fastestDisplayMode(modes, 1080, 2400))
        assertEquals(3, fastestDisplayMode(modes + DisplayModeChoice(5, 1440, 3200, 60f), 1440, 3200))
        // With one rate on offer there is nothing to ask for, and the system keeps choosing.
        assertEquals(null, fastestDisplayMode(listOf(DisplayModeChoice(1, 1080, 2400, 60f)), 1080, 2400))
        assertEquals(null, fastestDisplayMode(modes, 720, 1600))
    }
}
