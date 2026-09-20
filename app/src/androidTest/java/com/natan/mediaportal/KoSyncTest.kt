package com.natan.mediaportal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Button
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.services.positions
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class KoSyncTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val account = KoAccount("https://sync.example/kosync", "reader", KoProtocol.md5("test-password".toByteArray()))
    private fun nativeText(text: String): android.view.accessibility.AccessibilityNodeInfo {
        var node: android.view.accessibility.AccessibilityNodeInfo? = null
        compose.waitUntil(10000) {
            node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
            node != null
        }
        return node!!
    }
    private fun clickNative(text: String) {
        var node = nativeText(text)
        while (!node.isClickable) node = node.parent ?: error("No clickable parent")
        assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }
    private class Response(url: String, val body: String = "{}", val status: Int = 200) : HttpURLConnection(URL(url)) {
        val output = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body.byteInputStream()
        override fun getOutputStream() = output
    }
    @Test fun protocolMatchingEncryptionAndNetworkFailures(): Unit = runBlocking {
        val fixture = File(context.cacheDir, "kosync-fixture.epub")
        instrumentation.context.assets.open("alice.epub").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        assertEquals("2463b95d5f4568a62cd94dc518a5b1f7", KoProtocol.document(fixture))
        val renamed = File(context.cacheDir, "different-name.epub"); fixture.copyTo(renamed, overwrite = true)
        assertEquals(KoProtocol.document(fixture), KoProtocol.document(renamed))
        assertTrue(runCatching { KoProtocol.endpoint("http://sync.example") }.isFailure)
        assertTrue(runCatching { KoProtocol.parse(JSONObject("""{"progress":"x","percentage":1.2}""")) }.isFailure)
        val settings = KoSettings(context); assertNull(settings.account())
        try {
            settings.save(account)
            assertEquals(account, KoSettings(context).account())
            val stored = context.getSharedPreferences("koreader-sync", Context.MODE_PRIVATE).getString("secret", "")!!
            assertFalse(stored.contains(account.key))
            val calls = mutableListOf<Response>()
            val client = KoSync { url -> Response(url).also { calls += it } }
            client.login(account)
            client.push(account, KoProtocol.document(fixture), .45, "test-device")
            assertEquals("/kosync/users/auth", calls[0].url.path)
            assertEquals("PUT", calls[1].requestMethod)
            assertEquals("application/vnd.koreader.v1+json", calls[1].getRequestProperty("Accept"))
            assertEquals(account.key, calls[1].getRequestProperty("x-auth-key"))
            val payload = JSONObject(calls[1].output.toString())
            assertEquals("nmp-percent-v1:0.45", payload.getString("progress"))
            assertFalse(payload.has("metadata"))
            assertEquals(.45, payload.getDouble("percentage"), 0.00001)
            for (code in listOf(301, 401, 500)) {
                var count = 0
                assertTrue(runCatching { KoSync { url -> count++; Response(url, status = code) }.pull(account, KoProtocol.document(fixture)) }.isFailure)
                assertEquals(1, count)
            }
            assertNull(KoSync { url -> Response(url, status = 404) }.pull(account, KoProtocol.document(fixture)))
        } finally { settings.disconnect(); fixture.delete(); renamed.delete() }
    }
    @Test fun receivePositionRequiresChoiceAndUndoRestoresFinishedState(): Unit = runBlocking {
        val store = LibraryStore(context); val before = store.all()
        val settings = KoSettings(context); assertNull(settings.account())
        val fixture = File(context.cacheDir, "kosync-ui.epub")
        instrumentation.context.assets.open("alice.epub").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val book = store.import(Uri.fromFile(fixture), false, null)
        val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
        val progress = ReadingProgress(prefs, book.id)
        val publication = Books(context).open(store.file(book.ebook!!))
        val positions = publication.positions(); publication.close()
        progress.record(positions.first().toJSON().toString(), 0.0); progress.setFinished(true)
        prefs.edit().putLong("audio:${book.id}", 12345).commit()
        settings.save(account)
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("titleId", book.id)).use { reader ->
                var ready = false
                compose.waitUntil(15000) { reader.onActivity { ready = it.window.decorView.findViewWithTag<Button>("reader-sync").isEnabled }; ready }
                reader.onActivity { activity ->
                    val button = activity.window.decorView.findViewWithTag<Button>("reader-sync")
                    val nav = activity.supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment
                    val mock = KoSync { url -> Response(url, """{"progress":"/body/DocFragment[4]/body/p[3]","percentage":0.5,"device":"KOReader"}""") }
                    val controller = ReaderSync(activity, book, button, nav, positions, mock) {}
                    button.setOnClickListener { controller.show() }
                    button.performClick()
                }
                assertTrue(nativeText("Use other reader").isVisibleToUser)
                assertTrue(progress.finished) // Fetching alone must not change progress.
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }.resolve("kosync-choice.png").outputStream().use { stream -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream) }
                }
                clickNative("Use other reader")
                compose.waitUntil(10000) { progress.percent in 45..55 && !progress.finished }
                settings.disconnect() // Undo is local and must still work without a sync account/network.
                reader.onActivity { it.window.decorView.findViewWithTag<Button>("reader-sync").performClick() }
                clickNative("Undo last received position")
                compose.waitUntil(10000) { progress.finished }
                assertTrue(progress.finished); assertEquals(100, progress.percent)
                assertEquals(12345L, prefs.getLong("audio:${book.id}", 0))
            }
        } finally {
            settings.disconnect(); store.save(before); store.file(book.ebook!!).delete(); store.file("${book.id}.jpg").delete()
            prefs.edit().remove("ebook:${book.id}").remove("ebookPercent:${book.id}").remove("ebookFinished:${book.id}").remove("ebookSyncBackup:${book.id}").remove("audio:${book.id}").commit()
        }
    }
    @Test fun setupShowsSeparateLoginAndGatesSending() {
        assertNull(KoSettings(context).account())
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Server", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Set up KOReader sync").performScrollTo().performClick()
            compose.onNodeWithText("Sync server address").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Connect progress sync").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Bridge installed on my KOReader devices").performScrollTo().assertIsDisplayed()
            instrumentation.waitForIdleSync()
            val output = File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot().let { bitmap -> output.resolve("kosync-setup.png").outputStream().use { stream -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream) } }
        }
    }
}
