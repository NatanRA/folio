package com.natan.mediaportal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.view.View
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.shared.publication.services.positions
import java.io.File

class ReadingCompletionTest {
    @Test fun finishLastPageSurvivesReopeningAndCanBeUndone() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = LibraryStore(context)
        val before = store.all()
        val fixture = File(context.cacheDir, "completion-alice.epub")
        instrumentation.context.assets.open("alice.epub").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val book = store.import(Uri.fromFile(fixture), false, null)
        val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
        val progress = ReadingProgress(prefs, book.id)
        val publication = Books(context).open(store.file(book.ebook!!))
        val positions = publication.positions()
        val first = positions.first()
        val last = positions.last()
        publication.close()
        progress.record(first.toJSON().toString(), 0.0)
        prefs.edit().putLong("audio:${book.id}", 5000).commit()
        val intent = Intent(context, ReaderActivity::class.java).putExtra("titleId", book.id)
        try {
            ActivityScenario.launch<ReaderActivity>(intent).use { reader ->
                withTimeout(10000) {
                    var ready = false
                    while (!ready) {
                        reader.onActivity { ready = it.window.decorView.findViewWithTag<Button>("reader-sync").isEnabled }
                        delay(100)
                    }
                }
                var navigator: EpubNavigatorFragment? = null
                reader.onActivity { navigator = it.supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment }
                withTimeout(10000) {
                    while (withContext(Dispatchers.Main) { navigator!!.evaluateJavascript("document.readyState === 'complete' && document.body.children.length > 0") } != "true") delay(100)
                }
                delay(500)
                reader.onActivity {
                    assertEquals(View.GONE, it.window.decorView.findViewWithTag<Button>("reader-finish").visibility)
                    assertTrue((it.supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment).go(last))
                }
                withTimeout(10000) {
                    var visible = false
                    while (!visible) {
                        reader.onActivity { visible = it.window.decorView.findViewWithTag<Button>("reader-finish").visibility == View.VISIBLE }
                        delay(100)
                    }
                }
                reader.onActivity {
                    it.window.decorView.findViewWithTag<Button>("reader-finish").performClick()
                }
                assertEquals(100, progress.percent)
                assertTrue(progress.finished)
            }
            // Further locator events, including reopening and flipping back, cannot undo completion.
            progress.record(last.toJSON().toString(), .98)
            assertEquals(100, ReadingProgress(prefs, book.id).percent)
            ActivityScenario.launch<ReaderActivity>(intent).use { reader ->
                Thread.sleep(2500)
                reader.onActivity {
                    assertEquals("Finished · 100% · Undo", it.window.decorView.findViewWithTag<Button>("reader-finish").text)
                }
                instrumentation.waitForIdleSync()
                val output = File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    output.resolve("reader-finished.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                reader.onActivity { it.window.decorView.findViewWithTag<Button>("reader-finish").performClick() }
                assertFalse(progress.finished)
                assertTrue(progress.percent in 0..99)
                reader.onActivity { (it.supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment).go(positions[positions.size / 2]) }
                withTimeout(10000) {
                    var hidden = false
                    while (!hidden) {
                        reader.onActivity { hidden = it.window.decorView.findViewWithTag<Button>("reader-finish").visibility == View.GONE }
                        delay(100)
                    }
                }
                delay(500)
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    output.resolve("reader-finish-hidden.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            assertNotNull(prefs.getString("ebook:${book.id}", null))
            assertEquals(5000L, prefs.getLong("audio:${book.id}", 0))
        } finally {
            store.save(before)
            store.file(book.ebook!!).delete()
            File(context.filesDir, "${book.id}.jpg").delete()
            prefs.edit().remove("ebook:${book.id}").remove("ebookPercent:${book.id}")
                .remove("ebookFinished:${book.id}").remove("audio:${book.id}").commit()
        }
    }
}
