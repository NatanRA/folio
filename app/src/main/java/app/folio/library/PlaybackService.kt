package app.folio.library

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
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
    companion object {
        /** The book being listened to, so a position pulled from another device never displaces it. */
        @Volatile internal var activeBook: String? = null
        /** How often a playing book's position is written; a pause, seek or chapter change is written at once. */
        internal const val SAVE_INTERVAL_MS = 10_000L
        @Volatile private var loaded: ((String, Long) -> Long?)? = null
        /**
         * Takes a pulled position into the player and answers with the position actually taken, or
         * null when the book must not be moved and so must not be recorded either. Applying and
         * moving are one answer, and it is given on the main thread where positions are saved: a
         * record left in preferences that the player never took would be written straight back over
         * by the next save, under a fresh stamp, and rewind the device it came from.
         *
         * A book the player is not holding is free to record; one it holds is refused while it is
         * being listened to, or while its length is unknown and a seek could only reach the start.
         * A position past a length measured differently here is taken as far as it goes, and kept
         * under the stamp it arrived with, so the original still outranks the clamp at the service.
         */
        internal fun moveIfLoaded(id: String, position: Long): Long? {
            // Without a player there is nothing to disagree with what is written.
            val take = loaded ?: return position
            return take(id, position)
        }
    }
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val resolver = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val progress by lazy { getSharedPreferences("progress", MODE_PRIVATE) }
    private val sleepListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "sleep:chapter") player.setPauseAtEndOfMediaItems(progress.getBoolean("sleep:chapter", false))
    }
    /** The book a pull is moving the player through, so the seek it makes is not read as a write. */
    private var applying: String? = null
    /** The pulled position the player has already been sent to, so it is never sent there twice. */
    private var taken: Pair<String, Long>? = null
    private fun clearSleep() { progress.edit().remove("sleep:deadline").remove("sleep:chapter").apply() }
    /**
     * Positions are written on their own thread with commit(). An apply() leaves the write queued for the main thread
     * to finish at the next notification update or screen change, which held it on the disk for up to 25 ms at a time.
     * One thread keeps the writes in the order they were made.
     */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var savedAt = 0L
    private fun write(change: SharedPreferences.() -> Unit) { if (!writer.isShutdown) writer.execute { progress.change() } }
    /** Records where the player is; [handOver] also offers it to the reader's other devices straight away. */
    private fun savePosition(handOver: Boolean = false) {
        val id = player.currentMediaItem?.mediaId
        if (id == null) { activeBook = null; return }
        savedAt = SystemClock.elapsedRealtime()
        // The player is read here, on its own thread; only the writing moves.
        val position = player.bookPosition
        val duration = player.bookDuration
        val playing = player.isPlaying
        // Intent to play, not sound: rebuffering and a borrowed audio focus silence a listener who
        // has not stopped, and a position landing then is the mid-sentence yank this guards against.
        val listening = playing || player.playWhenReady
        val now = System.currentTimeMillis()
        // A position pulled from another device stays marked until the player has taken it — it may
        // have arrived while this book was loading, before there was a player to move. Writing this
        // one over it would put back a position the player was only ever told to start from, under
        // a stamp claiming this device moved it, and rewind the device that really did.
        val pulled = progress.getLong("pulled:$id", -1)
        if (!listening && pulled >= 0 && pulled == progress.getLong("audio:$id", -1) && pulled != position && taken != (id to pulled)) {
            activeBook = null
            // Until a length is known a seek could only reach the start, so wait rather than write.
            if (duration <= 0) return
            // Sent there once: a length measured differently here can stop short of it, and asking
            // again every second would only seek in place. The next save settles what was reached.
            taken = id to pulled; applying = id
            player.seekBook(pulled)
            return
        }
        // Either the player is there now, or the listener has moved on from it themselves.
        if (pulled >= 0) taken = null
        // Withheld from a pull at once; released only once this write is in, below.
        if (listening) activeBook = id
        write {
            // A pull that landed after this was read has already moved the player on. Writing the
            // position read before it would put the stale one back over it; the next save has the new one.
            if (getLong("pulled:$id", -1) == pulled) {
                val stored = getLong("audio:$id", -1)
                val edit = edit().putLong("audio:$id", position).putString("activeAudio", id).putLong("lastOpened:$id", now)
                if (pulled >= 0) edit.remove("pulled:$id")
                // Only a move counts as this device writing a position, and finishing another device's
                // write is not making one: a position a shorter encode here clamps must not go out
                // stamped now and outrank the very position it was clamped from.
                if (pulled < 0 && Positions.moved(position, stored))
                    edit.putLong("audioModified:$id", Positions.stamp(now, getLong("audioModified:$id", 0)))
                // Home can only turn a saved position into progress if it also knows the length, and the
                // length is not known until the book has loaded, so record it whenever it is available.
                duration.takeIf { it > 0 }?.let { edit.putLong("audioDuration:$id", it) }
                edit.commit()
            }
            // Only a book being listened to is withheld from a pull, and only until the position it is
            // withheld for has been written. This one, and every save queued before it, now is.
            if (!listening) handler.post { if (loaded != null && !(player.isPlaying || player.playWhenReady)) activeBook = null }
            if (handOver) PositionSync.schedule(this@PlaybackService, immediate = true)
            else if (playing) PositionSync.schedule(this@PlaybackService, immediate = false)
        }
    }
    /** A pulled position still waiting for this book's player, which a paused book has no other save to deliver. */
    private fun pullWaiting(): Boolean {
        val id = player.currentMediaItem?.mediaId ?: return false
        val pulled = progress.getLong("pulled:$id", -1)
        return pulled >= 0 && taken != (id to pulled)
    }
    /** A screen of the app leaving view, the app going to the background above all, writes the position at once. */
    private val screens = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityStopped(activity: android.app.Activity) { savePosition(handOver = true) }
        override fun onActivityCreated(activity: android.app.Activity, state: Bundle?) {}
        override fun onActivityStarted(activity: android.app.Activity) {}
        override fun onActivityResumed(activity: android.app.Activity) {}
        override fun onActivityPaused(activity: android.app.Activity) {}
        override fun onActivitySaveInstanceState(activity: android.app.Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: android.app.Activity) {}
    }
    private val saveTick = object : Runnable {
        override fun run() {
            val deadline = progress.getLong("sleep:deadline", 0)
            if (deadline > 0 && System.currentTimeMillis() >= deadline) { player.pause(); clearSleep() }
            if ((player.isPlaying && SystemClock.elapsedRealtime() - savedAt >= SAVE_INTERVAL_MS) || pullWaiting()) savePosition()
            handler.postDelayed(this, 1000)
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
            override fun onEvents(player: Player, events: Player.Events) {
                // A play or pause, a seek, a chapter or book change and a stop are written at once. Steady playback waits
                // for the tick, and so does a stream stalling to buffer, which would otherwise write on every stall.
                val stopped = !player.isPlaying && (!player.playWhenReady || player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE)
                // Pausing or stopping is the moment the reader's other device most needs this, including a pause
                // made while the stream was already silent to buffer, which changes nothing that is playing.
                val handOver = stopped && events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)
                if (handOver || events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) savePosition(handOver)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (oldPosition.mediaItem != null && oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId) clearSleep()
                val now = System.currentTimeMillis()
                oldPosition.mediaItem?.let { item ->
                    // The seek a pull makes is this device taking another device's write, not making
                    // one: recording the position it leaves would put the stale one back, stamped now.
                    if (item.mediaId == applying) { applying = null; return@let }
                    write { edit().putLong("audio:${item.mediaId}", item.chapterStartMs + oldPosition.positionMs.coerceAtLeast(0))
                        .putLong("audioModified:${item.mediaId}", Positions.stamp(now, getLong("audioModified:${item.mediaId}", 0))).commit() }
                }
            }
        })
        // A pulled position reaches a loaded book only through the player, and the answer is given
        // on this thread rather than posted to it, so nothing can start playing between the two.
        loaded = { id, position ->
            val total = player.bookDuration
            when {
                player.currentMediaItem?.mediaId != id -> position
                // Intent to play, not sound: a rebuffering book has not stopped being listened to.
                player.isPlaying || player.playWhenReady -> null
                total <= 0 -> null
                else -> position.coerceIn(0, total).also { if (player.bookPosition != it) { applying = id; player.seekBook(it) } }
            }
        }
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
        application.registerActivityLifecycleCallbacks(screens)
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
            // Written whole and at a bounded size, since the library may be reading this cover at the same moment.
            if (!store.file("${title.id}.jpg").exists()) metadata.embeddedPicture?.let { runCatching { Artwork.fromBytes(it, store.file("${title.id}.jpg")) } }
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
        application.unregisterActivityLifecycleCallbacks(screens)
        handler.removeCallbacks(saveTick)
        savePosition(handOver = true)
        // The last position has to be on disk before the process may go.
        writer.shutdown(); writer.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        activeBook = null
        loaded = null
        session?.release(); player.release(); session = null
        resolver.shutdownNow()
        super.onDestroy()
    }
}
