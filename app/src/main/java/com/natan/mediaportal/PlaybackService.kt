package com.natan.mediaportal

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.media.MediaMetadataRetriever
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream

/** Owns playback independently of the activity and publishes Android media controls. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val resolver = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val progress by lazy { getSharedPreferences("progress", MODE_PRIVATE) }
    private val sleepListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "sleep:chapter") player.setPauseAtEndOfMediaItems(progress.getBoolean("sleep:chapter", false))
    }
    private fun clearSleep() { progress.edit().remove("sleep:deadline").remove("sleep:chapter").apply() }
    private fun savePosition() {
        val id = player.currentMediaItem?.mediaId ?: return
        val edit = progress.edit().putLong("audio:$id", player.bookPosition).putString("activeAudio", id)
            .putLong("lastOpened:$id", System.currentTimeMillis())
        // Home can only turn a saved position into progress if it also knows the length, and the
        // length is not known until the book has loaded, so record it whenever it is available.
        player.bookDuration.takeIf { it > 0 }?.let { edit.putLong("audioDuration:$id", it) }
        edit.apply()
    }
    private val saveTick = object : Runnable {
        override fun run() {
            val deadline = progress.getLong("sleep:deadline", 0)
            if (deadline > 0 && System.currentTimeMillis() >= deadline) { player.pause(); clearSleep() }
            savePosition(); handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val sources = androidx.media3.datasource.DataSource.Factory {
            val regular = androidx.media3.datasource.DefaultDataSource.Factory(this).createDataSource()
            object : androidx.media3.datasource.DataSource {
                private var active: androidx.media3.datasource.DataSource = regular
                private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
                override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) { listeners.add(listener) }
                override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                    active = if (spec.uri.scheme == "telegram") TelegramDataSource(TelegramLibrary.get(this@PlaybackService)) else regular
                    listeners.forEach { active.addTransferListener(it) }; return active.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = active.read(buffer, offset, length)
                override fun getUri() = active.uri
                override fun close() = active.close()
            }
        }
        player = ExoPlayer.Builder(this).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(sources)).setSeekBackIncrementMs(15000).setSeekForwardIncrementMs(30000).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            val savedSpeed = progress.getFloat("audioSpeed", 1f)
            setPlaybackSpeed(if (savedSpeed.isFinite()) savedSpeed.coerceIn(.5f, 3f) else 1f)
        }
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && progress.getBoolean("sleep:chapter", false)) clearSleep()
            }
            override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) {
                progress.edit().putFloat("audioSpeed", parameters.speed).apply()
            }
            override fun onEvents(player: Player, events: Player.Events) { savePosition() }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (oldPosition.mediaItem != null && oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId) clearSleep()
                oldPosition.mediaItem?.let { progress.edit().putLong("audio:${it.mediaId}", it.chapterStartMs + oldPosition.positionMs.coerceAtLeast(0)).apply() }
            }
        })
        progress.registerOnSharedPreferenceChangeListener(sleepListener)
        player.setPauseAtEndOfMediaItems(progress.getBoolean("sleep:chapter", false))
        val openPlayer = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra("openAudio", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(openPlayer)
            .setMediaButtonPreferences(listOf(
                CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15).setDisplayName("Back 15 seconds").setPlayerCommand(Player.COMMAND_SEEK_BACK).build(),
                CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30).setDisplayName("Forward 30 seconds").setPlayerCommand(Player.COMMAND_SEEK_FORWARD).build()
            ))
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
                    return Futures.submit(java.util.concurrent.Callable { resolveItems(mediaItems) }, resolver)
                }
                override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    return Futures.submit(java.util.concurrent.Callable {
                    val resolved = resolveItems(mediaItems)
                    val id = mediaItems.getOrNull(startIndex.takeIf { it >= 0 } ?: 0)?.mediaId
                    val position = startPositionMs.coerceAtLeast(0)
                    val index = resolved.indices.lastOrNull { resolved[it].mediaId == id && resolved[it].chapterStartMs <= position } ?: 0
                    MediaSession.MediaItemsWithStartPosition(resolved, index,
                        (position - (resolved.getOrNull(index)?.chapterStartMs ?: 0)).coerceAtLeast(0))
                    }, resolver)
                }
                private fun resolveItems(mediaItems: List<MediaItem>): MutableList<MediaItem> {
                    // Resolve IDs against our library. Never open file URIs supplied by controllers.
                    val store = LibraryStore(this@PlaybackService)
                    val titles = store.all().associateBy { it.id }
                    return mediaItems.flatMap { item ->
                        val title = titles[item.mediaId] ?: return@flatMap emptyList()
                        val audio = title.audio ?: return@flatMap emptyList()
                        if (title.streaming) return@flatMap runCatching { streamingItems(store, title) }.getOrElse {
                            listOf(MediaItem.Builder().setMediaId(title.id).setUri(audio).setMediaMetadata(MediaMetadata.Builder().setTitle(title.name).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK).build()).build())
                        }
                        val file = store.file(audio).takeIf { it.isFile } ?: return@flatMap emptyList()
                        if (file.name.endsWith(".audiobook.json")) {
                            val tracks = AudioBundle.read(file); val cover = artwork(store, title.id)
                            return@flatMap tracks.map { track ->
                                MediaItem.Builder().setMediaId(title.id).setUri(Uri.fromFile(track.file))
                                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title.name).setArtist(track.name)
                                        .setExtras(Bundle().apply { putLong("chapterStartMs", track.startMs); putLong("bookDurationMs", tracks.sumOf { it.durationMs }) })
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK).setIsPlayable(true).setArtworkData(cover, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build()
                            }
                        }
                        val duration = runCatching { MediaMetadataRetriever().use { reader ->
                            reader.setDataSource(file.path)
                            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                        } }.getOrDefault(0)
                        val chapters = AudioChapters.read(file).filter { duration <= 0 || it.startMs < duration }.toMutableList()
                        if (chapters.isEmpty()) chapters.add(AudioChapter("", 0))
                        else if (chapters.first().startMs > 0) chapters.add(0, AudioChapter("Opening", 0))
                        val cover = artwork(store, title.id)
                        chapters.mapIndexed { index, chapter ->
                        MediaItem.Builder().setMediaId(title.id).setUri(Uri.fromFile(file))
                            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(chapter.startMs)
                                .setEndPositionMs(chapters.getOrNull(index + 1)?.startMs ?: C.TIME_END_OF_SOURCE).build())
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(title.name)
                                .setArtist(if (chapters.size > 1) chapter.displayName(index) else title.author.ifBlank { "Audiobook" })
                                .setExtras(Bundle().apply { putLong("chapterStartMs", chapter.startMs); putLong("bookDurationMs", duration) })
                                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK).setIsPlayable(true)
                                .setArtworkData(cover, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build()
                        }
                    }.toMutableList()
                }
            }).build()
        handler.post(saveTick)
    }
    private fun streamingItems(store: LibraryStore, title: Title): List<MediaItem> {
        val telegram = TelegramLibrary.get(this)
        val entry = kotlinx.coroutines.runBlocking { telegram.resolve(Uri.parse(title.audio)) }
        var cacheStart = -1L; var cache = ByteArray(0)
        fun read(offset: Long, count: Int): ByteArray {
            if (offset >= entry.size) return ByteArray(0)
            if (offset < cacheStart || offset >= cacheStart + cache.size) {
                cache = kotlinx.coroutines.runBlocking { telegram.readRange(entry, offset, minOf(256 * 1024L, entry.size - offset).toInt()) }; cacheStart = offset
            }
            val start = (offset - cacheStart).toInt()
            return cache.copyOfRange(start, minOf(cache.size, start + count))
        }
        var duration = 0L
        android.media.MediaMetadataRetriever().use { metadata ->
            metadata.setDataSource(object : android.media.MediaDataSource() {
                override fun getSize() = entry.size
                override fun close() { }
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (size == 0) return 0
                    val bytes = read(position, size); if (bytes.isEmpty()) return -1
                    bytes.copyInto(buffer, offset); return bytes.size
                }
            })
            duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if (!store.file("${title.id}.jpg").exists()) metadata.embeddedPicture?.let { store.file("${title.id}.jpg").writeBytes(it) }
        }
        val chapters = runCatching { AudioChapters.read(AudioChapters.RangeInput(entry.size, ::read)) }.getOrDefault(emptyList()).toMutableList()
        if (chapters.isEmpty() || chapters.first().startMs > 0) chapters.add(0, AudioChapter("Opening", 0))
        val cover = artwork(store, title.id)
        return chapters.mapIndexed { index, chapter -> MediaItem.Builder().setMediaId(title.id).setUri(title.audio)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(chapter.startMs).setEndPositionMs(chapters.getOrNull(index + 1)?.startMs ?: C.TIME_END_OF_SOURCE).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title.name).setArtist(chapter.displayName(index))
                .setExtras(Bundle().apply { putLong("chapterStartMs", chapter.startMs); putLong("bookDurationMs", duration) })
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK).setIsPlayable(true).setArtworkData(cover, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build() }
    }
    private fun artwork(store: LibraryStore, id: String): ByteArray {
        val file = store.file("$id.jpg")
        val cover = if (file.isFile) BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 }) else null
        val image = if (cover != null) {
            val scale = 512f / maxOf(cover.width, cover.height)
            Bitmap.createScaledBitmap(cover, (cover.width * scale).toInt().coerceAtLeast(1), (cover.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== cover) cover.recycle() }
        } else Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF0B1116.toInt())
            val canvas = Canvas(this)
            ContextCompat.getDrawable(this@PlaybackService, R.drawable.portal_logo)?.apply { setBounds(130, 130, 382, 382); draw(canvas) }
        }
        return ByteArrayOutputStream().use { output -> image.compress(Bitmap.CompressFormat.PNG, 100, output); image.recycle(); output.toByteArray() }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (controllerInfo.uid == applicationInfo.uid || controllerInfo.isTrusted) session else null
    override fun onDestroy() {
        progress.unregisterOnSharedPreferenceChangeListener(sleepListener)
        handler.removeCallbacks(saveTick)
        savePosition()
        session?.release(); player.release(); session = null
        resolver.shutdownNow()
        super.onDestroy()
    }
}
