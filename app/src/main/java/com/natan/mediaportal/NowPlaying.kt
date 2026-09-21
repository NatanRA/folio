package com.natan.mediaportal

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Forward30
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** The chapters the service split a title into; one item means the book has none. */
private fun Player.chaptersOf(titleId: String): List<AudioChapter> {
    if (currentMediaItem?.mediaId != titleId || mediaItemCount < 2) return emptyList()
    return (0 until mediaItemCount).map { getMediaItemAt(it) }.filter { it.mediaId == titleId }
        .mapIndexed { index, item -> AudioChapter(item.mediaMetadata.artist?.toString().orEmpty(), item.chapterStartMs).let { it.copy(name = it.displayName(index)) } }
}

/**
 * The full-screen player. Nothing scrolls: chapters open in their own sheet, so the controls never
 * cover them. It follows the app theme like every other screen, with the accent marking what can be
 * touched. [fallbackCover] draws the typographic cover when the title has no artwork.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioPlayerScreen(title: Title, player: Player, artwork: Bitmap?, prefs: SharedPreferences, onStreamReady: () -> Unit, onClose: () -> Unit,
    coverModifier: Modifier = Modifier, fallbackCover: @Composable (Modifier) -> Unit) {
    val motion = remember(player) { PlaybackMotionState().apply { report(player) } }
    var duration by remember(player) { mutableLongStateOf(player.bookDuration) }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    var chapters by remember(player, title.id) { mutableStateOf(player.chaptersOf(title.id)) }
    var playbackError by remember(title.id) { mutableStateOf<String?>(null) }
    var speedOpen by remember { mutableStateOf(false) }
    var chaptersOpen by remember { mutableStateOf(false) }
    DisposableEffect(player, title.id) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { duration = player.bookDuration; if (title.streaming && state == Player.STATE_READY) onStreamReady() }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { duration = player.bookDuration; chapters = player.chaptersOf(title.id) }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { chapters = player.chaptersOf(title.id) }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) { speed = parameters.speed }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { playbackError = if (title.streaming) "Couldn’t buffer audio. Check your connection and Telegram sign-in, then retry." else "This audio could not be played. The file may be damaged or use an unsupported codec." }
            // Anything that starts, stops or moves playback is a fresh position report, besides the regular ones below.
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) motion.report(player)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // The player is asked where it is twice a second, like the iOS one; the bar glides between reports on its own.
    LaunchedEffect(player) { while (true) { motion.report(player); duration = player.bookDuration; playing = player.isPlaying; delay(500) } }
    LaunchedEffect(motion) {
        snapshotFlow { motion.moving }.collectLatest { moving -> if (moving) while (true) withInfiniteAnimationFrameMillis { motion.frame() } }
    }
    // Derived, so the screen recomposes when the chapter changes rather than on every frame the bar moves.
    val current by remember(motion, chapters) { derivedStateOf { chapterIndexAt(chapters, motion.shown) } }
    val shownSecond by remember(motion) { derivedStateOf { motion.shown.toLong() / 1000 * 1000 } }
    val chapterStart = if (chapters.size > 1) chapters[current].startMs else 0L
    val chapterLength = if (chapters.size > 1) chapterLengthMs(chapters, current, duration) else duration
    val ready = duration > 0 && playbackError == null
    fun seek(to: Long, eased: Boolean = true) {
        // The bar learns of the jump first, so the player's own report of it, which can arrive during seekBook, finds it pending.
        motion.seek(to.coerceIn(0, duration), player, eased, chapters)
        player.seekBook(to)
        // A move the listener made here settles the book, so a position pulled from another
        // device is no longer waiting for the player: drop the mark that says it is.
        prefs.edit().putLong("audio:${title.id}", to).remove("pulled:${title.id}")
            .putLong("audioModified:${title.id}", Positions.stamp(System.currentTimeMillis(), prefs.getLong("audioModified:${title.id}", 0))).apply()
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) { Icon(Icons.Outlined.KeyboardArrowDown, "Close player") }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(14.dp)
            val image = remember(artwork) { artwork?.asImageBitmap() }
            if (artwork != null && image != null) Image(image, null, coverModifier.aspectRatio(artwork.width.toFloat() / artwork.height, matchHeightConstraintsFirst = true)
                .shadow(16.dp, shape).clip(shape).testTag("player-cover"), contentScale = ContentScale.Crop)
            else fallbackCover(coverModifier.aspectRatio(.67f, matchHeightConstraintsFirst = true))
        }
        Text(title.name, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("player-title"))
        if (title.author.isNotBlank()) Text(title.author, color = muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (chapters.size > 1) TextButton(onClick = { chaptersOpen = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 40.dp)) {
            Text(chapters[current].name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
        }
        playbackError?.let { message ->
            Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            if (title.streaming) TextButton(onClick = { playbackError = null; player.prepare(); player.play() }, contentPadding = PaddingValues(0.dp)) { Text("Retry streaming") }
        }
        Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("audio-controls")) {
            ChapterScrubber(chapterStart, chapterLength, { motion.shown }, ready, ::seek)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                val accent = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                IconButton(onClick = { seek((motion.skipBase(player) - 15000).coerceAtLeast(0)) }, enabled = ready, colors = accent, modifier = Modifier.size(60.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Replay, "Back 15 seconds", Modifier.size(36.dp))
                        Text("15", fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                FilledTonalIconButton(onClick = { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() } },
                    enabled = ready, modifier = Modifier.size(width = 80.dp, height = 64.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .18f), contentColor = MaterialTheme.colorScheme.primary)) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(36.dp))
                }
                IconButton(onClick = { seek((motion.skipBase(player) + 30000).coerceAtMost(duration)) }, enabled = ready, colors = accent, modifier = Modifier.size(60.dp)) {
                    Icon(Icons.Outlined.Forward30, "Forward 30 seconds", Modifier.size(36.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { speedOpen = true }, enabled = player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH), modifier = Modifier.heightIn(min = 48.dp).testTag("playback-speed")) {
                    Text("${speed.toString().removeSuffix(".0")}×", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                SleepTimer(prefs)
                IconButton(onClick = { chaptersOpen = true }, enabled = chapters.size > 1, colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                    Icon(Icons.AutoMirrored.Outlined.List, "Chapters")
                }
            }
        }
    }
    if (speedOpen) ModalBottomSheet(onDismissRequest = { speedOpen = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Playback speed", style = MaterialTheme.typography.headlineSmall)
            Text("Applies to all audiobooks. Voices keep their natural pitch.", color = muted, modifier = Modifier.padding(vertical = 12.dp))
            listOf(listOf(.5f, .75f, 1f), listOf(1.25f, 1.5f, 1.75f), listOf(2f, 2.5f, 3f)).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { value ->
                        FilterChip(selected = kotlin.math.abs(speed - value) < .01f, onClick = { player.setPlaybackSpeed(value); speedOpen = false },
                            label = { Text("${value}×") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    }
                }
            }
            TextButton(onClick = { speedOpen = false }, modifier = Modifier.align(Alignment.End)) { Text("Done") }
        }
    }
    if (chaptersOpen) ChapterTimelineSheet(chapters, current, { shownSecond }, duration, onSelect = { seek(it.startMs) }, onDismiss = { chaptersOpen = false })
}

/**
 * Scrubs within the current chapter, as the lock screen does, so a long book still gives fine
 * control. The chapter a drag starts in is held until release, so playback crossing into the next
 * chapter cannot move the scale under the finger. [position] is read while drawing, so the fill
 * glides every frame while only the labels, once a second, recompose.
 */
@Composable
private fun ChapterScrubber(chapterStart: Long, chapterLength: Long, position: () -> Double, enabled: Boolean, onSeek: (Long, Boolean) -> Unit) {
    var held by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var scrub by remember { mutableLongStateOf(0L) }
    val latestStart by rememberUpdatedState(chapterStart)
    val latestLength by rememberUpdatedState(chapterLength)
    val latestSeek by rememberUpdatedState(onSeek)
    val start = held?.first ?: chapterStart
    val length = (held?.second ?: chapterLength).coerceAtLeast(1)
    val elapsed by remember(start, length) { derivedStateOf { if (held == null) elapsedLabelMs(position(), start, length) else scrub } }
    val thickness by animateDpAsState(if (held != null) 9.dp else 5.dp, label = "Scrubber thickness")
    val fill = MaterialTheme.colorScheme.primary
    Column {
        Box(Modifier.fillMaxWidth().height(32.dp).testTag("audio-timeline")
            .semantics {
                contentDescription = "Chapter position"
                progressBarRangeInfo = ProgressBarRangeInfo(elapsed.toFloat(), 0f..length.toFloat())
                if (enabled) setProgress { value -> onSeek(start + value.toLong().coerceIn(0, length), true); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun at(x: Float) = ((x / size.width).coerceIn(0f, 1f) * (held?.second ?: latestLength).coerceAtLeast(1)).toLong()
                detectHorizontalDragGestures(
                    onDragStart = { offset -> held = latestStart to latestLength.coerceAtLeast(1); scrub = at(offset.x) },
                    onHorizontalDrag = { change, _ -> change.consume(); scrub = at(change.position.x) },
                    // The bar already sits where the finger let go, so the seek lands without easing.
                    onDragEnd = { held?.let { latestSeek(it.first + scrub, false) }; held = null },
                    onDragCancel = { held = null })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> latestSeek(latestStart + ((offset.x / size.width).coerceIn(0f, 1f) * latestLength).toLong(), true) }
            },
            contentAlignment = Alignment.CenterStart) {
            // Its own layer, redrawn alone each frame the fill moves.
            Box(Modifier.fillMaxWidth().height(thickness).graphicsLayer().clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .12f))
                .drawBehind {
                    val at = if (held == null) (position() - start).coerceIn(0.0, length.toDouble()) else scrub.toDouble()
                    drawRoundRect(fill, size = Size(size.width * (at / length).toFloat(), size.height), cornerRadius = CornerRadius(size.height / 2))
                })
        }
        Row(Modifier.fillMaxWidth()) {
            Text(audioTime(elapsed), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f).testTag("audio-elapsed"))
            Text("−" + audioTime(length - elapsed), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.testTag("audio-remaining"))
        }
    }
}

/** Every chapter on one timeline: finished ones dimmed, the current one opened up to show how far in it is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterTimelineSheet(chapters: List<AudioChapter>, current: Int, position: () -> Long, duration: Long, onSelect: (AudioChapter) -> Unit, onDismiss: () -> Unit) {
    val surface = BottomSheetDefaults.ContainerColor
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val rail = MaterialTheme.colorScheme.outlineVariant
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Chapters", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("${chapters.size} chapters · ${hoursAndMinutes(duration - position())} left", color = muted, fontSize = 13.sp)
            }
            FilledTonalButton(onClick = onDismiss) { Text("Done") }
        }
        LazyColumn(state = rememberLazyListState((current - 2).coerceAtLeast(0)), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            itemsIndexed(chapters) { index, chapter ->
                val length = chapterLengthMs(chapters, index, duration)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { onSelect(chapter); onDismiss() }
                    .semantics(mergeDescendants = true) { selected = index == current }) {
                    Box(Modifier.width(20.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Column(Modifier.fillMaxHeight()) {
                            Box(Modifier.weight(1f).width(2.dp).background(if (index == 0) Color.Transparent else rail))
                            Box(Modifier.weight(1f).width(2.dp).background(if (index == chapters.lastIndex) Color.Transparent else rail))
                        }
                        when {
                            index == current -> Box(Modifier.size(18.dp).clip(CircleShape).background(surface).border(2.dp, accent, CircleShape), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                            }
                            index < current -> Box(Modifier.size(10.dp).clip(CircleShape).background(muted.copy(alpha = .55f)))
                            else -> Box(Modifier.size(10.dp).clip(CircleShape).background(surface).border(2.dp, muted.copy(alpha = .5f), CircleShape))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    if (index == current) {
                        val elapsed = (position() - chapter.startMs).coerceIn(0, length)
                        Column(Modifier.weight(1f).padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = .14f)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(chapter.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            LinearProgressIndicator(progress = { if (length > 0) elapsed.toFloat() / length else 0f }, modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = accent, trackColor = accent.copy(alpha = .18f), gapSize = 0.dp, drawStopIndicator = {})
                            Text("${audioTime(elapsed)} of ${audioTime(length)} · started at ${audioTime(chapter.startMs)}", color = muted, fontSize = 12.sp)
                        }
                    } else Column(Modifier.weight(1f).heightIn(min = 58.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
                        Text(chapter.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (index < current) muted else MaterialTheme.colorScheme.onSurface)
                        Text("${audioTime(chapter.startMs)} · ${audioTime(length)}", color = muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * The progress bar's motion for one player: the rules live in [PlaybackProgress], and this keeps the latest of it and
 * where the bar is drawn. [shown] changes every frame while the bar moves, so only drawing should read it directly.
 */
@Stable
private class PlaybackMotionState(private val now: () -> Long = SystemClock::uptimeMillis) {
    private var progress = PlaybackProgress()
    /** Where the bar is drawn, in milliseconds into the book. */
    var shown by mutableDoubleStateOf(0.0)
        private set
    /** Whether the bar moves by itself and needs a frame loop: while audio plays, or during a jump's ease. */
    var moving by mutableStateOf(false)
        private set
    /** Audio moves at the playback speed only while it actually plays; while it buffers after a jump it stands still. */
    private val Player.rate get() = if (isPlaying) playbackParameters.speed.toDouble() else 0.0

    fun report(player: Player) = update(progress.report(player.bookPosition, player.rate, now()))
    fun skipBase(player: Player): Long = progress.skipBase(player.bookPosition, now())
    fun seek(target: Long, player: Player, eased: Boolean, chapters: List<AudioChapter>) = update(progress.seek(target, player.rate, now(), eased, chapters))
    fun frame() { val time = now(); shown = progress.shownAt(time); moving = progress.movingAt(time) }
    private fun update(next: PlaybackProgress) { progress = next; frame() }
}

/** What the playback service holds, for the mini player: which title, whether it plays, and the chapter it is in. */
internal data class NowPlaying(val titleId: String, val playing: Boolean, val detail: String)

/** Kept as a state rather than read here, so only what shows the play state and chapter recomposes when they change. */
@Composable
internal fun rememberNowPlaying(player: Player?): State<NowPlaying?> {
    val state = remember(player) { mutableStateOf<NowPlaying?>(null) }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        fun read() { state.value = player.currentMediaItem?.let { NowPlaying(it.mediaId, player.isPlaying, it.mediaMetadata.artist?.toString().orEmpty()) } }
        val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { read() } }
        read(); player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return state
}

/**
 * A floating glass capsule that keeps a loaded audiobook in reach once the player is closed. It
 * matches the tab bar's material and sits above it; the caller places it so it never covers the bar.
 * [capsuleModifier] and [thumbnailModifier] let the caller morph the capsule and cover into the player.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun MiniPlayer(title: Title, detail: String, playing: Boolean, artwork: Bitmap?, hazeState: HazeState, onOpen: () -> Unit, onToggle: () -> Unit,
    modifier: Modifier = Modifier, capsuleModifier: Modifier = Modifier, thumbnailModifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Row(modifier.padding(horizontal = 28.dp).then(capsuleModifier).fillMaxWidth().height(56.dp).clip(CircleShape)
        .hazeEffect(hazeState, HazeMaterials.thin(MaterialTheme.colorScheme.surfaceContainer))
        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), CircleShape)
        .testTag("mini-player"), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onOpen).padding(start = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Open player, ${title.name}" }, verticalAlignment = Alignment.CenterVertically) {
            Box(thumbnailModifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                val image = remember(artwork) { artwork?.asImageBitmap() }
                if (image != null) Image(image, null, Modifier.fillMaxSize().testTag("mini-cover"), contentScale = ContentScale.Crop)
                else Icon(Icons.Outlined.Headphones, null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onToggle, modifier = Modifier.padding(end = 6.dp).size(44.dp)) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause audiobook" else "Play audiobook", tint = accent)
        }
    }
}
