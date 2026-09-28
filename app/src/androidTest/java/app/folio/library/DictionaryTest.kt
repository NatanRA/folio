package app.folio.library

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DictionaryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext

    @Test fun bundledDefinitionsHandleInflectionsAndUnknownWords() = runBlocking {
        val tougher = OfflineDictionary.lookup(context, "“Tougher,”")
        assertTrue(tougher.any { it.word == "tough" && it.partOfSpeech == "adjective" })
        assertTrue(tougher.any { it.examples.isNotEmpty() })
        assertTrue(OfflineDictionary.lookup(context, "children").any { it.word == "child" })
        assertTrue(OfflineDictionary.lookup(context, "running").any { it.word == "run" })
        assertTrue(OfflineDictionary.lookup(context, "happier").any { it.word == "happy" })
        assertTrue(OfflineDictionary.lookup(context, "ice cream").isNotEmpty())
        assertTrue(OfflineDictionary.lookup(context, "zzzzfolioxyz").isEmpty())
        assertTrue(OfflineDictionary.lookup(context, "").isEmpty())
        assertTrue(OfflineDictionary.lookup(context, "x".repeat(81)).isEmpty())
    }

    @Test fun definitionSheetStaysInReaderAndSupportsSearch(): Unit = runBlocking {
        val store = LibraryStore(context); val before = store.all()
        val file = File(context.cacheDir, "dictionary-test.epub")
        inst.context.assets.open("alice.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
        val book = store.import(Uri.fromFile(file), false, null)
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("titleId", book.id)).use { scenario ->
                scenario.onActivity { it.defineWord("tougher") }
                compose.waitUntil(10000) { compose.onAllNodesWithText("tough", substring = false).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("English · Available offline").assertIsDisplayed()
                compose.onNodeWithText("adjective").assertIsDisplayed()
                capture("dictionary-definition")
                compose.onNodeWithText("Word or phrase").performTextReplacement("zzzzfolioxyz")
                compose.onNodeWithText("Find").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithText("No definition found").fetchSemanticsNodes().isNotEmpty() }
                capture("dictionary-no-result")
                compose.onNodeWithContentDescription("Close dictionary").performClick()
                compose.onNodeWithContentDescription("Reader menu").assertIsDisplayed()
                scenario.onActivity { assertFalse(it.isFinishing) }
            }
        } finally { store.save(before); file.delete(); book.ebook?.let { store.file(it).delete() } }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val folder = File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }
        inst.uiAutomation.takeScreenshot().let { image -> folder.resolve("$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
