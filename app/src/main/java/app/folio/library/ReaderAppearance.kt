package app.folio.library

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

/**
 * A reading paper: the page and ink colours, and whether the reader's controls sit light or dark on it.
 * The controls follow the paper rather than the app's mode (see [ReaderTheme]), so a white page never carries
 * dark controls. The same papers, colours and order as ReaderPaper on iOS.
 */
internal enum class ReaderPaper(
    val dark: Boolean, val page: Color, val ink: Color,
    /** The paper's own accent for the reader's controls; papers without one use the app palette's accent. */
    val accent: Color? = null,
    /** Whether Readium is given [page] and [ink]. Otherwise they are the colours of Readium's own [theme]. */
    private val custom: Boolean = false,
) {
    White(dark = false, page = Color(0xFFFFFFFF), ink = Color(0xFF121212)),
    Sepia(dark = false, page = Color(0xFFFAF4E8), ink = Color(0xFF121212)),
    // Cherry-blossom washi with plum-black ink.
    Sakura(dark = false, page = Color(0xFFF9EDEF), ink = Color(0xFF33262B), accent = Color(0xFFA43455), custom = true),
    Night(dark = true, page = Color(0xFF000000), ink = Color(0xFFFEFEFE)),
    // Indigo-dyed night paper, softer than black, with warm off-white ink and gold controls.
    Indigo(dark = true, page = Color(0xFF1B2130), ink = Color(0xFFE4DED2), accent = Color(0xFFE3BD6B), custom = true);

    // Resolved on use: Readium's Theme parses its colours with android.graphics.Color as it loads.
    val theme: Theme get() = when { dark -> Theme.DARK; this == Sepia -> Theme.SEPIA; else -> Theme.LIGHT }

    /** Readium's colour overrides for this paper, or null to keep its theme's own colours. */
    val readiumPage get() = if (custom) org.readium.r2.navigator.preferences.Color(page.toArgb()) else null
    val readiumInk get() = if (custom) org.readium.r2.navigator.preferences.Color(ink.toArgb()) else null

    companion object {
        /** The paper saved under "reader:paper": its name, or a Readium theme name saved before papers existed. */
        fun stored(value: String?): ReaderPaper = when (value) {
            "LIGHT" -> White
            "SEPIA" -> Sepia
            "DARK" -> Night
            else -> entries.firstOrNull { it.name == value } ?: White
        }
    }
}

internal data class ReaderAppearance(
    val size: Float = 110f, val spacing: Float = 1.45f, val margins: Float = 1f,
    val sans: Boolean = false, val paper: ReaderPaper = ReaderPaper.White
) {
    @OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
    fun preferences() = EpubPreferences(textAlign = TextAlign.LEFT, publisherStyles = false,
        // Readium takes a scale (1.1), while the UI stores a percentage (110).
        fontFamily = FontFamily(if (sans) "sans-serif" else "serif"), fontSize = size.toDouble() / 100.0,
        lineHeight = spacing.toDouble(), paragraphIndent = 1.25, paragraphSpacing = 0.0,
        pageMargins = margins.toDouble(), theme = paper.theme,
        backgroundColor = paper.readiumPage, textColor = paper.readiumInk)

    fun save(prefs: SharedPreferences) {
        prefs.edit().putFloat("reader:size", size).putFloat("reader:spacing", spacing)
            .putFloat("reader:margins", margins).putBoolean("reader:sans", sans)
            .putString("reader:paper", paper.name).apply()
    }
    companion object {
        fun load(prefs: SharedPreferences) = ReaderAppearance(
            prefs.getFloat("reader:size", 110f).coerceIn(80f, 180f),
            prefs.getFloat("reader:spacing", 1.45f).coerceIn(1.1f, 2f),
            prefs.getFloat("reader:margins", 1f).coerceIn(.5f, 2f),
            prefs.getBoolean("reader:sans", false),
            ReaderPaper.stored(prefs.getString("reader:paper", null)))
    }
}
