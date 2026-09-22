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

    @Test fun eachChapterRunsToTheNextAndTheLastToTheEndOfTheBook() {
        assertEquals(6000, chapterLengthMs(chapters, 0, 12000))
        assertEquals(3000, chapterLengthMs(chapters, 1, 12000))
        assertEquals(3000, chapterLengthMs(chapters, 2, 12000))
        assertEquals(0, chapterLengthMs(chapters, 3, 12000))
        // Until the book's length is known the last chapter has none, rather than a negative one.
        assertEquals(0, chapterLengthMs(chapters, 2, 0))
    }

    @Test fun timeLeftReadsInHoursAndMinutes() {
        assertEquals("1h 13m", hoursAndMinutes((73 * 60 + 21) * 1000L))
        assertEquals("4m", hoursAndMinutes(4 * 60_000L + 59_000))
        assertEquals("2h 0m", hoursAndMinutes(120 * 60_000L))
        assertEquals("0m", hoursAndMinutes(-5000))
    }
}
