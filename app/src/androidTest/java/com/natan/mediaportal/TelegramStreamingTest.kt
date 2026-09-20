package com.natan.mediaportal

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TelegramStreamingTest {
    @Test fun extractsArtworkBeforePlaybackFromPartialRanges() = kotlinx.coroutines.runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        for (name in listOf("chaptered.m4b", "chaptered.mp3")) {
            val bytes = inst.context.assets.open(name).use { it.readBytes() }
            val destination = java.io.File(inst.targetContext.cacheDir, "prefetch-test.jpg")
            var requested = 0
            try {
                assertTrue(name, Artwork.fromStream(bytes.size.toLong(), destination) { offset, length ->
                    requested += length
                    bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + minOf(length, 4096)))
                })
                assertNotNull(android.graphics.BitmapFactory.decodeFile(destination.path))
                assertTrue(requested <= 8 * 1024 * 1024)
            } finally { destination.delete() }
        }
    }
    @Test fun retainsTelegramCoverAndAudioDetails() {
        val entry = TelegramFile.parse(org.json.JSONObject("""{"chat_id":1,"id":2,"content":{"@type":"messageAudio","audio":{"file_name":"book.m4b","title":"Book title","performer":"Author","audio":{"id":3,"size":1000},"album_cover_thumbnail":{"file":{"id":4}}}}}"""))!!
        assertEquals(4, entry.thumbnail)
        assertEquals("Book title", entry.title)
        assertEquals("Author", entry.performer)
    }
    @Test fun nativePlayerBuffersAndSeeksThroughRangeSource() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val bytes = inst.context.assets.open("chaptered.m4b").use { it.readBytes() }
        val entry = TelegramFile(1, 2, 3, "chaptered.m4b", bytes.size.toLong())
        val ready = java.util.concurrent.CountDownLatch(1)
        var failure: Exception? = null
        lateinit var player: androidx.media3.exoplayer.ExoPlayer
        inst.runOnMainSync {
            val factory = androidx.media3.datasource.DataSource.Factory { TelegramDataSource({ entry }) { _, offset, length -> bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + minOf(length, 4096))) } }
            player = androidx.media3.exoplayer.ExoPlayer.Builder(inst.targetContext).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(factory)).build()
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == androidx.media3.common.Player.STATE_READY) ready.countDown() }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failure = error; ready.countDown() }
            })
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri("telegram://1/1/2/chaptered.m4b")); player.prepare()
        }
        try {
            assertTrue("Native player did not buffer", ready.await(20, java.util.concurrent.TimeUnit.SECONDS))
            assertNull(failure)
            inst.runOnMainSync { assertTrue(player.duration > 0); player.seekTo(player.duration / 2); player.play() }
            Thread.sleep(500)
            inst.runOnMainSync { assertNull(player.playerError); assertTrue(player.currentPosition > 0) }
        } finally { inst.runOnMainSync { player.release() } }
    }
    @Test fun seeksIntoLargeFilesWithoutReadingEarlierAudio() {
        val file = TelegramFile(1, 2, 3, "Book.m4b", 4_000_000_000)
        val offsets = mutableListOf<Long>()
        val source = TelegramDataSource({ file }) { _, offset, length -> offsets.add(offset); ByteArray(minOf(7, length)) { ((offset + it) % 251).toByte() } }
        val start = file.size - 20
        assertEquals(20L, source.open(DataSpec.Builder().setUri("telegram://1/1/2/Book.m4b").setPosition(start).build()))
        val bytes = ByteArray(20); var count = 0
        while (count < 20) count += source.read(bytes, count, 20 - count)
        assertArrayEquals(ByteArray(20) { ((start + it) % 251).toByte() }, bytes)
        assertEquals(-1, source.read(bytes, 0, 1)); assertTrue(offsets.all { it >= start })
        source.close()
        assertEquals(5L, source.open(DataSpec.Builder().setUri(Uri.parse("telegram://1/1/2/Book.m4b")).setPosition(100).setLength(5).build()))
        assertEquals(5, source.read(bytes, 0, 20)); assertEquals(-1, source.read(bytes, 0, 1)); source.close()
    }
    @Test fun readsChapterIndexThroughPartialByteRanges() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("chaptered.m4b").use { it.readBytes() }
        val chapters = AudioChapters.read(AudioChapters.RangeInput(bytes.size.toLong()) { position, count -> bytes.copyOfRange(position.toInt(), minOf(bytes.size, position.toInt() + minOf(count, 3))) })
        assertTrue(chapters.isNotEmpty()); assertEquals(0L, chapters.first().startMs)
    }
    @Test fun cloudBookIsNotAnOfflineDownload() {
        val title = Title("t", "Book", audio = "telegram://1/2/3/book.m4b")
        assertFalse(title.isLocal); assertTrue(title.streaming)
        assertTrue(title.copy(audio = "book.m4b").isLocal)
    }
}
