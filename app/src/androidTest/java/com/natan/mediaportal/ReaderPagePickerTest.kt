package com.natan.mediaportal

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.TextView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.readium.r2.shared.publication.services.positions
import java.io.File

/** The page picker in the reader: a real book's chapters and pages, and turning to a page moves the reader there. */
class ReaderPagePickerTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext

    private fun counter(scenario: ActivityScenario<ReaderActivity>): String {
        var text = ""
        scenario.onActivity { text = it.window.decorView.findViewWithTag<TextView>("reader-page-counter").text.toString() }
        return text
    }
    private suspend fun awaitCounter(scenario: ActivityScenario<ReaderActivity>, expected: String) =
        withTimeout(10000) { while (counter(scenario) != expected) delay(100) }
    private fun keyboardShown(scenario: ActivityScenario<ReaderActivity>): Boolean {
        var shown = false
        scenario.onActivity { shown = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        return shown
    }
    private fun awaitFaces(scenario: ActivityScenario<ReaderActivity>, expected: String) {
        fun seen() = compose.onAllNodesWithTag("page-flip-book").fetchSemanticsNodes().map { it.config[BookFaces] }
        try { compose.waitUntil(5000) { seen() == listOf(expected) } }
        catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            capture("page-picker-faces-timeout")
            val headers = compose.onAllNodesWithTag("page-picker-header").fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.Text) }
            throw AssertionError("Books show ${seen()}, expected \"$expected\"; headers $headers; reader at ${counter(scenario)}", timeout)
        }
    }
    private fun openPicker(scenario: ActivityScenario<ReaderActivity>) {
        // Let Compose see the last picker close first: this test's own waits don't run frames, and a picker closed
        // and reopened without one in between would keep its old page.
        compose.waitUntil(5000) { compose.onAllNodesWithTag("page-flip-book").fetchSemanticsNodes().isEmpty() }
        scenario.onActivity { it.window.decorView.findViewWithTag<TextView>("reader-page-counter").performClick() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("page-flip-book").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(400)
        val dir = File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }
        inst.uiAutomation.takeScreenshot().let { bitmap -> dir.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test fun swipingToAPageAndOpeningItMovesTheReader() = runBlocking {
        val store = LibraryStore(context); val before = store.all()
        val prefs = context.getSharedPreferences("progress", 0)
        val fixture = File(context.cacheDir, "page-picker.epub")
        inst.context.assets.open("alice.epub").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val book = store.import(Uri.fromFile(fixture), false, null)
        val bookName = book.name
        // The library screen makes a title's artwork as it lists it; this test goes straight to the reader.
        Artwork.restore(context, book)
        val publication = Books(context).open(store.file(book.ebook!!))
        val positions = publication.positions()
        val total = positions.size
        val locator = positions[24]; publication.close()
        ReadingProgress(prefs, book.id).record(locator.toJSON().toString(), locator.locations.totalProgression ?: 0.0)
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("titleId", book.id)).use { scenario ->
                withTimeout(15000) { while (!counter(scenario).matches(Regex("~[0-9]+ / $total"))) delay(100) }
                delay(800) // A reflowed first screen can settle on a neighbouring position.
                openPicker(scenario)
                // The book opens at the reader's page, named with its chapter from the table of contents.
                val description = compose.onNodeWithTag("page-flip-book").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                val match = Regex("Page ([0-9]+) of $total, ([IVX]+) · .+").matchEntire(description)
                assertTrue(description, match != null)
                val start = match!!.groupValues[1].toInt()
                assertTrue("Page $start should be in chapter III or IV", match.groupValues[2] in setOf("III", "IV"))

                compose.onNodeWithTag("page-flip-book").performTouchInput { swipeLeft(right - 10f, left + width * .1f, 600) }
                compose.waitUntil(5000) { compose.onAllNodesWithText("Open page ${start + 1}").fetchSemanticsNodes().isNotEmpty() }
                capture("page-picker-turned")
                compose.onNodeWithText("Open page ${start + 1}").performClick()
                awaitCounter(scenario, "~${start + 1} / $total")
                compose.onAllNodesWithTag("page-flip-book").assertCountEquals(0)

                // The page count in the header types a page instead.
                openPicker(scenario)
                compose.onNodeWithTag("page-picker-header").performClick()
                compose.onNodeWithText("Page number").performTextInput("999999")
                compose.onNodeWithText("Go").assertIsNotEnabled()
                compose.onNodeWithText("Page number").performTextClearance()
                compose.onNodeWithText("Page number").performTextInput("3")
                compose.onNodeWithText("Go").performClick()
                awaitCounter(scenario, "~3 / $total")
                // The keyboard goes with the dialog, rather than lingering over the reader.
                withTimeout(5000) { while (keyboardShown(scenario)) delay(100) }

                // At page 1 the book lies shut on its own artwork, captioned with its title; one turn opens it onto
                // the inside of the cover and page 2.
                openPicker(scenario)
                compose.onNodeWithTag("page-picker-header").performClick()
                compose.onNodeWithText("Page number").performTextInput("1")
                compose.onNodeWithText("Go").performClick()
                awaitCounter(scenario, "~1 / $total")
                openPicker(scenario)
                val flipBook = compose.onNodeWithTag("page-flip-book")
                awaitFaces(scenario, "shut: none | cover (artwork)")
                compose.onNode(hasText("Page 1 of $total") and hasText(bookName)).assertIsDisplayed()
                assertTrue(flipBook.fetchSemanticsNode().config[SemanticsProperties.StateDescription].startsWith("Cover, page 1 of $total"))
                capture("page-picker-cover")
                flipBook.performTouchInput { swipeLeft(right - 10f, left + width * .1f, 600) }
                awaitFaces(scenario, "open: inside cover | page 2")
                capture("page-picker-inside-cover")
                compose.onNodeWithText("Open page 2").performClick()
                awaitCounter(scenario, "~2 / $total")
            }
        } finally {
            store.save(before); fixture.delete(); store.file(book.ebook!!).delete(); store.file("${book.id}.jpg").delete()
            prefs.edit().remove("ebook:${book.id}").remove("ebookPercent:${book.id}").remove("lastOpened:${book.id}").commit()
        }
    }
}
