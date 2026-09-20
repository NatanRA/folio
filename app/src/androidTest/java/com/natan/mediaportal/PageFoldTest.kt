package com.natan.mediaportal

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.services.positions
import java.io.File

class PageFoldTest {
    @Test fun sideSwipeAlwaysCompletesWithoutWaitingForRelease() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val store = LibraryStore(context); val before = store.all()
        val fixture = File(context.cacheDir, "fold.epub")
        inst.context.assets.open("alice.epub").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val book = store.import(Uri.fromFile(fixture), false, null)
        val prefs = context.getSharedPreferences("progress", 0)
        val appearance = context.getSharedPreferences("appearance", 0)
        val oldMode = appearance.getString("mode", null); val oldPalette = appearance.getString("palette", null)
        appearance.edit().putString("mode", "Light").putString("palette", "Lilac").commit()
        val publication = Books(context).open(store.file(book.ebook!!))
        val locator = publication.positions()[7]; publication.close()
        ReadingProgress(prefs, book.id).record(locator.toJSON().toString(), locator.locations.totalProgression ?: 0.0)
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(context, ReaderActivity::class.java).putExtra("titleId", book.id)).use { scenario ->
                var nav: EpubNavigatorFragment? = null
                withTimeout(10000) { while (nav == null) { scenario.onActivity { nav = it.supportFragmentManager.findFragmentByTag("reader") as? EpubNavigatorFragment }; delay(100) } }
                withTimeout(10000) { while (withContext(Dispatchers.Main) { nav!!.evaluateJavascript("document.readyState === 'complete' && document.body.children.length > 0") } != "true") delay(100) }
                delay(700)
                // Use window-level input: an overlay must not steal touches from the book.
                val tapPoint = IntArray(2)
                scenario.onActivity { activity ->
                    val view = activity.window.decorView.findViewWithTag<PageFoldLayout>("reader-fold")
                    view.getLocationOnScreen(tapPoint)
                    tapPoint[0] += view.width / 2; tapPoint[1] += view.height / 2
                    assertEquals(android.view.View.GONE, activity.window.decorView.findViewWithTag<android.view.View>("reader-controls").visibility)
                }
                val tapTime = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    MotionEvent.obtain(tapTime, SystemClock.uptimeMillis(), action, tapPoint[0].toFloat(), tapPoint[1].toFloat(), 0).let {
                        inst.sendPointerSync(it); it.recycle()
                    }
                }
                delay(300)
                scenario.onActivity { assertEquals(android.view.View.VISIBLE, it.window.decorView.findViewWithTag<android.view.View>("reader-controls").visibility) }
                val origin = prefs.getString("ebook:${book.id}", null)
                suspend fun visiblePage() = withContext(Dispatchers.Main) {
                    nav!!.evaluateJavascript("JSON.stringify([location.href, window.scrollX, window.scrollY])")
                }
                val originalPage = visiblePage()
                var startTime = 0L
                fun touch(action: Int, x: Float, y: Float) {
                    scenario.onActivity { activity ->
                        val view = activity.window.decorView.findViewWithTag<PageFoldLayout>("reader-fold")
                        if (action == MotionEvent.ACTION_DOWN) startTime = SystemClock.uptimeMillis()
                        MotionEvent.obtain(startTime, SystemClock.uptimeMillis(), action, view.width * x, view.height * y, 0).let { event -> view.dispatchTouchEvent(event); event.recycle() }
                    }
                }
                fun shot(name: String) {
                    inst.waitForIdleSync()
                    val output = File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }
                    inst.uiAutomation.takeScreenshot().let { image -> output.resolve("$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                }
                scenario.onActivity { activity ->
                    val controls = activity.window.decorView.findViewWithTag<android.view.View>("reader-controls")
                    assertEquals("Controls must reach the bottom of their host", (controls.parent as android.view.View).height, controls.bottom)
                    val row = controls as android.widget.LinearLayout
                    val systemBottom = androidx.core.view.ViewCompat.getRootWindowInsets(row)!!
                        .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
                    for (index in 0 until row.childCount) {
                        val button = row.getChildAt(index)
                        assertTrue("Button must have space above it", button.top > 0)
                        assertTrue("Buttons must be vertically centered within one pixel of density rounding",
                            kotlin.math.abs(button.top - (row.height - systemBottom - button.bottom)) <= 1)
                    }
                }
                shot("reader-controls-bottom")
                touch(MotionEvent.ACTION_DOWN, .85f, .5f)
                touch(MotionEvent.ACTION_MOVE, .75f, .5f)
                scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<PageFoldLayout>("reader-fold").folding) }
                // Even an immediately interrupted/short swipe commits once the fold starts.
                touch(MotionEvent.ACTION_CANCEL, .75f, .5f)
                delay(350)
                shot("reader-side-fold")
                delay(950)
                assertNotEquals(origin, prefs.getString("ebook:${book.id}", null))
                assertNotEquals("The rendered page must move, not only the saved locator", originalPage, visiblePage())
                scenario.onActivity { assertFalse(it.window.decorView.findViewWithTag<PageFoldLayout>("reader-fold").folding) }
                val firstTurn = prefs.getString("ebook:${book.id}", null)
                touch(MotionEvent.ACTION_DOWN, .7f, .3f)
                touch(MotionEvent.ACTION_MOVE, .55f, .3f)
                delay(950) // Finger is still down: the animation must already be finished.
                scenario.onActivity { assertFalse(it.window.decorView.findViewWithTag<PageFoldLayout>("reader-fold").folding) }
                assertNotEquals(firstTurn, prefs.getString("ebook:${book.id}", null))
                touch(MotionEvent.ACTION_UP, .55f, .3f)
                // Real drags arrive as many moves, including moves below the interception threshold.
                val visitedChapters = mutableSetOf<String>()
                repeat(20) {
                    visitedChapters.add(withContext(Dispatchers.Main) { nav!!.currentLocator.value.href.toString() })
                    val visibleBefore = visiblePage()
                    touch(MotionEvent.ACTION_DOWN, .85f, .5f)
                    for (step in 1..12) {
                        touch(MotionEvent.ACTION_MOVE, .85f - step * .025f, .5f)
                        delay(16)
                    }
                    touch(MotionEvent.ACTION_UP, .55f, .5f)
                    delay(1000)
                    val metrics = withContext(Dispatchers.Main) { nav!!.evaluateJavascript("JSON.stringify([document.scrollingElement.scrollWidth,document.scrollingElement.clientWidth,document.scrollingElement.scrollLeft,window.innerWidth])") }
                    assertNotEquals("Repeated swipe $it must change the rendered page ($metrics)", visibleBefore, visiblePage())
                }
                assertTrue("Swipe regression must cross a chapter boundary", visitedChapters.size > 1)
                val after = prefs.getString("ebook:${book.id}", null)
                scenario.onActivity { activity ->
                    fun find(view: android.view.View): Button? {
                        if (view is Button && view.contentDescription == "Next page") return view
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    find(activity.window.decorView)!!.performClick()
                    assertFalse(activity.window.decorView.findViewWithTag<PageFoldLayout>("reader-fold").folding)
                }
                delay(700)
                assertNotEquals(after, prefs.getString("ebook:${book.id}", null))
                scenario.onActivity { activity ->
                    activity.window.decorView.findViewWithTag<android.view.View>("reader-controls").visibility = android.view.View.GONE
                }
                shot("reader-fold-settled")
                fun node(label: String, root: android.view.accessibility.AccessibilityNodeInfo? = inst.uiAutomation.rootInActiveWindow): android.view.accessibility.AccessibilityNodeInfo? {
                    root ?: return null
                    if (root.text?.toString()?.equals(label, ignoreCase = true) == true || root.contentDescription?.toString() == label) return root
                    for (i in 0 until root.childCount) node(label, root.getChild(i))?.let { return it }
                    return null
                }
                fun clickNode(label: String) {
                    var target = checkNotNull(node(label)) { "Missing control: $label" }
                    while (!target.isClickable && target.parent != null) target = target.parent
                    assertTrue(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                }
                clickNode("Reader menu")
                delay(300)
                shot("reader-menu")
                clickNode("Options")
                delay(300)
                assertNotNull(node("Keep navigation buttons visible"))
                shot("reader-material-options")
                clickNode("Done")
                scenario.onActivity { it.showSyncDialog(ReaderDialog("KOReader sync", "Connect your KOReader account under Server → KOReader progress first.")) }
                delay(400)
                shot("reader-material-sync")
                assertNotNull(node("KOReader sync"))
                clickNode("OK")
            }
        } finally {
            appearance.edit().putString("mode", oldMode).putString("palette", oldPalette).commit()
            store.save(before); fixture.delete(); store.file(book.ebook!!).delete(); store.file("${book.id}.jpg").delete()
            prefs.edit().remove("ebook:${book.id}").remove("ebookPercent:${book.id}").commit()
        }
    }
}
