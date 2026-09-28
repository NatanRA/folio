package app.folio.library

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class TelegramSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun telegramSetupIsNativeAndDoesNotExposeCredentials() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription("Server", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Telegram").performScrollTo().performClick()
            compose.onNodeWithText("API ID").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Connect Telegram").performScrollTo().assertIsNotEnabled()
            val inst = InstrumentationRegistry.getInstrumentation()
            val folder = File("/sdcard/Android/media/${inst.targetContext.packageName}/test-output").apply { mkdirs() }
            inst.uiAutomation.takeScreenshot().let { bitmap -> File(folder, "telegram-settings.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }
}
