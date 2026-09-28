package app.folio.library

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class GlobalSearchTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun searchIsAvailableAcrossTabsWithoutDiscoverDestination() {
        val inst = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use {
            for (tab in listOf("Home", "Library", "Downloads", "Server")) {
                compose.onNodeWithContentDescription(tab, useUnmergedTree = true).performClick()
                compose.onNodeWithContentDescription("Search").assertIsDisplayed()
                compose.onAllNodesWithTag("global-search").assertCountEquals(0)
            }
            compose.onAllNodesWithText("Discover").assertCountEquals(0)
            compose.onNodeWithContentDescription("Search").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("global-search").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("global-search").performTextInput("Alice")
            compose.onNodeWithText("My library").assertIsDisplayed()
            compose.onNodeWithText("Free books").performClick()
            compose.onNodeWithText("Audiobooks").assertIsDisplayed()
            compose.onNodeWithText("My library").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Audiobooks").fetchSemanticsNodes().isEmpty() }
            val folder = File("/sdcard/Android/media/${inst.targetContext.packageName}/test-output").apply { mkdirs() }
            inst.uiAutomation.takeScreenshot().let { bitmap -> File(folder, "universal-search.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            compose.onNodeWithContentDescription("Close search").performClick()
            compose.onNodeWithText("Server settings").assertIsDisplayed()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("global-search").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Search").assertIsDisplayed()
        }
    }
}
