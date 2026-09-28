package app.folio.library

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class BackgroundPlaybackTest {
    @Test fun playbackSurvivesBackgroundAndPublishesSystemControls() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val store = LibraryStore(context); val before = store.all()
        val prefs = context.getSharedPreferences("progress", 0); val activeBefore = prefs.getString("activeAudio", null)
        val file = File(context.cacheDir, "background-test.mp3")
        inst.context.assets.open("chaptered.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
        val book = store.import(Uri.fromFile(file), true, null)
        var controller: MediaController? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                val future = withContext(Dispatchers.Main) { MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
                controller = future.get(10, TimeUnit.SECONDS)
                val player = controller!!
                withContext(Dispatchers.Main) {
                    player.setMediaItem(MediaItem.Builder().setMediaId(book.id).build()); player.prepare(); player.play()
                }
                withTimeout(10000) { while (!withContext(Dispatchers.Main) { player.isPlaying }) delay(100) }
                withContext(Dispatchers.Main) {
                    assertEquals(book.name, player.mediaMetadata.title.toString())
                    assertTrue(player.mediaMetadata.artworkData!!.isNotEmpty())
                    assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_BACK))
                }
                activity.moveToState(Lifecycle.State.CREATED)
                inst.uiAutomation.executeShellCommand("input keyevent 223").close()
                delay(2200)
                withContext(Dispatchers.Main) { assertTrue(player.isPlaying); assertTrue(player.currentPosition > 1000) }
                assertTrue(prefs.getLong("audio:${book.id}", 0) > 0)
                val notices = context.getSystemService(NotificationManager::class.java).activeNotifications
                val media = notices.firstOrNull { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
                assertNotNull("Playback must publish an Android media notification", media)
                val token = media!!.notification.extras.getParcelable<android.media.session.MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)!!
                val systemController = android.media.session.MediaController(context, token)
                systemController.transportControls.pause()
                withTimeout(5000) { while (withContext(Dispatchers.Main) { player.isPlaying }) delay(100) }
                withContext(Dispatchers.Main) {
                    assertEquals(2, player.mediaItemCount)
                    assertEquals(6000L, player.duration)
                    player.seekBook(8000)
                }
                delay(500)
                assertTrue(systemController.metadata!!.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) in 5900L..6500L)
                systemController.transportControls.seekTo(1000)
                delay(500)
                withContext(Dispatchers.Main) {
                    assertEquals(1, player.currentMediaItemIndex)
                    assertEquals(7000L, player.bookPosition)
                    assertEquals(1000L, player.currentPosition)
                    // Global resume must map back into the same chapter.
                    player.setMediaItem(MediaItem.Builder().setMediaId(book.id).build(), 8500)
                    player.prepare()
                }
                delay(800)
                withContext(Dispatchers.Main) {
                    assertEquals(1, player.currentMediaItemIndex)
                    assertEquals(8500L, player.bookPosition)
                    player.seekBook(5500); player.play()
                }
                withTimeout(5000) { while (!withContext(Dispatchers.Main) { player.currentMediaItemIndex == 1 && player.currentPosition > 100 }) delay(100) }
                systemController.transportControls.pause()
                delay(500)
                assertTrue(prefs.getLong("audio:${book.id}", 0) >= 6000)
                android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("input keyevent 224")).use { it.readBytes() }
                delay(1000)
                android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("wm dismiss-keyguard")).use { it.readBytes() }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cmd statusbar expand-settings")).use { it.readBytes() }
                delay(1200)
                inst.uiAutomation.takeScreenshot().let { bitmap ->
                    val out = File("/sdcard/Android/media/${context.packageName}/test-output").apply { mkdirs() }
                    out.resolve("android-media-player.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                inst.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
                systemController.sessionActivity!!.send()
                delay(1500)
                assertEquals(book.id, prefs.getString("activeAudio", null))
            }
        } finally {
            withContext(Dispatchers.Main) { controller?.let { it.pause(); it.clearMediaItems(); it.release() } }
            context.stopService(Intent(context, PlaybackService::class.java))
            delay(300)
            inst.uiAutomation.executeShellCommand("input keyevent 224").close()
            store.save(before); file.delete(); book.audio?.let { store.file(it).delete() }; store.file("${book.id}.jpg").delete()
            prefs.edit().remove("audio:${book.id}").putString("activeAudio", activeBefore).commit()
        }
    }
}
