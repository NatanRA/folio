package com.natan.mediaportal

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class PlayerPaletteTest {
    private fun rgb(red: Int, green: Int, blue: Int) = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    /** WCAG contrast ratio of white text on [color]. */
    private fun whiteContrast(color: Int): Double {
        fun linear(channel: Int) = (channel / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        val luminance = 0.2126 * linear(color shr 16 and 0xFF) + 0.7152 * linear(color shr 8 and 0xFF) + 0.0722 * linear(color and 0xFF)
        return 1.05 / (luminance + 0.05)
    }

    @Test fun keepsTheCoverHueButDarkensIt() {
        val magenta = rgb(142, 47, 90)
        val backdrop = PlayerPalette.backdrop(magenta)
        assertEquals(PlayerPalette.toHsb(magenta)[0], PlayerPalette.toHsb(backdrop)[0], 0.01f)
        assertEquals(0.24f, PlayerPalette.toHsb(backdrop)[2], 0.01f)
    }

    @Test fun aGreyCoverGetsTheNeutralDark() {
        assertEquals(PlayerPalette.neutral, PlayerPalette.backdrop(rgb(204, 204, 204)))
    }

    @Test fun whiteTextStaysReadableWhateverTheCoverColour() {
        for (step in 0 until 24) {
            val cover = PlayerPalette.fromHsb(step / 24f, 1f, 1f)
            assertTrue("hue $step of 24", whiteContrast(PlayerPalette.backdrop(cover)) > 7)
        }
    }

    @Test fun hsbRoundTripsPrimaryAndMixedColours() {
        for (color in listOf(rgb(255, 0, 0), rgb(0, 255, 0), rgb(0, 0, 255), rgb(51, 184, 166), rgb(142, 47, 90))) {
            val (h, s, b) = PlayerPalette.toHsb(color)
            val back = PlayerPalette.fromHsb(h, s, b)
            for (shift in listOf(16, 8, 0)) assertEquals((color shr shift and 0xFF).toDouble(), (back shr shift and 0xFF).toDouble(), 1.0)
        }
    }

    @Test fun averagesOpaquePixelsAndIgnoresTransparentOnes() {
        val pixels = IntArray(6) { if (it < 3) rgb(51, 102, 204) else rgb(153, 102, 0) } + intArrayOf(0x00FFFFFF)
        assertEquals(rgb(102, 102, 102), PlayerPalette.average(pixels))
        assertNull(PlayerPalette.average(intArrayOf(0x00000000)))
    }
}
