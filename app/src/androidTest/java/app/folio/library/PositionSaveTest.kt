package app.folio.library

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * While a book plays, its position is written every few seconds rather than every second, which kept the main thread
 * waiting on the disk; leaving the app writes it at once, so the position handed to another device is the current one.
 */
class PositionSaveTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun silence(seconds: Int): File = File(context.cacheDir, "Position test.wav").apply {
        val rate = 8000; val bytes = seconds * rate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(bytes)
        }
        outputStream().buffered().use { it.write(header.array()); it.write(ByteArray(bytes)) }
    }

    @Test fun steadyPlaybackWritesSeldomAndLeavingTheAppWritesAtOnce(): Unit = runBlocking {
        val store = LibraryStore(context); val before = store.all()
        val prefs = context.getSharedPreferences("progress", 0); val activeBefore = prefs.getString("activeAudio", null)
        val file = silence(120)
        val title = store.import(Uri.fromFile(file), true, null)
        var controller: MediaController? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
                compose.onNodeWithText(title.name).performClick()
                compose.onNodeWithTag("audio-format").performScrollTo().performClick()
                compose.waitUntil(10000) { compose.onAllNodes(hasContentDescription("Play") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Play").performClick()
                compose.onNodeWithContentDescription("Close player").performClick()
                val future = withContext(Dispatchers.Main) { MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
                val player = future.get(10, TimeUnit.SECONDS).also { controller = it }
                withTimeout(10000) { while (!withContext(Dispatchers.Main) { player.isPlaying }) delay(100) }
                // Starting to play is written at once; after that, a few seconds of steady playback leave it alone.
                delay(800)
                val started = prefs.getLong("audio:${title.id}", -1)
                assertTrue("Starting playback should write a position", started >= 0)
                delay(3000)
                assertEquals("Steady playback shouldn't write every second", started, prefs.getLong("audio:${title.id}", -1))
                val now = withContext(Dispatchers.Main) { player.bookPosition }
                assertTrue("The book should have played on, is at $now ms", now >= started + 2500)
                // Leaving the app writes where playback is now.
                scenario.moveToState(Lifecycle.State.CREATED)
                withTimeout(3000) { while (prefs.getLong("audio:${title.id}", -1) < now) delay(50) }
                assertEquals(title.id, prefs.getString("activeAudio", null))
                // Closing the scenario from the background waits out a long timeout; from the foreground it doesn't.
                scenario.moveToState(Lifecycle.State.RESUMED)
            }
        } finally {
            withContext(Dispatchers.Main) { controller?.let { it.pause(); it.clearMediaItems(); it.release() } }
            context.stopService(Intent(context, PlaybackService::class.java))
            store.save(before); file.delete(); title.audio?.let { store.file(it).delete() }; store.file("${title.id}.jpg").delete()
            prefs.edit().remove("audio:${title.id}").remove("audioModified:${title.id}").remove("lastOpened:${title.id}").remove("audioDuration:${title.id}")
                .putString("activeAudio", activeBefore).commit()
        }
    }
}
