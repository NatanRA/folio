package com.natan.mediaportal

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

class FixedReaderTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun pdfAndComicPagesTurnAndResume() = runBlocking<Unit> {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val store = LibraryStore(context); val before = store.all()
        val imported = mutableListOf<Title>()
        try {
            for (name in listOf("sample.pdf", "comic.cbz")) {
                val source = File(context.cacheDir, name)
                inst.context.assets.open(name).use { input -> source.outputStream().use { input.copyTo(it) } }
                val book = store.import(Uri.fromFile(source), false, null); imported += book
                val count = if (name.endsWith("pdf")) 2 else 3
                FixedPages(store.file(book.ebook!!)).use { pages ->
                    assertEquals(count, pages.count)
                    if (count == 3) { val image = pages.render(1); assertTrue(android.graphics.Color.green(image.getPixel(50, 50)) > 150); image.recycle() }
                }
                val intent = Intent(context, FixedReaderActivity::class.java).putExtra("titleId", book.id)
                ActivityScenario.launch<FixedReaderActivity>(intent).use {
                    compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithContentDescription("Page 1").performTouchInput { swipeLeft() }
                    compose.waitUntil(5000) { compose.onAllNodesWithText("2 / $count").fetchSemanticsNodes().isNotEmpty() }
                    compose.waitUntil(5000) { context.getSharedPreferences("progress", 0).getInt("fixed-page:${book.id}", 0) == 1 }
                    val dir = File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }
                    inst.uiAutomation.takeScreenshot().let { image -> File(dir, "$name-reader.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                }
                ActivityScenario.launch<FixedReaderActivity>(intent).use {
                    compose.waitUntil(10000) { compose.onAllNodesWithText("2 / $count").fetchSemanticsNodes().isNotEmpty() }
                }
                source.delete()
            }
        } finally { store.save(before); imported.forEach { store.file(it.ebook!!).delete(); store.file("${it.id}.jpg").delete() } }
    }
}
