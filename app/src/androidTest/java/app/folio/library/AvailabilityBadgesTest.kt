package app.folio.library

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class AvailabilityBadgesTest {
    @get:Rule val compose = createComposeRule()
    @Test fun badgeDistinguishesLocalServerAndBoth() {
        val state = mutableStateOf(true to true)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF141619)), contentAlignment = Alignment.Center) {
                Box(Modifier.width(130.dp).height(194.dp).background(Color(0xFF45525B))) {
                    Text("Alice’s Adventures in Wonderland", color = Color.White, modifier = Modifier.padding(12.dp))
                    AvailabilityBadges(state.value.first, state.value.second, Modifier.align(Alignment.BottomEnd).padding(5.dp))
                }
            }
        }
        compose.onNodeWithContentDescription("Available offline").assertIsDisplayed()
        compose.onNodeWithContentDescription("In server catalogue (last refresh)").assertIsDisplayed()
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.waitForIdleSync()
        Thread.sleep(500)
        val output = File("/sdcard/Android/media/${inst.targetContext.packageName}/test-output").apply { mkdirs() }
        inst.uiAutomation.takeScreenshot().let { bitmap -> output.resolve("booklore-badge.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        compose.runOnIdle { state.value = true to false }
        compose.onNodeWithContentDescription("Available offline").assertIsDisplayed()
        compose.onNodeWithContentDescription("In server catalogue (last refresh)").assertDoesNotExist()
        compose.runOnIdle { state.value = false to true }
        compose.onNodeWithContentDescription("Available offline").assertDoesNotExist()
        compose.onNodeWithContentDescription("In server catalogue (last refresh)").assertIsDisplayed()
    }
}
