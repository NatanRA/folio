package app.folio.library

import android.content.SharedPreferences
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Every palette and paper must stay readable where it is drawn. The thresholds are WCAG's: 4.5:1 for text and
 * 7:1 for a paper's body text, since that is read for hours. The same rules as ThemeContrastTests on iOS.
 */
class ThemeContrastTest {
    @Test fun paletteAccentsAreReadableOnEverySurfaceOfTheirScheme() {
        for (palette in PortalPalettes) for (dark in listOf(false, true)) {
            assertSchemeReadable(portalColorScheme(paletteAccent(palette, dark), dark), "$palette ${if (dark) "dark" else "light"}")
        }
    }

    @Test fun papersKeepTheirTextAndControlsReadable() {
        for (paper in ReaderPaper.entries) {
            assertAtLeast(7.0, paper.ink, paper.page, "${paper.name} body text")
            // A paper's own accent replaces the palette's, so every palette gives the same controls on it.
            for (palette in PortalPalettes) {
                val scheme = readerScheme(paper, palette)
                assertAtLeast(4.5, scheme.primary, paper.page, "${paper.name} controls with the $palette palette")
                assertSchemeReadable(scheme, "${paper.name} reader sheets with the $palette palette")
            }
        }
    }

    @Test fun controlsFollowThePaperNotTheAppMode() {
        assertEquals(listOf("White", "Sepia", "Sakura", "Night", "Indigo"), ReaderPaper.entries.map { it.name })
        assertEquals(listOf(false, false, false, true, true), ReaderPaper.entries.map { it.dark })
        for (paper in ReaderPaper.entries) {
            val scheme = readerScheme(paper, "Aquamarine")
            // A dark paper's scheme has light text on dark surfaces; a light paper's the reverse.
            assertEquals("${paper.name} scheme", paper.dark, luminance(scheme.surface) < luminance(scheme.onSurface))
        }
        assertEquals(Color(0xFFA43455), readerScheme(ReaderPaper.Sakura, "Blue").primary)
        assertEquals(Color(0xFFE3BD6B), readerScheme(ReaderPaper.Indigo, "Blue").primary)
        assertEquals(paletteAccent("Blue", dark = false), readerScheme(ReaderPaper.White, "Blue").primary)
        assertEquals(paletteAccent("Blue", dark = true), readerScheme(ReaderPaper.Night, "Blue").primary)
    }

    @Test fun legacyReadiumThemeNamesMigrateToPapers() {
        fun loaded(stored: String?) = ReaderAppearance.load(FakePrefs().apply { if (stored != null) values["reader:paper"] = stored }).paper
        assertEquals(ReaderPaper.White, loaded("LIGHT"))
        assertEquals(ReaderPaper.Sepia, loaded("SEPIA"))
        assertEquals(ReaderPaper.Night, loaded("DARK"))
        assertEquals(ReaderPaper.White, loaded("Parchment"))
        assertEquals(ReaderPaper.White, loaded(""))
        assertEquals(ReaderPaper.White, loaded(null))
        assertEquals(ReaderPaper.Sakura, loaded("Sakura"))
        assertEquals(ReaderPaper.Indigo, loaded("Indigo"))
    }

    @Test fun papersAreSavedByName() {
        for (paper in ReaderPaper.entries) {
            val prefs = FakePrefs()
            ReaderAppearance(paper = paper).save(prefs)
            assertEquals(paper.name, prefs.values["reader:paper"])
            assertEquals(paper, ReaderAppearance.load(prefs).paper)
        }
    }

    /** The reader's scheme, built the way [ReaderTheme] asks [PortalTheme] for it. */
    private fun readerScheme(paper: ReaderPaper, palette: String) =
        portalColorScheme(paper.accent ?: paletteAccent(palette, paper.dark), paper.dark)

    private fun assertSchemeReadable(scheme: ColorScheme, label: String) {
        val surfaces = mapOf("background" to scheme.background, "surface" to scheme.surface,
            "surfaceContainerLowest" to scheme.surfaceContainerLowest, "surfaceContainerLow" to scheme.surfaceContainerLow,
            "surfaceContainer" to scheme.surfaceContainer, "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            "surfaceContainerHighest" to scheme.surfaceContainerHighest, "surfaceVariant" to scheme.surfaceVariant,
            "primaryContainer" to scheme.primaryContainer, "secondaryContainer" to scheme.secondaryContainer)
        for ((name, surface) in surfaces) assertAtLeast(4.5, scheme.primary, surface, "$label: primary on $name")
        assertAtLeast(4.5, scheme.onPrimaryContainer, scheme.primaryContainer, "$label: onPrimaryContainer")
        assertAtLeast(4.5, scheme.onPrimary, scheme.primary, "$label: onPrimary on a filled accent")
        // The reader's page buttons and menu button: onSecondaryContainer on secondaryContainer.
        assertAtLeast(4.5, scheme.onSecondaryContainer, scheme.secondaryContainer, "$label: tonal button content")
        // Accent text on its own tint, like the selection toolbar's Define button over surfaceContainer.
        val tint = scheme.primary.copy(alpha = AccentTextTint).compositeOver(scheme.surfaceContainer)
        assertAtLeast(4.5, scheme.primary, tint, "$label: primary on its own tint")
    }

    private fun assertAtLeast(minimum: Double, foreground: Color, background: Color, label: String) {
        val ratio = contrast(foreground, background)
        assertTrue("$label is %.2f:1, needs %.1f:1".format(ratio, minimum), ratio >= minimum)
    }

    /** WCAG 2 relative luminance from sRGB channels, computed here rather than with Compose's helper. */
    private fun luminance(color: Color): Double {
        val argb = color.toArgb()
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xFF) / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (high, low) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }

    private fun Color.compositeOver(background: Color) = Color(
        red = red * alpha + background.red * (1 - alpha), green = green * alpha + background.green * (1 - alpha),
        blue = blue * alpha + background.blue * (1 - alpha))

    /** In-memory SharedPreferences, enough for ReaderAppearance's load and save. */
    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        override fun getAll() = values.toMutableMap()
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = Editor()
        private inner class Editor : SharedPreferences.Editor {
            private fun put(key: String, value: Any?): Editor { values[key] = value; return this }
            override fun putString(key: String, value: String?) = put(key, value)
            override fun putStringSet(key: String, set: MutableSet<String>?) = put(key, set)
            override fun putInt(key: String, value: Int) = put(key, value)
            override fun putLong(key: String, value: Long) = put(key, value)
            override fun putFloat(key: String, value: Float) = put(key, value)
            override fun putBoolean(key: String, value: Boolean) = put(key, value)
            override fun remove(key: String): Editor { values.remove(key); return this }
            override fun clear(): Editor { values.clear(); return this }
            override fun commit() = true
            override fun apply() {}
        }
    }
}
