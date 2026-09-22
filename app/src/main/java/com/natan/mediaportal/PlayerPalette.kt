package com.natan.mediaportal

/**
 * The audiobook player's backdrop: the cover's own colour, darkened until white text on it stays
 * easy to read. Colours are ARGB ints and the maths is plain Kotlin, so it runs in unit tests.
 */
internal object PlayerPalette {
    /** The backdrop for a grey or missing cover. */
    val neutral: Int = fromHsb(0f, 0f, 0.14f)

    /**
     * Keeps the cover's hue, keeps its saturation within a calm range and fixes a dark brightness.
     * A grey cover has no hue worth keeping, so it gets the neutral dark rather than a muddy tint.
     */
    fun backdrop(average: Int): Int {
        val (hue, saturation) = toHsb(average)
        if (saturation <= 0.12f) return neutral
        return fromHsb(hue, saturation.coerceIn(0.3f, 0.7f), 0.24f)
    }

    /** The mean colour of opaque pixels, or null when there are none. */
    fun average(pixels: IntArray): Int? {
        var red = 0L; var green = 0L; var blue = 0L; var count = 0
        for (pixel in pixels) {
            if (pixel ushr 24 < 128) continue
            red += pixel shr 16 and 0xFF; green += pixel shr 8 and 0xFF; blue += pixel and 0xFF; count++
        }
        if (count == 0) return null
        return argb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    /** Hue (0 to 1), saturation and brightness of an ARGB colour. */
    fun toHsb(color: Int): FloatArray {
        val r = (color shr 16 and 0xFF) / 255f; val g = (color shr 8 and 0xFF) / 255f; val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b); val delta = max - minOf(r, g, b)
        val hue = when {
            delta == 0f -> 0f
            max == r -> ((g - b) / delta).mod(6f) / 6f
            max == g -> ((b - r) / delta + 2) / 6f
            else -> ((r - g) / delta + 4) / 6f
        }
        return floatArrayOf(hue, if (max == 0f) 0f else delta / max, max)
    }

    fun fromHsb(hue: Float, saturation: Float, brightness: Float): Int {
        val sector = (hue.mod(1f)) * 6f
        val chroma = brightness * saturation
        val x = chroma * (1 - kotlin.math.abs(sector.mod(2f) - 1))
        val (r, g, b) = when (sector.toInt()) {
            0 -> Triple(chroma, x, 0f); 1 -> Triple(x, chroma, 0f); 2 -> Triple(0f, chroma, x)
            3 -> Triple(0f, x, chroma); 4 -> Triple(x, 0f, chroma); else -> Triple(chroma, 0f, x)
        }
        val m = brightness - chroma
        return argb(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
    }

    private fun argb(red: Int, green: Int, blue: Int) = (0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
}
