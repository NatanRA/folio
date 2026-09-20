package com.natan.mediaportal

import android.content.SharedPreferences
import org.json.JSONObject

/** Completion is a reader decision, independent of the start position of an EPUB page. */
class ReadingProgress(private val prefs: SharedPreferences, private val id: String) {
    val finished: Boolean get() = prefs.getBoolean("ebookFinished:$id", false)
    val percent: Int get() = if (finished) 100 else prefs.getInt("ebookPercent:$id", 0)

    fun record(locator: String, progression: Double) {
        prefs.edit().putString("ebook:$id", locator)
            .putInt("ebookPercent:$id", if (finished) 100 else (progression * 100).toInt().coerceIn(0, 99))
            // Home offers the most recently opened title, so every progress write stamps the time.
            .putLong("lastOpened:$id", System.currentTimeMillis()).apply()
    }

    fun setFinished(value: Boolean) {
        val position = runCatching {
            JSONObject(prefs.getString("ebook:$id", null) ?: "{}").optJSONObject("locations")
                ?.optDouble("totalProgression", 0.0) ?: 0.0
        }.getOrDefault(0.0)
        prefs.edit().putBoolean("ebookFinished:$id", value)
            .putInt("ebookPercent:$id", if (value) 100 else (position * 100).toInt().coerceIn(0, 99)).apply()
    }
}
