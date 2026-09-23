package com.natan.mediaportal

import android.content.Context
import android.net.Uri
import android.view.View
import org.readium.r2.shared.publication.services.positions
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PortalUiTest {
    @Test fun audiobookSpeedPickerUpdatesService() = runBlocking {
        val store = LibraryStore(context); val before = store.all()
        val prefs = context.getSharedPreferences("progress", 0)
        val speedBefore = prefs.getFloat("audioSpeed", 1f)
        val fixture = File(context.cacheDir, "speed-test.m4b")
        instrumentation.context.assets.open("chaptered.m4b").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val book = store.import(Uri.fromFile(fixture), true, null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithText("Library", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
                compose.onNodeWithText(book.name).performClick()
                compose.onNodeWithText("Audiobook").performScrollTo().performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithTag("playback-speed").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("playback-speed").performClick()
                compose.onNodeWithText("Playback speed").assertIsDisplayed()
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    val output = File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }
                    output.resolve("playback-speed.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                compose.onNodeWithText("1.5×").performClick()
                compose.waitUntil(5000) { prefs.getFloat("audioSpeed", 1f) == 1.5f }
                compose.onNodeWithTag("playback-speed").assertTextContains("1.5×")
                compose.onNodeWithContentDescription("Sleep timer").performClick()
                compose.onNodeWithText("15 minutes").performClick()
                compose.waitUntil(5000) { prefs.getLong("sleep:deadline", 0) > System.currentTimeMillis() }
                compose.onNodeWithContentDescription("Play").assertIsDisplayed()
                compose.onNodeWithContentDescription("Play").performClick()
                compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
                prefs.edit().putLong("sleep:deadline", System.currentTimeMillis() + 1000).commit()
                compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() && prefs.getLong("sleep:deadline", 0) == 0L }
                compose.onNodeWithContentDescription("Sleep timer").performClick()
                compose.onNodeWithText("End of chapter").performClick()
                compose.waitUntil(5000) { prefs.getBoolean("sleep:chapter", false) }
                compose.onNodeWithContentDescription("Play").performClick()
                compose.waitUntil(15000) { !prefs.getBoolean("sleep:chapter", false) && compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("playback-speed").performClick()
                compose.onNodeWithText("1.0×").performClick()
                compose.waitUntil(5000) { prefs.getFloat("audioSpeed", 0f) == 1f }
            }
        } finally { store.save(before); prefs.edit().putFloat("audioSpeed", speedBefore).commit() }
    }
    @Test fun readerHomeReusesMainActivity() = runBlocking {
        val store = LibraryStore(context); val before = store.all()
        val fixture = File(context.cacheDir, "home-return.epub")
        instrumentation.context.assets.open("alice.epub").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val book = store.import(Uri.fromFile(fixture), false, null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var original: MainActivity? = null
                scenario.onActivity { original = it }
                compose.waitUntil(10000) { compose.onAllNodesWithText("Library", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
                compose.onNodeWithText(book.name).performClick()
                compose.onNodeWithText("E-book").performScrollTo().performClick()
                var reader: ReaderActivity? = null
                withTimeout(10000) {
                    while (reader == null) {
                        instrumentation.runOnMainSync {
                            reader = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                                .filterIsInstance<ReaderActivity>().firstOrNull()
                        }
                        delay(100)
                    }
                }
                delay(1000)
                instrumentation.runOnMainSync {
                    val row = reader!!.window.decorView.findViewWithTag<android.widget.LinearLayout>("reader-controls")
                    row.visibility = View.VISIBLE
                    row.getChildAt(0).performClick()
                }
                compose.waitUntil(10000) { compose.onAllNodesWithText("Library", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(isSelectable() and hasText("Home")).assertIsSelected()
                scenario.onActivity { assertSame("Home must resume the existing activity", original, it) }
                screenshot("reader-home-return")
            }
        } finally { store.save(before) }
    }
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun screenshot(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun emptyStateServerFormAndNavigation() {
        val store = LibraryStore(context); val before = store.all()
        // Run only on a clean test installation, never discard a signed-in server.
        assertNull(BookServer(context).account())
        store.save(emptyList())
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("Connect your server").assertIsDisplayed().performClick()
                compose.onNodeWithText("Server settings").assertIsDisplayed()
                compose.onNodeWithText("Server address").assertDoesNotExist()
                screenshot("server-collapsed")
                compose.onNodeWithText("BookLore").performClick()
                compose.onNodeWithText("Connect library").assertIsNotEnabled()
                screenshot("server-setup")
                compose.onNodeWithText("Server address").performScrollTo().performTextInput("https://books.example.com")
                compose.onNodeWithText("OPDS username").performScrollTo().performTextInput("preview-reader")
                compose.onNodeWithText("OPDS password").performScrollTo().performTextInput("test-only")
                compose.onNodeWithText("Connect library").assertIsEnabled()
                compose.onNodeWithText("BookLore").performScrollTo().performClick()
                compose.onNodeWithText("Server address").assertDoesNotExist()
                compose.onNodeWithText("BookLore").performClick()
                compose.onNodeWithText("Connect library").performScrollTo().assertIsEnabled()
                compose.onNodeWithText("BookLore").performScrollTo().performClick()
                compose.onNodeWithText("KOReader sync").performClick()
                compose.onNodeWithText("Sync username").performScrollTo().performTextInput("preview-reader")
                compose.onNodeWithText("KOReader sync").performScrollTo().performClick()
                compose.onNodeWithText("Sync username").assertDoesNotExist()
                compose.onNodeWithText("KOReader sync").performClick()
                compose.onNodeWithText("Sync username").performScrollTo().assertTextContains("preview-reader")
                compose.onNodeWithText("Downloads", useUnmergedTree = true).performClick()
                compose.onNodeWithText("No downloads yet").assertIsDisplayed()
                screenshot("downloads-empty")
            }
        } finally { store.save(before) }
    }
    @Test fun coverLibrarySearchAndDownloadedBookDetail(): Unit = runBlocking {
        val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val oldMode = appearance.getString("mode", null); val oldPalette = appearance.getString("palette", null)
        appearance.edit().putString("mode", "Light").putString("palette", "Blue").commit()
        val store = LibraryStore(context); val before = store.all()
        val fixture = File(context.cacheDir, "alice.epub")
        instrumentation.context.assets.open("alice.epub").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val book = store.import(Uri.fromFile(fixture), false, null)
        val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
        prefs.edit().putInt("ebookPercent:${book.id}", 32).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForIdle()
                compose.waitUntil(5000) { File(context.filesDir, "${book.id}.jpg").exists() }
                screenshot("home-library")
                compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
                compose.onNodeWithContentDescription("Search").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("global-search").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("global-search").performTextInput("no such title")
                compose.onNodeWithText("No matching titles").assertIsDisplayed()
                compose.onNode(hasSetTextAction()).performTextClearance()
                compose.onNodeWithText(book.name).performClick()
                compose.onNodeWithText("32% read").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("+ Add audiobook").performScrollTo().assertIsDisplayed()
                screenshot("book-detail")
            }
            assertEquals(book.ebook, LibraryStore(context).all().first { it.id == book.id }.ebook)
            val intent = android.content.Intent(context, ReaderActivity::class.java).putExtra("titleId", book.id)
            ActivityScenario.launch<ReaderActivity>(intent).use { reader ->
                compose.waitUntil(10000) { prefs.getString("ebook:${book.id}", null) != null }
                var navigator: org.readium.r2.navigator.epub.EpubNavigatorFragment? = null
                reader.onActivity { navigator = it.supportFragmentManager.findFragmentByTag("reader") as? org.readium.r2.navigator.epub.EpubNavigatorFragment }
                withTimeout(10000) {
                    while (withContext(Dispatchers.Main) { navigator?.evaluateJavascript("document.readyState === 'complete' && document.body.children.length > 0 && Array.from(document.images).every(i => i.complete && i.naturalWidth > 0)") } != "true") { delay(100) }
                }
                delay(500) // Let WebView composite the loaded page before taking a screenshot.
                var firstCounter = ""
                reader.onActivity {
                    firstCounter = it.window.decorView.findViewWithTag<android.widget.TextView>("reader-page-counter").text.toString()
                    assertTrue(firstCounter.matches(Regex("~[0-9]+ / [0-9]+")))
                }
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }.resolve("reader.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                val publication = Books(context).open(store.file(book.ebook!!))
                val positions = publication.positions()
                val target = positions[positions.size / 2]
                publication.close()
                reader.onActivity { assertTrue(navigator!!.go(target)) }
                withTimeout(10000) {
                    while (withContext(Dispatchers.Main) {
                        var counter = firstCounter
                        reader.onActivity { counter = it.window.decorView.findViewWithTag<android.widget.TextView>("reader-page-counter").text.toString() }
                        counter == firstCounter
                    }) { delay(100) }
                }
                reader.onActivity {
                    val counter = it.window.decorView.findViewWithTag<android.widget.TextView>("reader-page-counter")
                    assertTrue(counter.text.toString().endsWith(" / ${positions.size}"))
                    assertEquals(View.VISIBLE, counter.visibility)
                }
                withTimeout(10000) {
                    while (withContext(Dispatchers.Main) { navigator!!.evaluateJavascript("Array.from(document.querySelectorAll('p')).filter(p => p.textContent.trim().length > 100).some(p => getComputedStyle(p).textAlign === 'left')") } != "true") { delay(100) }
                }
                delay(500)
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }.resolve("reader-left-aligned.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                var displayedPage = 1
                reader.onActivity {
                    val counter = it.window.decorView.findViewWithTag<android.widget.TextView>("reader-page-counter")
                    displayedPage = counter.text.toString().substringBefore(" / ").removePrefix("~").toInt()
                    counter.performClick()
                }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("page-flip-book").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("page-flip-book").assert(SemanticsMatcher("shows page $displayedPage") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription)?.startsWith("Page $displayedPage of ${positions.size}") == true })
                // A reflowed screen can begin at a different locator than the requested midpoint.
                val nextPage = (displayedPage + 1).coerceAtMost(positions.size)
                instrumentation.waitForIdleSync()
                delay(400)
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }.resolve("reader-page-picker.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                // TalkBack's "Next page" action turns the book; the button opens that page.
                val actions = compose.onNodeWithTag("page-flip-book").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
                compose.runOnUiThread { actions.single { it.label == "Next page" }.action() }
                compose.onNodeWithText("Open page $nextPage").performClick()
                compose.waitUntil(10000) {
                    var atPage = false
                    reader.onActivity { atPage = it.window.decorView.findViewWithTag<android.widget.TextView>("reader-page-counter").text.toString() == "~$nextPage / ${positions.size}" }
                    atPage
                }
                reader.onActivity { it.window.decorView.findViewWithTag<android.widget.TextView>("reader-page-counter").performClick() }
                // The page count in the header types a page.
                compose.onNodeWithTag("page-picker-header").performClick()
                compose.onNodeWithText("Page number").performTextInput("999999")
                compose.onNodeWithText("Go").assertIsNotEnabled()
                compose.onNodeWithText("Page number").performTextClearance()
                compose.onNodeWithText("Page number").performTextInput("3")
                compose.onNodeWithText("Go").performClick()
                compose.waitUntil(10000) {
                    var atPage = false
                    reader.onActivity { atPage = it.window.decorView.findViewWithTag<android.widget.TextView>("reader-page-counter").text.toString() == "~3 / ${positions.size}" }
                    atPage
                }
                reader.onActivity { it.window.decorView.findViewWithTag<android.widget.TextView>("reader-page-counter").performClick() }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("page-flip-book").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("page-flip-book").assert(SemanticsMatcher("shows page 3") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription)?.startsWith("Page 3 of ${positions.size}") == true })
                compose.waitForIdle()
                val themeShot = File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    themeShot.resolve("page-picker-theme.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                compose.onNodeWithContentDescription("Close page picker").performClick()
                reader.onActivity { assertEquals("~3 / ${positions.size}", it.window.decorView.findViewWithTag<android.widget.TextView>("reader-page-counter").text.toString()) }
            }
        } finally {
            appearance.edit().putString("mode", oldMode).putString("palette", oldPalette).commit()
            store.save(before); fixture.delete(); book.ebook?.let { store.file(it).delete() }; store.file("${book.id}.jpg").delete()
            prefs.edit().remove("ebookPercent:${book.id}").remove("ebook:${book.id}").commit()
        }
    }
    @Test fun serverCredentialEncryptionRoundTripAndDisconnect() {
        val server = BookServer(context)
        assertNull(server.account())
        val account = ServerAccount("https://example.com/api/v1/opds", "test-reader", "test-password")
        try {
            server.save(account)
            assertEquals(account, BookServer(context).account())
            assertFalse(context.getSharedPreferences("book-server", 0).all.values.any { it == account.password })
        } finally { server.disconnect() }
        assertNull(server.account())
    }
    @Test fun librarySortMenuReordersTheGridAndIsRemembered() {
        val store = LibraryStore(context); val before = store.all()
        val library = context.getSharedPreferences("library", Context.MODE_PRIVATE); val oldSort = library.getString("sort", null)
        library.edit().remove("sort").commit()
        store.save(listOf(Title("sort-new", "Zebra Crossing", "Anne Author", addedAt = 2_000L), Title("sort-old", "Apple Orchard", "Zed Writer", addedAt = 1_000L)))
        fun order() = listOf("Zebra Crossing", "Apple Orchard").sortedWith(compareBy({ compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }, { compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.x }))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithContentDescription("Sort library").assertDoesNotExist()
                compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
                assertEquals(listOf("Apple Orchard", "Zebra Crossing"), order())
                compose.onNodeWithContentDescription("Sort library").performClick()
                compose.onNodeWithText("Date added").performClick()
                assertEquals(listOf("Zebra Crossing", "Apple Orchard"), order())
                compose.onNodeWithContentDescription("Sort library").performClick()
                compose.onNodeWithText("Author").performClick()
                assertEquals(listOf("Zebra Crossing", "Apple Orchard"), order())
                screenshot("library-sort")
            }
            assertEquals("Author", library.getString("sort", null))
        } finally { store.save(before); library.edit().apply { if (oldSort == null) remove("sort") else putString("sort", oldSort) }.commit() }
    }
}
