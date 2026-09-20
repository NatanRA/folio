package com.natan.mediaportal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class TabBarTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun screenshot(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun floatingTabBarSwitchesByTapAndByDraggingAcrossIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val bar = compose.onNodeWithTag("tab-bar").assertIsDisplayed()
            // Tapping still works like the old bar.
            compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
            compose.onNode(hasText("titles ·", substring = true)).assertIsDisplayed()
            // Sliding a finger from the first item to the third lands on Downloads.
            bar.performTouchInput {
                swipe(start = Offset(width * 0.125f, centerY), end = Offset(width * 0.625f, centerY), durationMillis = 400)
            }
            compose.onNode(hasText("Available offline", substring = true)).assertIsDisplayed()
            screenshot("tab-bar-downloads")
            // Sliding back the other way returns to Home, which has no title strip.
            bar.performTouchInput {
                swipe(start = Offset(width * 0.625f, centerY), end = Offset(width * 0.125f, centerY), durationMillis = 400)
            }
            compose.onNode(hasText("Available offline", substring = true)).assertDoesNotExist()
            compose.onNode(hasText("titles ·", substring = true)).assertDoesNotExist()
        }
    }
}
