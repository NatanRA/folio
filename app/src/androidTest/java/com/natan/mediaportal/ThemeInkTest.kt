package com.natan.mediaportal

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How readable the most visible ink in an image is against its commonest colour, the background it sits on. */
internal object Legibility {
    fun contrast(image: Bitmap): Double {
        val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
        val ground = pixels.toList().groupingBy { it }.eachCount().maxBy { it.value }.key
        return pixels.maxOf { ratio(it, ground) }
    }
    private fun luminance(color: Int): Double {
        fun channel(value: Int) = (value / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * channel(color shr 16 and 0xFF) + 0.7152 * channel(color shr 8 and 0xFF) + 0.0722 * channel(color and 0xFF)
    }
    private fun ratio(a: Int, b: Int): Double {
        val (high, low) = luminance(a).let { la -> luminance(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }
        return (high + 0.05) / (low + 0.05)
    }
}

/**
 * Text and icons given no colour of their own must stay readable on the theme's background in every palette and mode.
 * The audiobook player sits outside any Surface, and its title and close arrow once came out black on dark.
 */
@RunWith(AndroidJUnit4::class)
class ThemeInkTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unstyledTextAndIconsAreReadableInEveryPaletteAndMode() {
        var dark by mutableStateOf(false)
        var palette by mutableStateOf(PortalPalettes.first())
        compose.setContent {
            PortalTheme(styleWindow = false, darkOverride = dark, accentOverride = paletteAccent(palette, dark)) {
                Row(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                    Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(40.dp).testTag("icon"))
                    Text("Harry Potter and the Philosopher's Stone", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("text"))
                }
            }
        }
        for (mode in listOf(false, true)) for (name in PortalPalettes) {
            dark = mode; palette = name
            compose.waitForIdle()
            for (tag in listOf("text", "icon")) {
                val contrast = Legibility.contrast(compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap())
                assertTrue("$tag in $name ${if (mode) "dark" else "light"} is $contrast:1", contrast >= 4.5)
            }
        }
    }
}
