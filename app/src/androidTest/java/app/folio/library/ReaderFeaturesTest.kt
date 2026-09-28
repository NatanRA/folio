package app.folio.library

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.services.positions
import java.io.File

class ReaderFeaturesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private fun capture(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(350)
        val dir = File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }
        inst.uiAutomation.takeScreenshot().let { bitmap -> dir.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
    }
    private suspend fun navigator(scenario: ActivityScenario<ReaderActivity>): EpubNavigatorFragment {
        var nav: EpubNavigatorFragment? = null
        withTimeout(10000) { while (nav == null) { scenario.onActivity { nav = it.supportFragmentManager.findFragmentByTag("reader") as? EpubNavigatorFragment }; delay(100) } }
        withTimeout(10000) { while (withContext(Dispatchers.Main) { nav!!.evaluateJavascript("document.readyState==='complete' && document.body.children.length>0") } != "true") delay(100) }
        delay(600)
        return nav!!
    }
    @Test fun appearanceAndSelectedTextNotesSurviveReopening() = runBlocking {
        val store = LibraryStore(context); val before = store.all()
        val prefs = context.getSharedPreferences("progress", 0)
        val oldAppearance = ReaderAppearance.load(prefs)
        val fixture = File(context.cacheDir, "reader-features.epub")
        inst.context.assets.open("alice.epub").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val book = store.import(Uri.fromFile(fixture), false, null)
        val publication = Books(context).open(store.file(book.ebook!!))
        val locator = publication.positions()[7]; publication.close()
        ReadingProgress(prefs, book.id).record(locator.toJSON().toString(), locator.locations.totalProgression ?: 0.0)
        ReaderAppearance().save(prefs)
        val launch = Intent(context, ReaderActivity::class.java).putExtra("titleId", book.id)
        try {
            ActivityScenario.launch<ReaderActivity>(launch).use { scenario ->
                val nav = navigator(scenario)
                val href = withContext(Dispatchers.Main) { nav.currentLocator.value.href }
                compose.onNodeWithContentDescription("Reader menu").performClick()
                compose.onNodeWithText("Options").performClick()
                compose.onNodeWithContentDescription("Sepia").performClick()
                compose.onNodeWithContentDescription("Sepia").assertIsSelected()
                compose.onNodeWithContentDescription("White").assertIsNotSelected()
                compose.onNodeWithText("Sans serif").performClick()
                compose.waitForIdle(); delay(500)
                val oldFont = withContext(Dispatchers.Main) { nav.evaluateJavascript("parseFloat(getComputedStyle(document.querySelector('p')).fontSize)") }!!.toDouble()
                compose.onNodeWithContentDescription("Text size").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(140f) }
                compose.waitUntil(5000) { ReaderAppearance.load(prefs).size > 120f }
                capture("reader-appearance")
                compose.onNodeWithText("Done").performScrollTo().performClick()
                compose.onNodeWithText("Reader options").assertDoesNotExist()
                compose.waitForIdle()
                assertTrue(ReaderAppearance.load(prefs).sans)
                assertEquals(ReaderPaper.Sepia, ReaderAppearance.load(prefs).paper)
                delay(1000)
                val newFont = withContext(Dispatchers.Main) { nav.evaluateJavascript("parseFloat(getComputedStyle(document.querySelector('p')).fontSize)") }!!.toDouble()
                assertTrue("Rendered text should grow: $oldFont -> $newFont", newFont > oldFont)
                assertEquals("Changing typography must keep the same chapter", href, withContext(Dispatchers.Main) { nav.currentLocator.value.href })
                // Locate a visible word, then use a real long press to open Android's selection actions.
                suspend fun selectWord() {
                    compose.waitForIdle()
                    // Native sheet dismissal and focus restoration finish outside Compose's clock.
                    withTimeout(5000) {
                        var focused = false
                        while (!focused) { scenario.onActivity { focused = it.hasWindowFocus() }; if (!focused) delay(50) }
                    }
                    val point = withContext(Dispatchers.Main) { nav.evaluateJavascript("""
                        (function(){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),n;while(n=w.nextNode()){if(!n.parentElement.closest('p'))continue;for(var m of n.textContent.matchAll(/[A-Za-z]{4,}/g)){var r=document.createRange();r.setStart(n,m.index);r.setEnd(n,m.index+m[0].length);var b=r.getBoundingClientRect();if(b.x>5&&b.right<innerWidth-5&&b.y>25&&b.bottom<innerHeight-25)return [b.x+b.width/2,b.y+b.height/2];}}return null;})()
                    """.trimIndent()) }
                    assertNotNull(point)
                    val xy = JSONArray(point)
                    val offset = IntArray(2); var density = 1f
                    scenario.onActivity { activity ->
                        fun find(view: android.view.View): android.webkit.WebView? {
                            if (view is android.webkit.WebView && view.isShown) return view
                            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                            return null
                        }
                        val web = requireNotNull(find(activity.window.decorView)); web.getLocationOnScreen(offset)
                        density = activity.resources.displayMetrics.density
                    }
                    val x = offset[0] + xy.getDouble(0).toFloat() * density
                    val y = offset[1] + xy.getDouble(1).toFloat() * density
                    val down = SystemClock.uptimeMillis()
                    inst.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
                    delay(900)
                    inst.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
                    delay(500)
                }
                selectWord()
                delay(500); capture("reader-selection")
                compose.onNodeWithTag("reader-selection-toolbar").assertIsDisplayed()
                compose.onNodeWithText("Define").performClick()
                compose.onNodeWithText("English · Available offline").assertIsDisplayed()
                compose.waitUntil(10000) { compose.onAllNodesWithText("Finding definition…").fetchSemanticsNodes().isEmpty() }
                capture("reader-selected-definition")
                compose.onNodeWithContentDescription("Close dictionary").performClick()
                delay(500)
                compose.onNodeWithText("Dictionary", substring = false).assertDoesNotExist()
                selectWord()
                capture("reader-selection-again")
                compose.onNodeWithContentDescription("More selection actions").performClick()
                compose.onNodeWithText("Selected text").assertIsDisplayed()
                capture("reader-selection-actions")
                compose.onNodeWithText("Save word").performClick()
                compose.waitUntil(5000) { ReadingNotes(context, book.id, "reading-words").all().isNotEmpty() }
                selectWord()
                compose.onNodeWithText("Highlight").performClick()
                delay(500); capture("reader-after-highlight")
                withTimeout(5000) { while (ReadingNotes(context, book.id).all().isEmpty()) delay(100) }
                assertTrue(ReadingNotes(context, book.id).all().single().quote.isNotBlank())
                delay(500); capture("reader-highlight")
                compose.onNodeWithContentDescription("Reader menu").performClick()
                compose.onNodeWithText("Highlights & notes").performClick()
                compose.onNodeWithText("Edit note").performClick()
                compose.onNodeWithText("Your note").performTextInput("A curious beginning")
                compose.onNodeWithText("Save").performClick()
                compose.waitUntil(5000) { ReadingNotes(context, book.id).all().single().note == "A curious beginning" }
                compose.onNodeWithText("Your note").assertDoesNotExist()
                capture("reader-notes")
            }
            ActivityScenario.launch<ReaderActivity>(launch).use { scenario ->
                val nav = navigator(scenario)
                assertTrue(ReaderAppearance.load(prefs).size > 120f)
                assertTrue(ReaderAppearance.load(prefs).sans)
                compose.onNodeWithContentDescription("Reader menu").performClick()
                compose.onNodeWithText("Highlights & notes").performClick()
                compose.onNodeWithText("A curious beginning").assertIsDisplayed()
                compose.onNodeWithText("Go to text").performClick()
                compose.onNodeWithText("Highlights & notes").assertDoesNotExist()
                delay(500)
                assertEquals(ReadingNotes(context, book.id).all().single().locator.href, withContext(Dispatchers.Main) { nav.currentLocator.value.href })
                compose.onNodeWithContentDescription("Reader menu").performClick()
                compose.onNodeWithText("Highlights & notes").performClick()
                compose.onNodeWithText("Search this book’s notes").performTextInput("missing phrase")
                compose.onNodeWithText("No matching notes.").assertIsDisplayed()
                compose.onNodeWithText("Search this book’s notes").performTextClearance()
                compose.onNodeWithText("Delete").performClick()
                compose.onNode(hasText("Delete") and hasAnyAncestor(hasAnyDescendant(hasText("Delete highlight?")))).performClick()
                compose.waitUntil(5000) { ReadingNotes(context, book.id).all().isEmpty() }
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitForIdle()
                if (compose.onAllNodesWithText("Highlights & notes").fetchSemanticsNodes().isNotEmpty()) inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.onNodeWithContentDescription("Reader menu").performClick()
                compose.onNodeWithText("Bookmarks").performClick()
                compose.onNodeWithText("Bookmark this page").performClick()
                compose.waitUntil(5000) { ReadingNotes(context, book.id, "reading-bookmarks").all().size == 1 }
                compose.onNodeWithText("Chapters").performClick()
                capture("reader-chapters")
                compose.onNodeWithText("Search", substring = false).performClick()
                compose.onNodeWithText("Search inside this book").performTextInput("Rabbit")
                compose.waitUntil(15000) { compose.onAllNodes(hasText("matches", substring = true)).fetchSemanticsNodes().any { !it.config.toString().contains("No matches") } }
                capture("reader-search")
            }
        } finally { store.save(before); oldAppearance.save(prefs); context.getSharedPreferences("reading-notes", 0).edit().remove(book.id).commit(); context.getSharedPreferences("reading-bookmarks", 0).edit().remove(book.id).commit(); context.getSharedPreferences("reading-words", 0).edit().remove(book.id).commit() }
    }
}
