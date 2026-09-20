package com.natan.mediaportal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File

class CoverFitTest {
    @get:Rule val compose = createComposeRule()
    @Test fun fullCoverRemainsVisibleAndRefreshesWithoutOpeningBook() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val titles = listOf("Square", "Wide", "Portrait").map { Title("fit-$it", it, audio = "telegram://1/2/3/audio.m4b") }
        val files = titles.map { File(context.filesDir, "${it.id}.jpg") }
        files.forEach { it.delete() }
        try {
            compose.setContent {
                MaterialTheme {
                    Column(Modifier.statusBarsPadding().padding(16.dp)) {
                        Text("Full audiobook covers")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            titles.forEach { title ->
                                Column {
                                    Text(title.name)
                                    Cover(title, Modifier.width(100.dp).height(150.dp), BookServer(context))
                                }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            // Simulate artwork arriving after the library is already displayed.
            listOf(400 to 400, 600 to 300, 300 to 600).forEachIndexed { index, (width, height) ->
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.BLUE)
                val paint = Paint().apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 30f }
                canvas.drawRect(15f, 15f, width - 15f, height - 15f, paint)
                Artwork.fromBytes(java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(), files[index])
                bitmap.recycle()
            }
            TelegramLibrary.get(context).revision.value++
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Cover of Square").fetchSemanticsNodes().isNotEmpty() }
            titles.forEach { title ->
                compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Cover of ${title.name}").fetchSemanticsNodes().isNotEmpty() }
                val pixels = compose.onNodeWithContentDescription("Cover of ${title.name}").captureToImage().toPixelMap()
                // Both horizontal and vertical edges must survive fitting, for every shape.
                val yellow = { x: Int, y: Int -> val c = pixels[x, y]; c.red > .7f && c.green > .7f && c.blue < .4f }
                assertTrue(title.name, (0 until pixels.width / 2).any { yellow(it, pixels.height / 2) })
                assertTrue(title.name, (0 until pixels.height / 2).any { yellow(pixels.width / 2, it) })
            }
            val folder = File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                folder.resolve("cover-fit.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally { files.forEach { it.delete() } }
    }
}
