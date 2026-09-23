package com.natan.mediaportal

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

/** ReaderPaper keeps its own page and ink colours so JVM tests can check them; they must be what Readium draws. */
class ReaderPaperTest {
    @Test fun pageAndInkAreTheColoursReadiumIsGiven() {
        for (paper in ReaderPaper.entries) {
            val preferences = ReaderAppearance(paper = paper).preferences()
            assertEquals(paper.theme, preferences.theme)
            assertEquals("${paper.name} page", paper.page.toArgb(), preferences.backgroundColor?.int ?: paper.theme.backgroundColor)
            assertEquals("${paper.name} ink", paper.ink.toArgb(), preferences.textColor?.int ?: paper.theme.contentColor)
        }
    }
}
