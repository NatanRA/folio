package com.natan.mediaportal

import org.junit.Assert.*
import org.junit.Test

class AudioScrubbingTest {
    private val chapters = listOf(AudioChapter("Opening", 0), AudioChapter("002", 6000), AudioChapter("The journey", 9000))

    @Test fun namesPreserveMetadataAndCleanNumericPlaceholders() {
        assertEquals("Opening", chapters[0].displayName(0))
        assertEquals("Chapter 2", chapters[1].displayName(1))
        assertEquals("The journey", chapters[2].displayName(2))
        assertEquals("Chapter 4", AudioChapter("  ", 10000).displayName(3))
    }

    @Test fun snapsNearMarkersInEitherDirectionButAllowsSeekingInsideChapters() {
        assertEquals(chapters[1], snapAudioChapter(5900, 12000, chapters))
        assertEquals(chapters[1], snapAudioChapter(6100, 12000, chapters))
        assertEquals(chapters[2], snapAudioChapter(9000, 12000, chapters))
        assertNull(snapAudioChapter(3000, 12000, chapters))
        assertNull(snapAudioChapter(12000, 12000, chapters))
        assertNull(snapAudioChapter(6000, 0, chapters))
        assertNull(snapAudioChapter(6000, 12000, emptyList()))
    }
}
