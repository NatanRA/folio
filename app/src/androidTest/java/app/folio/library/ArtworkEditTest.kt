package app.folio.library

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Choosing a cover from a file replaces it everywhere it shows, straight away: the library, the book's page, the mini
 * player and the full player. The book streams from Telegram and already has the cover the background loader saved,
 * as in the report from iPhone where picking a PNG did nothing. The test answers the file picker as the system would.
 */
class ArtworkEditTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun image(color: Int, format: Bitmap.CompressFormat, name: String): File = File(context.cacheDir, name).apply {
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        outputStream().use { bitmap.compress(format, 95, it) }
        bitmap.recycle()
    }
    private fun near(pixel: Int, color: Int) =
        abs(Color.red(pixel) - Color.red(color)) + abs(Color.green(pixel) - Color.green(color)) + abs(Color.blue(pixel) - Color.blue(color)) < 60
    /** Waits until the middle of [node]'s image is [color]; on a timeout, says what it saw instead. */
    private fun shows(where: String, color: Int, node: () -> SemanticsNodeInteraction) {
        var seen = "nothing yet"
        try {
            compose.waitUntil(where, 10_000) {
                runCatching { node().captureToImage().asAndroidBitmap().let { it.getPixel(it.width / 2, it.height / 2) } }
                    .onFailure { seen = it.message?.lineSequence()?.take(3)?.joinToString(" ") ?: it.toString() }
                    .onSuccess { seen = "#%06X".format(it and 0xFFFFFF) }
                    .getOrNull()?.let { near(it, color) } == true
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("$where: saw $seen", e) }
    }

    @Test fun aChosenPngThenJpgReplacesTheCoverEverywhere() {
        val store = LibraryStore(context); val before = store.all()
        val prefs = context.getSharedPreferences("progress", 0); val activeBefore = prefs.getString("activeAudio", null)
        val book = Title("tg-artwork-test", "Streamed cover test", "Folio", audio = "telegram://1/2/3/${Uri.encode("Cover test.m4b")}",
            telegramSource = "1:2:3", addedAt = System.currentTimeMillis())
        val blue = Color.rgb(20, 60, 200); val red = Color.rgb(220, 30, 40); val green = Color.rgb(30, 190, 60)
        // The cover the background loader saved for the book.
        val loaded = store.file("${book.id}.jpg"); val custom = store.file("${book.id}-custom.jpg")
        image(blue, Bitmap.CompressFormat.JPEG, "loaded.jpg").let { Artwork.fromBytes(it.readBytes(), loaded); it.delete() }
        val png = image(red, Bitmap.CompressFormat.PNG, "chosen.png"); val jpg = image(green, Bitmap.CompressFormat.JPEG, "chosen.jpg")
        var answer = png
        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent) =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(answer))) else null
        }
        instrumentation.addMonitor(picker)
        store.save(listOf(book) + before)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
                val cover = { compose.onNodeWithContentDescription("Cover of ${book.name}") }
                // The library's tile, rather than the mini player, which names the book too.
                val tile = { compose.onNode(hasContentDescription("Cover of ${book.name}") and hasClickAction()) }
                shows("the library shows the loaded cover", blue, tile)
                tile().performClick()
                // Loading the book gives it a mini player; without Telegram it can't play, which doesn't matter here.
                compose.onNodeWithTag("audio-format").performScrollTo().performClick()
                compose.waitUntil("the player opens", 30_000) { compose.onAllNodesWithContentDescription("Close player").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Close player").performClick()
                compose.waitUntil("the mini player shows", 30_000) { compose.onAllNodesWithTag("mini-player").fetchSemanticsNodes().isNotEmpty() }
                shows("the mini player shows the loaded cover", blue) { compose.onNodeWithTag("mini-cover", useUnmergedTree = true) }
                for ((file, color) in listOf(png to red, jpg to green)) {
                    answer = file
                    compose.onNodeWithText("Edit artwork").performScrollTo().performClick()
                    compose.onNodeWithText("Choose image").performClick()
                    shows("the book's page shows the chosen ${file.extension}", color, cover)
                    shows("the mini player shows the chosen ${file.extension}", color) { compose.onNodeWithTag("mini-cover", useUnmergedTree = true) }
                    compose.onNodeWithContentDescription("Open player", substring = true).performClick()
                    shows("the full player shows the chosen ${file.extension}", color) { compose.onNodeWithTag("player-cover") }
                    compose.onNodeWithContentDescription("Close player").performClick()
                    compose.waitUntil("the player closes", 10_000) { compose.onAllNodesWithTag("audio-player").fetchSemanticsNodes().isEmpty() }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    shows("the library shows the chosen ${file.extension}", color, tile)
                    tile().performClick()
                }
            }
        } finally {
            instrumentation.removeMonitor(picker)
            context.stopService(Intent(context, PlaybackService::class.java))
            store.save(before); loaded.delete(); custom.delete(); png.delete(); jpg.delete()
            prefs.edit().remove("audio:${book.id}").remove("audioModified:${book.id}").remove("lastOpened:${book.id}").remove("audioDuration:${book.id}")
                .putString("activeAudio", activeBefore).commit()
        }
    }
}
