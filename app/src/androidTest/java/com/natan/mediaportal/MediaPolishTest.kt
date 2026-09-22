package com.natan.mediaportal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MediaPolishTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(name: String) = File(context.cacheDir, name).also { file ->
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    private fun screenshot(name: String) {
        val folder = File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }
        instrumentation.waitForIdleSync()
        Thread.sleep(150) // Capture the composited dialog window, not the activity behind it.
        instrumentation.uiAutomation.takeScreenshot().let { image -> folder.resolve("$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    @Test fun chapterMetadataInMp3AndM4bAndInvalidFile() {
        for (name in listOf("chaptered.mp3", "chaptered.m4b")) {
            val file = fixture(name)
            try {
                val chapters = AudioChapters.read(file)
                assertEquals(name, listOf(AudioChapter("First chapter", 0), AudioChapter("Second chapter", 6000)), chapters)
                if (name.endsWith(".m4b")) java.io.RandomAccessFile(file, "r").use { input ->
                    // This fixture has both formats; exercise the QuickTime track independently of chpl.
                    val quickTime = QuickTimeChapters.read(AudioChapters.RangeInput(input.length()) { offset, count ->
                        input.seek(offset); ByteArray(count).also { input.readFully(it) }
                    })
                    assertEquals(chapters, quickTime)
                }
            } finally { file.delete() }
        }
        val invalid = File(context.cacheDir, "broken.m4b").apply { writeBytes(byteArrayOf(0, 0, 0, 1, 109, 111, 111, 118, 127, 127, 127)) }
        try { assertTrue(AudioChapters.read(invalid).isEmpty()) } finally { invalid.delete() }
    }
    @Test fun embeddedArtworkManualReplacementAndRecovery(): Unit = runBlocking {
        val store = LibraryStore(context); val before = store.all(); val audio = fixture("chaptered.m4b")
        val title = store.import(Uri.fromFile(audio), true, null)
        val custom = store.file("${title.id}-custom.jpg")
        val selected = File(context.cacheDir, "chosen.png")
        try {
            assertNotNull(BitmapFactory.decodeFile(store.file("${title.id}.jpg").path))
            Bitmap.createBitmap(100, 120, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE); selected.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
            }
            context.getSharedPreferences("progress", 0).edit().putLong("audio:${title.id}", 5000).commit()
            Artwork.upload(context, title.id, Uri.fromFile(selected))
            assertTrue(custom.exists())
            assertTrue(Color.blue(BitmapFactory.decodeFile(custom.path).getPixel(50, 50)) > 240)
            assertEquals(5000L, context.getSharedPreferences("progress", 0).getLong("audio:${title.id}", 0))
            val invalid = File(context.cacheDir, "invalid.png").apply { writeText("not an image") }
            try { assertTrue(runCatching { Artwork.upload(context, title.id, Uri.fromFile(invalid)) }.isFailure); assertTrue(custom.exists()) } finally { invalid.delete() }
            assertTrue(Artwork.restore(context, title))
            assertFalse(custom.exists())
            assertNotNull(BitmapFactory.decodeFile(store.file("${title.id}.jpg").path))
        } finally {
            store.save(before); audio.delete(); selected.delete(); custom.delete()
            title.audio?.let { store.file(it).delete() }; store.file("${title.id}.jpg").delete()
            context.getSharedPreferences("progress", 0).edit().remove("audio:${title.id}").commit()
        }
    }
    @Test fun importLabelsArtworkControlsAndChapterSeeking(): Unit = runBlocking {
        val store = LibraryStore(context); val before = store.all(); val file = fixture("chaptered.mp3")
        val title = store.import(Uri.fromFile(file), true, null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithContentDescription("Import file").performClick()
                compose.onNodeWithText("Load e-book").performClick()
                compose.onNodeWithText("Supported files: EPUB, PDF and CBZ comics. Unprotected files only.").assertIsDisplayed()
                screenshot("load-ebook-dialog")
                compose.onNodeWithText("Cancel").performClick()
                compose.onNodeWithContentDescription("Import file").performClick()
                compose.onNodeWithText("Load audiobook").performClick()
                compose.onNodeWithText("Supported files: MP3", substring = true).assertIsDisplayed()
                screenshot("load-audiobook-dialog")
                compose.onNodeWithText("Cancel").performClick()
                compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
                compose.onNodeWithText(title.name).performClick()
                compose.onNodeWithText("Edit artwork").performScrollTo().performClick()
                compose.onNodeWithText("Choose image").assertIsDisplayed()
                compose.onNodeWithText("Recover from file").assertIsEnabled()
                screenshot("artwork-dialog")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.onNodeWithTag("audio-format").performScrollTo().performClick()
                // The player is a full-height sheet whose timeline covers the current chapter only.
                compose.waitUntil(10000) { compose.onAllNodesWithText("First chapter").fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(10000) { compose.onAllNodesWithText("−0:06").fetchSemanticsNodes().isNotEmpty() }
                val controlsBefore = compose.onNodeWithTag("audio-controls").fetchSemanticsNode().boundsInRoot
                compose.onNodeWithContentDescription("Play").assertIsDisplayed().performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Pause").performClick()
                compose.onNodeWithContentDescription("Play").assertIsDisplayed()
                // Halfway along a six-second chapter is 0:03, whatever the rest of the book holds.
                compose.onNodeWithTag("audio-timeline").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .5f, centerY)) }
                compose.waitUntil(5000) { compose.onAllNodesWithText("0:03").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("−0:03").assertExists()
                screenshot("audio-scrub-result")
                compose.onNodeWithContentDescription("Chapters").performClick()
                compose.onNodeWithText("2 chapters", substring = true).assertIsDisplayed()
                screenshot("audiobook-chapters")
                compose.onNodeWithText("Second chapter").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithText("2 chapters", substring = true).fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithText("Second chapter").assertIsDisplayed()
                compose.onNodeWithText("−0:06").assertExists()
                assertEquals(controlsBefore, compose.onNodeWithTag("audio-controls").fetchSemanticsNode().boundsInRoot)
                compose.onNodeWithContentDescription("Play").assertIsDisplayed()
                // Closing keeps the book in a mini player that sits above the tab bar, never over it.
                compose.onNodeWithContentDescription("Close player").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("mini-player").fetchSemanticsNodes().isNotEmpty() }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5000) { compose.onAllNodesWithTag("tab-bar").fetchSemanticsNodes().isNotEmpty() }
                val mini = compose.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
                val tabs = compose.onNodeWithTag("tab-bar").fetchSemanticsNode().boundsInRoot
                assertTrue("Mini player $mini must sit above the tab bar $tabs", mini.bottom <= tabs.top)
                screenshot("mini-player")
                compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
                compose.onNodeWithContentDescription("Open player", substring = true).performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Close player").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Second chapter").assertIsDisplayed()
            }
            assertTrue(context.getSharedPreferences("progress", 0).getLong("audio:${title.id}", 0) >= 6000)
        } finally {
            store.save(before); file.delete(); title.audio?.let { store.file(it).delete() }; store.file("${title.id}.jpg").delete()
            context.getSharedPreferences("progress", 0).edit().remove("audio:${title.id}").commit()
        }
    }

    @Test fun formatButtonsAdaptToAvailableEditions(): Unit = runBlocking {
        val store = LibraryStore(context); val before = store.all()
        val ebook = fixture("alice.epub"); val audio = fixture("chaptered.mp3")
        val imported = store.import(Uri.fromFile(ebook), false, null)
        val title = store.import(Uri.fromFile(audio), true, imported.id)
        val prefs = context.getSharedPreferences("progress", 0)
        prefs.edit().putInt("ebookPercent:${title.id}", 42).putLong("audio:${title.id}", 6000).commit()
        try {
            for (state in listOf("both", "ebook", "audio")) {
                store.save(before + title.copy(ebook = if (state == "audio") null else title.ebook, audio = if (state == "ebook") null else title.audio))
                ActivityScenario.launch(MainActivity::class.java).use {
                    compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
                    compose.onNodeWithText(title.name).performClick()
                    val primary = if (state == "audio") "audio-format" else "ebook-format"
                    compose.onNodeWithTag(primary).performScrollTo().assertIsDisplayed()
                    compose.onNodeWithText("Downloaded · Ready offline").assertDoesNotExist()
                    if (state == "both") {
                        compose.onNodeWithText("42% read").assertIsDisplayed()
                        compose.onNodeWithText("0:06 listened").assertIsDisplayed()
                        val left = compose.onNodeWithTag("ebook-format").fetchSemanticsNode().boundsInRoot
                        val right = compose.onNodeWithTag("audio-format").fetchSemanticsNode().boundsInRoot
                        assertTrue(left.right < right.left)
                        assertEquals(left.width, right.width, 1f)
                        screenshot("detail-both-formats")
                    } else {
                        val missing = if (state == "ebook") "audiobook" else "e-book"
                        compose.onNodeWithText("+ Add $missing").performScrollTo().assertIsDisplayed()
                        screenshot("detail-$state-only")
                        compose.onNodeWithText("+ Add $missing").performClick()
                        compose.onNodeWithText("Load $missing").assertIsDisplayed()
                        compose.onNodeWithText("Cancel").performClick()
                    }
                }
            }
        } finally {
            store.save(before); ebook.delete(); audio.delete()
            title.ebook?.let { store.file(it).delete() }; title.audio?.let { store.file(it).delete() }; store.file("${title.id}.jpg").delete()
            prefs.edit().remove("ebookPercent:${title.id}").remove("audio:${title.id}").commit()
        }
    }
}
