package app.folio.library

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Skipping while an audiobook plays: quick taps stack, and neither the time under the bar nor its fill ever steps
 * back on the way. The test clock is stepped a frame at a time so the bar's own frame loop runs, as on screen.
 */
class PlaybackProgressTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** A silent 16-bit mono WAV, long enough to skip around in: the bundled fixtures run 12 s. */
    private fun silence(seconds: Int): File = File(context.cacheDir, "Skipping test.wav").apply {
        val rate = 8000; val bytes = seconds * rate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(bytes)
        }
        outputStream().buffered().use { it.write(header.array()); it.write(ByteArray(bytes)) }
    }

    private fun elapsedSeconds(): Int = compose.onNodeWithTag("audio-elapsed").fetchSemanticsNode().config[SemanticsProperties.Text]
        .single().text.split(':').let { (minutes, seconds) -> minutes.toInt() * 60 + seconds.toInt() }

    /** How far the accent fill reaches along the middle of the bar, in pixels. */
    private fun fillEnd(): Int {
        val bar = compose.onNodeWithTag("audio-timeline").captureToImage().asAndroidBitmap()
        val y = bar.height / 2
        val fill = bar.getPixel(4, y)
        fun near(pixel: Int) = abs(Color.red(pixel) - Color.red(fill)) + abs(Color.green(pixel) - Color.green(fill)) + abs(Color.blue(pixel) - Color.blue(fill)) < 40
        return (4 until bar.width).firstOrNull { !near(bar.getPixel(it, y)) } ?: bar.width
    }

    /** Steps the test clock a frame at a time, in real time, until [condition] holds. */
    private fun stepUntil(timeoutMs: Long, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("Timed out after $timeoutMs ms", SystemClock.uptimeMillis() < until)
            compose.mainClock.advanceTimeByFrame()
        }
    }

    @Test fun threeQuickSkipsMoveNinetySecondsAndTheTimeNeverRunsBackwards(): Unit = runBlocking {
        val store = LibraryStore(context); val before = store.all()
        val prefs = context.getSharedPreferences("progress", 0); val activeBefore = prefs.getString("activeAudio", null)
        val file = silence(180)
        val title = store.import(Uri.fromFile(file), true, null)
        var controller: MediaController? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
                compose.onNodeWithText(title.name).performClick()
                compose.onNodeWithTag("audio-format").performScrollTo().performClick()
                compose.waitUntil(10000) { compose.onAllNodes(hasContentDescription("Play") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                // From here on the test steps the frames itself. With the clock running on its own, the test framework
                // stops endless animations, which would stop the bar's frame loop as soon as playback starts.
                compose.mainClock.autoAdvance = false
                compose.onNodeWithContentDescription("Play").performClick()
                stepUntil(10000) { elapsedSeconds() >= 3 }
                val future = withContext(Dispatchers.Main) { MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
                val player = future.get(10, TimeUnit.SECONDS).also { controller = it }
                assertTrue(withContext(Dispatchers.Main) { player.isPlaying })

                val began = SystemClock.uptimeMillis()
                val start = withContext(Dispatchers.Main) { player.bookPosition }
                val labels = mutableListOf(elapsedSeconds())
                val fills = mutableListOf(fillEnd())
                repeat(3) {
                    compose.onNodeWithContentDescription("Forward 30 seconds").performClick()
                    compose.mainClock.advanceTimeByFrame()
                    labels += elapsedSeconds()
                }
                // Three seconds of frames: the jump's ease, the audio restarting, and playback carrying on.
                while (SystemClock.uptimeMillis() - began < 3000) {
                    compose.mainClock.advanceTimeByFrame()
                    labels += elapsedSeconds()
                    fills += fillEnd()
                }
                val end = withContext(Dispatchers.Main) { player.bookPosition }
                val took = SystemClock.uptimeMillis() - began
                compose.mainClock.autoAdvance = true
                android.util.Log.i("PlaybackProgressTest", "moved ${end - start} ms in $took ms; ${labels.size} labels $labels; ${fills.size} fills $fills")

                labels.zipWithNext().forEachIndexed { index, (earlier, later) ->
                    assertTrue("The elapsed time went back from $earlier to $later s at sample ${index + 1} of $labels", later >= earlier)
                }
                // A pixel of give for the anti-aliased end of the fill.
                fills.zipWithNext().forEachIndexed { index, (earlier, later) ->
                    assertTrue("The fill went back from $earlier to $later px at sample ${index + 1} of $fills", later >= earlier - 1)
                }
                val moved = end - start
                assertTrue("Three taps of +30 s should move 90 s plus the ${took} ms that played, moved $moved ms", moved in 89_500..90_000 + took + 500)
                assertTrue("The label should show the jump, went ${labels.first()} → ${labels.last()} s", labels.last() - labels.first() in 90..90 + took / 1000 + 1)
                withContext(Dispatchers.Main) { player.pause() }
            }
        } finally {
            withContext(Dispatchers.Main) { controller?.let { it.pause(); it.clearMediaItems(); it.release() } }
            context.stopService(Intent(context, PlaybackService::class.java))
            store.save(before); file.delete(); title.audio?.let { store.file(it).delete() }; store.file("${title.id}.jpg").delete()
            prefs.edit().remove("audio:${title.id}").remove("audioModified:${title.id}").putString("activeAudio", activeBefore).commit()
        }
    }
}
