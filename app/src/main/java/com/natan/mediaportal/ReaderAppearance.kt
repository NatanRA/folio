package com.natan.mediaportal

import android.content.SharedPreferences
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

internal data class ReaderAppearance(
    val size: Float = 110f, val spacing: Float = 1.45f, val margins: Float = 1f,
    val sans: Boolean = false, val theme: Theme = Theme.LIGHT
) {
    @OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
    fun preferences() = EpubPreferences(textAlign = TextAlign.LEFT, publisherStyles = false,
        // Readium takes a scale (1.1), while the UI stores a percentage (110).
        fontFamily = FontFamily(if (sans) "sans-serif" else "serif"), fontSize = size.toDouble() / 100.0,
        lineHeight = spacing.toDouble(), paragraphIndent = 1.25, paragraphSpacing = 0.0,
        pageMargins = margins.toDouble(), theme = theme)

    fun save(prefs: SharedPreferences) {
        prefs.edit().putFloat("reader:size", size).putFloat("reader:spacing", spacing)
            .putFloat("reader:margins", margins).putBoolean("reader:sans", sans)
            .putString("reader:paper", theme.name).apply()
    }
    companion object {
        fun load(prefs: SharedPreferences) = ReaderAppearance(
            prefs.getFloat("reader:size", 110f).coerceIn(80f, 180f),
            prefs.getFloat("reader:spacing", 1.45f).coerceIn(1.1f, 2f),
            prefs.getFloat("reader:margins", 1f).coerceIn(.5f, 2f),
            prefs.getBoolean("reader:sans", false),
            runCatching { Theme.valueOf(prefs.getString("reader:paper", "LIGHT")!!) }.getOrDefault(Theme.LIGHT))
    }
}
