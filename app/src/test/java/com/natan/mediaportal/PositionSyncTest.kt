package com.natan.mediaportal

import org.junit.Assert.*
import org.junit.Test

/**
 * A listening position is carried between the reader's own devices by the annotation service.
 * Android keeps positions in milliseconds and the wire carries milliseconds, so nothing on this
 * side may scale a value; iOS converts from seconds at its own end.
 */
class PositionSyncTest {
    private val harryPotter = "tg-7654321-1002-4519"
    private val imported = "6f1b9a2c-3d4e-4f50-8a1b-2c3d4e5f6a7b"

    private fun record(key: String = harryPotter, position: Long = 8_241_000, duration: Long = 27_360_000, modified: Long = 1_789_941_600_000) =
        ListeningPosition(key, position, duration, modified)

    private fun offered(saved: Map<String, ListeningPosition>) = Positions.outgoing(saved.keys.toList()) { saved[it] }

    @Test fun onlyTelegramBooksLeaveTheDevice() {
        val saved = mapOf(harryPotter to record(), imported to record(imported), "booklore:42" to record("booklore:42"))
        assertEquals(listOf(harryPotter), offered(saved).map { it.key })
    }

    /** An id this device invented cannot identify the same book anywhere else. */
    @Test fun shareableAcceptsTelegramIdsAlone() {
        assertTrue(Positions.shareable(harryPotter))
        assertFalse(Positions.shareable(imported))
        assertFalse(Positions.shareable("tg 1-2-3"))
        assertFalse(Positions.shareable("tg-1-2-3/../4"))
        assertFalse(Positions.shareable(""))
    }

    @Test fun aBookThisDeviceHasNeverPlayedIsNotOffered() {
        assertTrue(Positions.outgoing(listOf(harryPotter)) { null }.isEmpty())
        assertNull(Positions.offer(harryPotter, position = 0, duration = 0, audioModified = 0))
    }

    /**
     * The whole point of the feature: a position from before stamps existed has only the date of
     * the save that left it, and the one-time backfill turns that into the stamp it travels under.
     */
    @Test fun aPositionSavedBeforeStampsExistedStillTravels() {
        val stamped = Positions.backfill(position = 8_241_000, audioModified = 0, lastOpened = 1_789_900_000_000)
        assertEquals(1_789_900_000_000L, stamped)
        val offered = Positions.offer(harryPotter, position = 8_241_000, duration = 27_360_000, audioModified = stamped!!)
        assertEquals(1_789_900_000_000L, offered!!.modified)
        assertEquals(8_241_000L, offered.position)
        assertEquals(27_360_000L, offered.duration)
    }

    /** The backfill dates what was already saved and never overwrites a stamp of its own. */
    @Test fun theBackfillOnlyDatesAPositionThatHasNoStampOfItsOwn() {
        assertNull(Positions.backfill(position = 8_241_000, audioModified = 1_789_941_600_000, lastOpened = 1_700_000_000_000))
        assertEquals(1_700_000_000_000L, Positions.backfill(position = 8_241_000, audioModified = 0, lastOpened = 1_700_000_000_000))
        assertNull(Positions.backfill(position = 8_241_000, audioModified = 0, lastOpened = 0))
    }

    /**
     * Opening a title dates it without moving anything, so the backfill passes such a book over and
     * it stays unstamped. It then has nothing to offer and, by that same one rule read the other
     * way, nothing to hold an arriving record back with — however recently it was opened.
     */
    @Test fun aBookOnlyEverOpenedNeitherTravelsNorBlocks() {
        assertNull(Positions.backfill(position = 0, audioModified = 0, lastOpened = 1_789_900_000_000))
        assertNull(Positions.offer(harryPotter, position = 0, duration = 27_360_000, audioModified = 0))
        val arriving = record(modified = 1_700_000_000_000)
        assertEquals(listOf(arriving), Positions.incoming(listOf(arriving), null) { 0L })
    }

    /** A rewind right back to the start is still a position this device wrote. */
    @Test fun aDeliberateReturnToTheStartStillTravels() {
        val offered = Positions.offer(harryPotter, position = 0, duration = 27_360_000, audioModified = 1_789_941_600_000)
        assertEquals(1_789_941_600_000L, offered!!.modified)
        assertEquals(0L, offered.position)
    }

    /** A record nobody could ever apply would only take up one of the service's rows. */
    @Test fun aRecordWithNoTimeIsNeverSent() {
        assertTrue(Positions.outgoing(listOf(harryPotter)) { record(modified = 0) }.isEmpty())
    }

    /** Merely opening a never-played book must not claim a write and outrank the other device. */
    @Test fun openingABookAtTheStartIsNotAWrite() {
        assertFalse(Positions.moved(position = 0, stored = -1))
        assertTrue(Positions.moved(position = 8_241_000, stored = -1))
        assertFalse(Positions.moved(position = 8_241_000, stored = 8_241_000))
        assertTrue(Positions.moved(position = 8_242_000, stored = 8_241_000))
        assertTrue(Positions.moved(position = 0, stored = 8_241_000))
    }

    /** Two hours seventeen into the book stays two hours seventeen, not two hours seventeen thousand. */
    @Test fun positionsTravelInTheMillisecondsAndroidAlreadySaves() {
        val offered = offered(mapOf(harryPotter to record())).single()
        assertEquals(8_241_000L, offered.position)
        assertEquals(27_360_000L, offered.duration)
        assertEquals(8_241_000L, Positions.updates(offered, lastOpened = 0)["audio:$harryPotter"])
        assertEquals(27_360_000L, Positions.updates(offered, lastOpened = 0)["audioDuration:$harryPotter"])
    }

    @Test fun aStaleServerRecordNeverRewindsANewerLocalPosition() {
        val stale = record(position = 600_000, modified = 1_789_000_000_000)
        assertTrue(Positions.incoming(listOf(stale), null) { 1_789_900_000_000 }.isEmpty())
        assertEquals(listOf(stale), Positions.incoming(listOf(stale), null) { 1_700_000_000_000 })
    }

    /** An equal timestamp is this device's own write; only a later one belongs to the other device. */
    @Test fun anEquallyOldRecordIsLeftAlone() {
        assertTrue(Positions.incoming(listOf(record(modified = 1_789_941_600_000)), null) { 1_789_941_600_000 }.isEmpty())
    }

    @Test fun anUnknownDurationLeavesAKnownLengthAlone() {
        val updates = Positions.updates(record(duration = 0), lastOpened = 0)
        assertFalse("a zero length must not clobber the one this device measured", updates.containsKey("audioDuration:$harryPotter"))
        assertEquals(8_241_000L, updates["audio:$harryPotter"])
    }

    /** Home sorts by when a book was last opened, so a pulled position has to date the book too. */
    @Test fun aPulledPositionIsWrittenWithItsOwnTime() {
        val updates = Positions.updates(record(), lastOpened = 1_700_000_000_000)
        assertEquals(1_789_941_600_000L, updates["audioModified:$harryPotter"])
        assertEquals(1_789_941_600_000L, updates["lastOpened:$harryPotter"])
    }

    /**
     * Reading the EPUB of a title dates it without stamping its audio, so a record older than that
     * reading can still apply — and must not demote the book Home and Continue offer first.
     */
    @Test fun aPulledPositionNeverMovesTheOpeningTimeBackwards() {
        val updates = Positions.updates(record(modified = 1_700_000_000_000), lastOpened = 1_789_900_000_000)
        assertEquals(1_789_900_000_000L, updates["lastOpened:$harryPotter"])
        assertEquals(1_700_000_000_000L, updates["audioModified:$harryPotter"])
    }

    /** Only the book being listened to, mind: a loaded but paused one is what a pull is for. */
    @Test fun theBookBeingListenedToIsNeverMoved() {
        val newer = record(modified = 1_789_999_999_999)
        assertTrue(Positions.incoming(listOf(newer), harryPotter) { 0L }.isEmpty())
        assertEquals(listOf(newer), Positions.incoming(listOf(newer), "tg-7654321-1002-4520") { 0L })
    }

    @Test fun recordsForBooksThisLibraryDoesNotHoldAreIgnored() {
        assertTrue(Positions.incoming(listOf(record()), null) { null }.isEmpty())
    }

    /** The service rejects the whole batch over one bad record, so never build one. */
    @Test fun refusesWhatTheServiceWouldRefuse() {
        assertFalse(Positions.valid(record(key = "tg-1-2-3?")))
        assertFalse(Positions.valid(record(position = -1)))
        assertFalse(Positions.valid(record(position = Positions.LIMIT_MS + 1)))
        assertFalse(Positions.valid(record(duration = Positions.LIMIT_MS + 1)))
        assertFalse(Positions.valid(record(modified = -1)))
        assertTrue(Positions.valid(record(position = 0, duration = 0, modified = 0)))
        assertTrue(Positions.valid(record(position = Positions.LIMIT_MS, duration = Positions.LIMIT_MS)))
    }

    @Test fun anInvalidPulledRecordChangesNothing() {
        assertTrue(Positions.incoming(listOf(record(position = -5)), null) { 0L }.isEmpty())
        assertTrue(Positions.incoming(listOf(record(key = imported)), null) { 0L }.isEmpty())
    }

    /**
     * A position taken from another device is not a position this one wrote. Once the player has
     * been moved to it, the save a second later finds nothing changed and leaves the stamp the
     * record arrived with, so the record never comes back as this device's own newer write.
     */
    @Test fun applyingAPulledPositionToAPausedPlayerKeepsTheRecordsStamp() {
        val arriving = record(position = 8_241_000, modified = 1_789_941_600_000)
        val updates = Positions.updates(arriving, lastOpened = 0)
        assertEquals(1_789_941_600_000L, updates["audioModified:$harryPotter"])
        val saved = updates["audio:$harryPotter"]!!
        assertFalse("the player now sits where the record put it, so the save is not a move",
            Positions.moved(position = saved, stored = saved))
    }

    /**
     * Until the player has taken it, the pulled position is marked as another device's. A book
     * loading as the record lands has already been told where to start, and the save that follows
     * would otherwise put that stale position back and stamp it as a move made here.
     */
    @Test fun aPulledPositionIsMarkedUntilThePlayerHasTakenIt() {
        val updates = Positions.updates(record(), lastOpened = 0)
        assertEquals(updates["audio:$harryPotter"], updates["pulled:$harryPotter"])
        assertTrue("the stale position the player starts from would otherwise be stamped as a write",
            Positions.moved(position = 600_000, stored = updates["pulled:$harryPotter"]!!))
    }

    /**
     * A clock put back must not date this device's later work before its earlier work, or every
     * merge against its own records is lost and the older position keeps being pulled back in.
     */
    @Test fun aDevicesOwnStampsNeverGoBackwards() {
        assertEquals(1_789_941_600_000L, Positions.stamp(now = 1_700_000_000_000, stored = 1_789_941_600_000))
        assertEquals(1_789_941_600_001L, Positions.stamp(now = 1_789_941_600_001, stored = 1_789_941_600_000))
        assertEquals(1_789_941_600_000L, Positions.stamp(now = 1_789_941_600_000, stored = 1_789_941_600_000))
        assertEquals(1_700_000_000_000L, Positions.stamp(now = 1_700_000_000_000, stored = 0))
    }

    /** A stamp held forward still outranks the record that was ahead of it, and only just. */
    @Test fun aHeldForwardStampStillLosesToAGenuinelyNewerRecord() {
        val held = Positions.stamp(now = 1_700_000_000_000, stored = 1_789_941_600_000)
        assertTrue(Positions.incoming(listOf(record(modified = held + 1)), null) { held }.isNotEmpty())
        assertTrue(Positions.incoming(listOf(record(modified = held)), null) { held }.isEmpty())
    }
}
