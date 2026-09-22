package com.natan.mediaportal

import android.content.SharedPreferences
import android.graphics.Bitmap
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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

private val Rail = Color.White.copy(alpha = .16f)

/** The mean colour of a cover, sampled from a small copy so it stays cheap for large artwork. */
internal fun averageArgb(bitmap: Bitmap): Int? {
    val small = Bitmap.createScaledBitmap(bitmap, 24, 36, true)
    val pixels = IntArray(small.width * small.height)
    small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
    if (small !== bitmap) small.recycle()
    return PlayerPalette.average(pixels)
}

/**
 * The player is always light-on-dark over its cover-coloured backdrop, whatever the app theme, so
 * everything inside it, its own sheets included, takes a dark scheme built on that backdrop.
 */
@Composable
internal fun PlayerTheme(backdrop: Color, content: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary.let { if (it.luminance() < .3f) lerp(it, Color.White, .45f) else it }
    val raised = lerp(backdrop, Color.White, .08f)
    MaterialTheme(colorScheme = darkColorScheme(
        primary = accent, onPrimary = Color(0xFF101819), secondary = accent, onSecondary = Color(0xFF101819),
        secondaryContainer = lerp(backdrop, accent, .35f), onSecondaryContainer = Color.White,
        background = backdrop, surface = backdrop, onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color.White.copy(alpha = .7f),
        surfaceContainerLow = backdrop, surfaceContainer = raised, surfaceContainerHigh = raised, surfaceVariant = raised, outline = Color.White.copy(alpha = .3f)
    )) { CompositionLocalProvider(LocalContentColor provides Color.White, content = content) }
}

@Composable
internal fun PlayerDragHandle() {
    Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(Color.White.copy(alpha = .3f)))
}

/** The chapters the service split a title into; one item means the book has none. */
private fun Player.chaptersOf(titleId: String): List<AudioChapter> {
    if (currentMediaItem?.mediaId != titleId || mediaItemCount < 2) return emptyList()
    return (0 until mediaItemCount).map { getMediaItemAt(it) }.filter { it.mediaId == titleId }
        .mapIndexed { index, item -> AudioChapter(item.mediaMetadata.artist?.toString().orEmpty(), item.chapterStartMs).let { it.copy(name = it.displayName(index)) } }
}

/**
 * The full-screen player. Nothing scrolls: chapters open in their own sheet, so the controls never
 * cover them. [fallbackCover] draws the typographic cover when the title has no artwork.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioPlayerScreen(title: Title, player: Player, artwork: Bitmap?, prefs: SharedPreferences, onStreamReady: () -> Unit, onClose: () -> Unit, fallbackCover: @Composable (Modifier) -> Unit) {
    var position by remember(player) { mutableLongStateOf(player.bookPosition) }
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
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) { while (true) { position = player.bookPosition; duration = player.bookDuration; playing = player.isPlaying; delay(500) } }
    val current = chapters.indexOfLast { it.startMs <= position }.coerceAtLeast(0)
    val chapterStart = if (chapters.size > 1) chapters[current].startMs else 0L
    val chapterLength = if (chapters.size > 1) chapterLengthMs(chapters, current, duration) else duration
    val ready = duration > 0 && playbackError == null
    fun seek(to: Long) {
        player.seekBook(to); position = to
        prefs.edit().putLong("audio:${title.id}", to).putLong("audioModified:${title.id}", System.currentTimeMillis()).apply()
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f))) { Icon(Icons.Outlined.KeyboardArrowDown, "Close player") }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(14.dp)
            if (artwork != null) Image(artwork.asImageBitmap(), null, Modifier.aspectRatio(artwork.width.toFloat() / artwork.height, matchHeightConstraintsFirst = true)
                .shadow(28.dp, shape, spotColor = Color.Black.copy(alpha = .6f)).clip(shape), contentScale = ContentScale.Crop)
            else fallbackCover(Modifier.aspectRatio(.67f, matchHeightConstraintsFirst = true))
        }
        Text(title.name, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (title.author.isNotBlank()) Text(title.author, color = muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (chapters.size > 1) TextButton(onClick = { chaptersOpen = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 40.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = .86f))) {
            Text(chapters[current].name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
        }
        playbackError?.let { message ->
            Text(message, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            if (title.streaming) TextButton(onClick = { playbackError = null; player.prepare(); player.play() }, contentPadding = PaddingValues(0.dp)) { Text("Retry streaming") }
        }
        Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("audio-controls")) {
            ChapterScrubber(chapterStart, chapterLength, position, ready, ::seek)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { seek((player.bookPosition - 15000).coerceAtLeast(0)) }, enabled = ready, modifier = Modifier.size(60.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Replay, "Back 15 seconds", Modifier.size(36.dp))
                        Text("15", fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                IconButton(onClick = { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() } },
                    enabled = ready, modifier = Modifier.size(76.dp)) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(54.dp))
                }
                IconButton(onClick = { seek((player.bookPosition + 30000).coerceAtMost(duration)) }, enabled = ready, modifier = Modifier.size(60.dp)) {
                    Icon(Icons.Outlined.Forward30, "Forward 30 seconds", Modifier.size(36.dp))
                }
            }
            CompositionLocalProvider(LocalContentColor provides Color.White.copy(alpha = .8f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { speedOpen = true }, enabled = player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH),
                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current), modifier = Modifier.heightIn(min = 48.dp).testTag("playback-speed")) {
                        Text("${speed.toString().removeSuffix(".0")}×", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    SleepTimer(prefs)
                    IconButton(onClick = { chaptersOpen = true }, enabled = chapters.size > 1) { Icon(Icons.AutoMirrored.Outlined.List, "Chapters") }
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
    if (chaptersOpen) ChapterTimelineSheet(chapters, current, position, duration, onSelect = { seek(it.startMs) }, onDismiss = { chaptersOpen = false })
}

/**
 * Scrubs within the current chapter, as the lock screen does, so a long book still gives fine
 * control. The chapter a drag starts in is held until release, so playback crossing into the next
 * chapter cannot move the scale under the finger.
 */
@Composable
private fun ChapterScrubber(chapterStart: Long, chapterLength: Long, position: Long, enabled: Boolean, onSeek: (Long) -> Unit) {
    var held by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var scrub by remember { mutableLongStateOf(0L) }
    val latestStart by rememberUpdatedState(chapterStart)
    val latestLength by rememberUpdatedState(chapterLength)
    val latestSeek by rememberUpdatedState(onSeek)
    val start = held?.first ?: chapterStart
    val length = (held?.second ?: chapterLength).coerceAtLeast(1)
    val elapsed = if (held == null) (position - start).coerceIn(0, length) else scrub
    val thickness by animateDpAsState(if (held != null) 9.dp else 5.dp, label = "Scrubber thickness")
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp).testTag("audio-timeline")
            .semantics {
                contentDescription = "Chapter position"
                progressBarRangeInfo = ProgressBarRangeInfo(elapsed.toFloat(), 0f..length.toFloat())
                if (enabled) setProgress { value -> onSeek(start + value.toLong().coerceIn(0, length)); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun at(x: Float) = ((x / size.width).coerceIn(0f, 1f) * (held?.second ?: latestLength).coerceAtLeast(1)).toLong()
                detectHorizontalDragGestures(
                    onDragStart = { offset -> held = latestStart to latestLength.coerceAtLeast(1); scrub = at(offset.x) },
                    onHorizontalDrag = { change, _ -> change.consume(); scrub = at(change.position.x) },
                    onDragEnd = { held?.let { latestSeek(it.first + scrub) }; held = null },
                    onDragCancel = { held = null })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> latestSeek(latestStart + ((offset.x / size.width).coerceIn(0f, 1f) * latestLength).toLong()) }
            },
            contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(thickness).clip(CircleShape).background(Color.White.copy(alpha = .24f)))
            Box(Modifier.width(maxWidth * (elapsed.toFloat() / length)).height(thickness).clip(CircleShape).background(Color.White))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(audioTime(elapsed), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("−" + audioTime(length - elapsed), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

/** Every chapter on one timeline: finished ones dimmed, the current one opened up to show how far in it is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterTimelineSheet(chapters: List<AudioChapter>, current: Int, position: Long, duration: Long, onSelect: (AudioChapter) -> Unit, onDismiss: () -> Unit) {
    val backdrop = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = backdrop, dragHandle = { PlayerDragHandle() }) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Chapters", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("${chapters.size} chapters · ${hoursAndMinutes(duration - position)} left", color = muted, fontSize = 13.sp)
            }
            FilledTonalButton(onClick = onDismiss, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = .14f), contentColor = Color.White)) { Text("Done") }
        }
        LazyColumn(state = rememberLazyListState((current - 2).coerceAtLeast(0)), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            itemsIndexed(chapters) { index, chapter ->
                val length = chapterLengthMs(chapters, index, duration)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { onSelect(chapter); onDismiss() }
                    .semantics(mergeDescendants = true) { selected = index == current }) {
                    Box(Modifier.width(20.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Column(Modifier.fillMaxHeight()) {
                            Box(Modifier.weight(1f).width(2.dp).background(if (index == 0) Color.Transparent else Rail))
                            Box(Modifier.weight(1f).width(2.dp).background(if (index == chapters.lastIndex) Color.Transparent else Rail))
                        }
                        when {
                            index == current -> Box(Modifier.size(18.dp).clip(CircleShape).background(backdrop).border(2.dp, accent, CircleShape), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                            }
                            index < current -> Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = .55f)))
                            else -> Box(Modifier.size(10.dp).clip(CircleShape).background(backdrop).border(2.dp, Color.White.copy(alpha = .38f), CircleShape))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    if (index == current) {
                        val elapsed = (position - chapter.startMs).coerceIn(0, length)
                        Column(Modifier.weight(1f).padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = .14f)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(chapter.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            LinearProgressIndicator(progress = { if (length > 0) elapsed.toFloat() / length else 0f }, modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = accent, trackColor = Color.White.copy(alpha = .18f), gapSize = 0.dp, drawStopIndicator = {})
                            Text("${audioTime(elapsed)} of ${audioTime(length)} · started at ${audioTime(chapter.startMs)}", color = muted, fontSize = 12.sp)
                        }
                    } else Column(Modifier.weight(1f).heightIn(min = 58.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
                        Text(chapter.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = if (index < current) .6f else .95f))
                        Text("${audioTime(chapter.startMs)} · ${audioTime(length)}", color = muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** What the playback service holds, for the mini player: which title, whether it plays, and the chapter it is in. */
internal data class NowPlaying(val titleId: String, val playing: Boolean, val detail: String)

@Composable
internal fun rememberNowPlaying(player: Player?): NowPlaying? {
    var state by remember(player) { mutableStateOf<NowPlaying?>(null) }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        fun read() { state = player.currentMediaItem?.let { NowPlaying(it.mediaId, player.isPlaying, it.mediaMetadata.artist?.toString().orEmpty()) } }
        val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { read() } }
        read(); player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return state
}

/**
 * A floating glass capsule that keeps a loaded audiobook in reach once the player is closed. It
 * matches the tab bar's material and sits above it; the caller places it so it never covers the bar.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun MiniPlayer(title: Title, detail: String, playing: Boolean, artwork: Bitmap?, hazeState: HazeState, onOpen: () -> Unit, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Row(modifier.padding(horizontal = 28.dp).fillMaxWidth().height(56.dp).clip(CircleShape)
        .hazeEffect(hazeState, HazeMaterials.thin(MaterialTheme.colorScheme.surfaceContainer))
        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), CircleShape)
        .testTag("mini-player"), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onOpen).padding(start = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Open player, ${title.name}" }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                if (artwork != null) Image(artwork.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
