package com.natan.mediaportal

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppearanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun paletteAndThemeSurviveRecreation() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val prefs = context.getSharedPreferences("appearance", 0)
        val old = prefs.all
        prefs.edit().clear().commit()
        fun shot(name: String) {
            compose.waitForIdle()
            Thread.sleep(350)
            val out = File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }
            inst.uiAutomation.takeScreenshot().let { bitmap -> out.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                compose.onNodeWithContentDescription("Appearance").performClick()
                compose.onNodeWithText("Dark").assertIsSelected()
                compose.onNodeWithText("Aquamarine").assertIsSelected()
                shot("appearance-dark")
                compose.onNodeWithText("Light").performClick()
                compose.onNodeWithText("Lilac").performClick()
                compose.onNodeWithText("Done").performClick()
                assertEquals("Light", prefs.getString("mode", null))
                assertEquals("Lilac", prefs.getString("palette", null))
                activity.recreate()
                compose.onNodeWithContentDescription("Appearance").performClick()
                compose.onNodeWithText("Light").assertIsSelected()
                compose.onNodeWithText("Lilac").assertIsSelected()
                shot("appearance-light")
                compose.onNodeWithText("Done").performClick()
                compose.onNodeWithText("Server").performClick()
                compose.onNodeWithText("Server settings").assertIsDisplayed()
                shot("expressive-settings-light")
                compose.onNodeWithContentDescription("Appearance").performClick()
                compose.onNodeWithText("Dark").performClick()
                compose.onNodeWithText("Aquamarine").performClick()
                compose.onNodeWithText("Done").performClick()
                shot("expressive-settings-dark")
                compose.onNodeWithText("BookLore").performTouchInput { down(center) }
                shot("settings-header-pressed")
                compose.onNodeWithText("BookLore").performTouchInput { up() }
            }
        } finally {
            prefs.edit().clear().apply { old.forEach { (k, v) -> if (v is String) putString(k, v) } }.commit()
        }
    }
}
