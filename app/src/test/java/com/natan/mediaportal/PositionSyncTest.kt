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
    }

    /** Two hours seventeen into the book stays two hours seventeen, not two hours seventeen thousand. */
    @Test fun positionsTravelInTheMillisecondsAndroidAlreadySaves() {
        val offered = offered(mapOf(harryPotter to record())).single()
        assertEquals(8_241_000L, offered.position)
        assertEquals(27_360_000L, offered.duration)
        assertEquals(8_241_000L, Positions.updates(offered)["audio:$harryPotter"])
        assertEquals(27_360_000L, Positions.updates(offered)["audioDuration:$harryPotter"])
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
        val updates = Positions.updates(record(duration = 0))
        assertFalse("a zero length must not clobber the one this device measured", updates.containsKey("audioDuration:$harryPotter"))
        assertEquals(8_241_000L, updates["audio:$harryPotter"])
    }

    /** Home sorts by when a book was last opened, so a pulled position has to date the book too. */
    @Test fun aPulledPositionIsWrittenWithItsOwnTime() {
        val updates = Positions.updates(record())
        assertEquals(1_789_941_600_000L, updates["audioModified:$harryPotter"])
        assertEquals(1_789_941_600_000L, updates["lastOpened:$harryPotter"])
    }

    @Test fun theBookInThePlayerIsNeverMoved() {
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
}
