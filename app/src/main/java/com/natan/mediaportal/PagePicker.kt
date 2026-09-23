package com.natan.mediaportal

import android.animation.ValueAnimator
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// The flipbook design's geometry (see PageFlip.kt for its timing and gesture numbers).
private const val BookAspect = 2f / 1.43f
private const val BookTilt = 16f
/** The camera sits this many book-widths away, as the design's CSS perspective does. */
private const val BookCamera = 4.1f
/** A Reduce Motion turn cross-fades the spread instead of turning a leaf. */
private const val FadeMillis = 150
private val TurnEasing = CubicBezierEasing(.3f, .6f, .25f, 1f)

/** Compose animations follow the system animator scale, which Reduce Motion sets to 0; its cross-fade keeps real time. */
private object SteadyMotion : MotionDurationScale { override val scaleFactor = 1f }

/** A graphics layer's camera distance is in Skia's 72-per-inch units; this places it [widths] of [width] px away. */
private fun cameraAt(widths: Float, width: Float) = widths * width / 72f

private data class Spread(val left: Int, val right: Int)

/** A page lifted over the spine. [turning] pages are being turned and lift off the spread; the rest are a scrub's passing flutter. */
@Stable
private class Leaf(val page: Int, val forward: Boolean, val turning: Boolean, val order: Long) {
    /** Degrees turned from where the page lay (0) to where it lands (180). */
    var turned by mutableFloatStateOf(0f)
}

/**
 * The open book's state: the selected page (the right-hand page), the pages in flight and the feel of each move.
 * Turns follow [FlipFeel]'s haptic map; under Reduce Motion they cross-fade and riffles jump.
 */
@Stable
private class FlipBookState(
    val total: Int, reading: Int, val chapters: List<BookChapter>,
    private val reduceMotion: Boolean, private val scope: CoroutineScope, private val feel: (FlipFeel) -> Unit,
) {
    var page by mutableIntStateOf(reading.coerceIn(1, total.coerceAtLeast(1))); private set
    val leaves = mutableStateListOf<Leaf>()
    /** A turn or riffle is running. New gestures wait for it, as the design's do. */
    var busy by mutableStateOf(false); private set
    /** Under Reduce Motion: the spread fading out over the new one, and how far the new one has come in. */
    var fading by mutableStateOf<Spread?>(null); private set
    var fade by mutableFloatStateOf(1f); private set

    private var order = 0L
    private var dragging = false
    private var dragForward = false
    private var dragTurned = 0f
    private var dragLeaf: Leaf? = null
    private var lastFlutter = Long.MIN_VALUE / 2

    /** The two pages lying open. Pages being turned are lifted off it, so it shows the pages beneath them. */
    val spread: Spread get() {
        val turning = leaves.filter { it.turning }
        return if (turning.isEmpty()) Spread(page - 1, page) else Spread(turning.minOf { it.page } - 1, turning.maxOf { it.page } + 1)
    }

    /**
     * How shut the book is: 1 with page 1 selected and nothing turning, 0 open, and in between while the cover turns.
     * The newest cover leaf decides, so a scrub back and forth across page 1 follows the latest swing.
     */
    val closedness: Float get() {
        val cover = leaves.lastOrNull { it.page == 1 }
        return when {
            cover != null -> coverClosedness(cover.forward, cover.turned)
            page == 1 -> 1f
            else -> 0f
        }
    }

    private fun canTurn(forward: Boolean) = if (forward) page < total else page > 1

    private fun launchBusy(block: suspend () -> Unit) {
        busy = true
        scope.launch { try { block() } finally { busy = false } }
    }

    /** One page by key: a full turn, then the landing feel. [many] riffles ten. */
    fun turnByKey(forward: Boolean, many: Boolean) {
        if (busy || dragging || !canTurn(forward)) return
        launchBusy {
            if (many) riffle(10, forward)
            else if (reduceMotion) { val from = page; if (fadeTo(if (forward) page + 1 else page - 1)) feel(landFeel(chapters, from, page)) }
            else if (turn(forward, KeyTurnMillis)) feel(FlipFeel.PageLanded)
        }
    }

    /** Shows [target] at once, for the edge scrubber and accessibility; a scrub also flutters a quick leaf past. */
    fun jumpTo(target: Int, flutter: Boolean = false): Boolean {
        val to = target.coerceIn(1, total)
        if (to == page || busy || dragging) return false
        val from = page
        page = to
        feel(moveFeel(chapters, from, to))
        val now = SystemClock.uptimeMillis()
        // Landing on page 1 or leaving it always swings the cover, so the book shuts or opens rather than jumping.
        val cover = from == 1 || to == 1
        if (flutter && !reduceMotion && (cover || now - lastFlutter > ScrubFlutterGapMillis)) {
            lastFlutter = now
            val forward = to > from
            val leaf = Leaf(if (cover) 1 else if (forward) to - 1 else to, forward, turning = false, order = order++)
            leaves += leaf
            scope.launch { try { animateLeaf(leaf, 180f, ScrubFlutterMillis) } finally { leaves -= leaf } }
        }
        return true
    }

    fun scrubEnded() = feel(FlipFeel.PageLanded)

    /** The finger starts turning a page: the right-hand page when [forward], else the left-hand one back. */
    fun dragStart(forward: Boolean) {
        if (busy || dragging) return
        dragging = true; dragForward = forward; dragTurned = 0f
        if (!reduceMotion && canTurn(forward)) dragLeaf = Leaf(if (forward) page else page - 1, forward, turning = true, order = order++).also { leaves += it }
    }

    /** The finger has moved [distance] px toward the turn, over pages [pageWidth] px wide. */
    fun dragTo(distance: Float, pageWidth: Float) {
        if (!dragging) return
        val turned = turnedDegrees(distance, pageWidth)
        if (dragLeaf != null && dragTurned <= 90f && turned > 90f) feel(FlipFeel.PagePassed)
        dragTurned = turned
        dragLeaf?.turned = turned
    }

    /** The finger lets go at [speed] dp/s toward the turn: finish the turn (and riffle a fling on), or spring back. */
    fun dragEnd(speed: Float) {
        if (!dragging) return
        dragging = false
        val leaf = dragLeaf
        dragLeaf = null
        val forward = dragForward
        if (!canTurn(forward)) { leaf?.let { leaves -= it }; return }
        launchBusy {
            try {
                if (commitsTurn(dragTurned, speed)) {
                    val from = page
                    val step = if (forward) 1 else -1
                    if (leaf != null) {
                        animateLeaf(leaf, 180f, DragFinishMillis); page = from + step; leaves -= leaf
                        feel(landFeel(chapters, from, page))
                        riffle(riffleCount(speed), forward)
                    } else {
                        // Reduce Motion: one cross-fade to where the turn and any riffle would end, with one feel.
                        val extra = riffleCount(speed)
                        if (fadeTo(from + step * (1 + extra))) feel(if (extra > 0) moveFeel(chapters, from, page) else landFeel(chapters, from, page))
                    }
                } else if (leaf != null) animateLeaf(leaf, 0f, SpringBackMillis)
            } finally { leaf?.let { leaves -= it } }
        }
    }

    /** One page turn over [millis]. The page count moves as it starts, so a riffle's overlapping turns each take the next page. */
    private suspend fun turn(forward: Boolean, millis: Int): Boolean {
        if (!canTurn(forward)) return false
        val from = page
        val to = if (forward) from + 1 else from - 1
        val leaf = Leaf(if (forward) from else to, forward, turning = true, order = order++)
        page = to
        leaves += leaf
        try {
            coroutineScope {
                launch { delay((millis * .45f).toLong()); feel(moveFeel(chapters, from, to)) }
                animateLeaf(leaf, 180f, millis)
            }
        } finally { leaves -= leaf }
        return true
    }

    /** [count] pages as a staggered cascade of turns; under Reduce Motion, straight to where it would end. */
    private suspend fun riffle(count: Int, forward: Boolean) {
        if (count <= 0 || !canTurn(forward)) return
        if (reduceMotion) {
            val from = page
            if (fadeTo(page + if (forward) count else -count)) feel(moveFeel(chapters, from, page))
            return
        }
        coroutineScope {
            repeat(count) {
                if (canTurn(forward)) {
                    launch { turn(forward, RiffleTurnMillis) }
                    delay(RiffleStaggerMillis)
                }
            }
        }
        feel(FlipFeel.PageLanded)
    }

    private suspend fun animateLeaf(leaf: Leaf, to: Float, millis: Int) =
        animate(leaf.turned, to, animationSpec = tween(millis, easing = TurnEasing)) { value, _ -> leaf.turned = value }

    private suspend fun fadeTo(target: Int): Boolean {
        val to = target.coerceIn(1, total)
        if (to == page) return false
        fading = spread
        fade = 0f
        page = to
        try { withContext(SteadyMotion) { animate(0f, 1f, animationSpec = tween(FadeMillis)) { value, _ -> fade = value } } }
        finally { fading = null; fade = 1f }
        return true
    }
}

/** The reading paper's colours for the book, with the page edges, the shade of the table it lies on and the cover's cloth. */
private class PaperLook(val page: Color, val ink: Color, val accent: Color, val edge: Color, val edgeLine: Color, val table: Color) {
    val cloth = clothColor(accent)
}

/** A cover board without artwork: a deep, muted tone of the paper's accent, dark enough for [CoverInk] lettering. */
internal fun clothColor(accent: Color): Color = lerp(accent, Color(0xFF15171A), .66f)

/** The light lettering on a cloth cover. */
internal val CoverInk = Color(0xFFF4EEDF)

private fun paperLook(paper: ReaderPaper, accent: Color): PaperLook {
    val (edge, edgeLine, table) = when (paper) {
        ReaderPaper.White -> Triple(Color(0xFFE4E4E0), Color(0xFFCFCFCA), Color(0xFFE9E9EE))
        ReaderPaper.Sepia -> Triple(Color(0xFFEDE3CF), Color(0xFFD9CBB0), Color(0xFFECE6DA))
        ReaderPaper.Sakura -> Triple(Color(0xFFEFDDE1), Color(0xFFDCC1C7), Color(0xFFEFE4E6))
        ReaderPaper.Night -> Triple(Color(0xFF1E1E20), Color(0xFF2C2C2F), Color(0xFF111113))
        ReaderPaper.Indigo -> Triple(Color(0xFF262E40), Color(0xFF343E54), Color(0xFF0F131D))
    }
    return PaperLook(paper.page, paper.ink, accent, edge, edgeLine, table)
}

/**
 * What a page preview is typeset from. [excerpts] fill in as they load; until then a page shows skeleton lines.
 * [cover] is the title's artwork for the front cover; without it the cover is cloth lettered with the title and author.
 */
private class PageSource(val total: Int, val chapters: List<BookChapter>, val bookTitle: String, val author: String, val cover: ImageBitmap?,
    val reading: Int, val excerpts: SnapshotStateMap<Int, String?>)

private enum class PageSide { Left, Right }

/**
 * The e-book reader's page picker: a book of estimated pages to flip through, with the page edges as a scrubber. Page 1
 * is its front cover, shut until turned ([cover] is the title's artwork, if any). It takes the caller's theme, which
 * in the reader follows the paper; the pages are drawn in [paper]'s own colours.
 * [reduceMotion] follows the system animator scale: with animations removed, turns cross-fade and riffles jump.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PagePicker(
    title: String, author: String, cover: ImageBitmap?, paper: ReaderPaper, total: Int, current: Int, chapters: List<BookChapter>,
    excerpt: suspend (Int) -> String?, onSelect: (Int) -> Unit, onDismiss: () -> Unit,
    reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val throttle = remember { TickThrottle() }
    val feel: (FlipFeel) -> Unit = remember(view) {
        { value -> if (throttle.allow(value, SystemClock.uptimeMillis())) view.performHapticFeedback(value.constant(Build.VERSION.SDK_INT)) }
    }
    val book = remember { FlipBookState(total, current, chapters, reduceMotion, scope, feel) }
    val excerpts = remember { mutableStateMapOf<Int, String?>() }
    LaunchedEffect(book.page) {
        for (page in pagesToPreview(book.page, total)) if (!excerpts.containsKey(page)) {
            // A page whose text can't be read keeps its skeleton lines.
            excerpts[page] = try { excerpt(page) } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { null }
        }
    }
    val source = remember(chapters, title, author, cover, current) { PageSource(total, chapters, title, author, cover, current, excerpts) }
    val look = remember(paper, colors.primary) { paperLook(paper, colors.primary) }
    val chapter = chapters.getOrNull(chapterIndexAt(chapters, book.page))
    // The cover is captioned with the book's title, whatever the contents list calls its first file.
    val caption = if (book.page == 1) title else chapter?.caption ?: title
    var typing by remember { mutableStateOf(false) }
    fun open(page: Int) { feel(FlipFeel.PageOpened); onSelect(page) }
    // Short windows (landscape, split screen) give the book the room the spacing would take.
    val compact = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < 600.dp

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "Type a page number", role = Role.Button) { typing = true }
                    .testTag("page-picker-header").padding(vertical = 4.dp), verticalArrangement = Arrangement.Center) {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Page ${book.page}") }
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)) { append(" of $total") }
                    }, fontSize = 22.sp, style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
                    Text(caption, color = colors.onSurfaceVariant, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, contentDescription = "Close page picker") }
            }
            // The book and its edges are physical: page 1 stays on the left in right-to-left languages too.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                FlipBook(book, look, source, caption, { open(book.page) },
                    Modifier.align(Alignment.CenterHorizontally).weight(1f, fill = false)
                        .padding(start = 14.dp, end = 14.dp, top = if (compact) 6.dp else 22.dp, bottom = if (compact) 14.dp else 26.dp))
                EdgeScrubber(book, look, source)
            }
            Button(onClick = { open(book.page) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).heightIn(min = 48.dp)) {
                Text("Open page ${book.page}", style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
            }
        }
    }
    if (typing) PageNumberDialog(total, onGo = { page -> typing = false; open(page) }, onDismiss = { typing = false })
}

@Composable
private fun FlipBook(book: FlipBookState, look: PaperLook, source: PageSource, caption: String, onOpen: () -> Unit, modifier: Modifier) {
    val total = book.total
    val page = book.page
    var focused by remember { mutableStateOf(false) }
    // No fillMaxWidth: the aspect ratio then fits by height in short windows (landscape, split screen) too.
    Box(modifier.aspectRatio(BookAspect)
        // A swipe starting near the screen edge turns the page rather than going back.
        .systemGestureExclusion()
        .testTag("page-flip-book")
        // Keys and focus come before the semantics below, which would otherwise clear focus's own semantics.
        .onFocusChanged { focused = it.isFocused }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.key) {
                Key.DirectionRight -> { book.turnByKey(forward = true, many = event.isShiftPressed); true }
                Key.DirectionLeft -> { book.turnByKey(forward = false, many = event.isShiftPressed); true }
                Key.Enter, Key.NumPadEnter -> { onOpen(); true }
                else -> false
            }
        }
        .focusable()
        // One adjustable control for TalkBack: swipe up or down to change page, double-tap to open it.
        .clearAndSetSemantics {
            contentDescription = "Book"
            stateDescription = if (page == 1) "Cover, page 1 of $total" else "Page $page of $total, $caption"
            bookFaces = describeFaces(book, source)
            progressBarRangeInfo = ProgressBarRangeInfo(page.toFloat(), 1f..total.toFloat().coerceAtLeast(1f), steps = (total - 2).coerceAtLeast(0))
            setProgress("Change page") { value -> book.jumpTo(adjustedPage(book.page, value, total)) }
            customActions = listOf(
                CustomAccessibilityAction("Next page") { book.jumpTo(book.page + 1) },
                CustomAccessibilityAction("Previous page") { book.jumpTo(book.page - 1) })
            onClick("Open page $page") { onOpen(); true }
        }
        .pointerInput(book) {
            val tracker = VelocityTracker()
            var moved = 0f
            var forward = true
            var started = false
            detectDragGestures(
                orientationLock = Orientation.Horizontal,
                onDragStart = { down, _, _ -> tracker.resetTracking(); tracker.addPointerInputChange(down); moved = 0f; started = false },
                // The finger-up event lets the tracker see a finger that stopped before lifting: no fling then.
                onDragEnd = { up -> tracker.addPointerInputChange(up); if (started) tracker.calculateVelocity().x.div(density).let { book.dragEnd(if (forward) -it else it) } },
                onDragCancel = { if (started) book.dragEnd(0f) },
            ) { change, amount ->
                tracker.addPointerInputChange(change)
                change.consume()
                // The first movement picks the page: leftward lifts the right-hand page, rightward brings the left one back.
                if (!started && amount.x != 0f) { forward = amount.x < 0f; started = true; book.dragStart(forward) }
                moved += amount.x
                if (started) book.dragTo(if (forward) -moved else moved, size.width / 2f)
            }
        }
        .pointerInput(book) {
            detectTapGestures { at ->
                when (bookTap(at.x, size.width.toFloat(), book.closedness)) {
                    BookTap.Open -> onOpen()
                    BookTap.Back -> book.turnByKey(forward = false, many = false)
                    BookTap.None -> {}
                }
            }
        }
        .drawWithContent {
            drawContent()
            if (focused) {
                val inset = 8.dp.toPx()
                drawRoundRect(look.accent, topLeft = Offset(-inset, -inset), size = Size(size.width + inset * 2, size.height + inset * 2),
                    cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
        }
    ) {
        TiltedSpread(book.spread, { book.closedness }, total, source, look, Modifier.fillMaxSize(), lift = book) {
            for (leaf in book.leaves) key(leaf.order) { TurningLeaf(leaf, source, look) }
        }
        // Reduce Motion: the spread being left fades out over the new one, each shut or open as its own page 1 has it.
        book.fading?.let { old ->
            TiltedSpread(old, { if (old.right == 1) 1f else 0f }, total, source, look, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - book.fade })
        }
    }
}

/** What each side of the book shows, for tests: "shut: none | cover (artwork)", "open: inside cover | page 2". Not read aloud. */
internal val BookFaces = SemanticsPropertyKey<String>("BookFaces")
private var SemanticsPropertyReceiver.bookFaces by BookFaces

private fun describeFaces(book: FlipBookState, source: PageSource): String {
    fun face(page: Int, onLeft: Boolean) = when (pageFace(page, onLeft)) {
        PageFace.None -> "none"
        PageFace.Cover -> if (source.cover != null) "cover (artwork)" else "cover (cloth)"
        PageFace.InsideCover -> "inside cover"
        PageFace.Text -> "page $page"
    }
    val state = when (book.closedness) { 1f -> "shut"; 0f -> "open"; else -> "turning" }
    return "$state: ${face(book.spread.left, true)} | ${face(book.spread.right, false)}"
}

/**
 * The book lying open (or shut) on its table: tilted away from the reader, with the page edges under each side, and
 * slid left by [closedOffset] so that a shut book's cover sits in the middle.
 */
@Composable
private fun TiltedSpread(spread: Spread, closedness: () -> Float, total: Int, source: PageSource, look: PaperLook, modifier: Modifier,
    lift: FlipBookState? = null, leaves: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier
        .graphicsLayer {
            rotationX = BookTilt; cameraDistance = cameraAt(BookCamera, size.width)
            translationX = -closedOffset(closedness(), size.width / 2f)
        }
        .drawBehind { drawTableAndEdges(spread, total, look, closedness()) }) {
        Row(Modifier.fillMaxSize()) {
            BookPage(spread.left, PageSide.Left, source, look, Modifier.weight(1f).fillMaxHeight()) { lift?.let { liftShade(it, forward = false, PageSide.Left) } }
            BookPage(spread.right, PageSide.Right, source, look, Modifier.weight(1f).fillMaxHeight()) { lift?.let { liftShade(it, forward = true, PageSide.Right) } }
        }
        leaves()
    }
}

/** The shade a page being turned casts on the page it uncovers, deepest while it stands upright. */
private fun DrawScope.liftShade(book: FlipBookState, forward: Boolean, side: PageSide) {
    val lift = book.leaves.filter { it.turning && it.forward == forward }.maxOfOrNull { sin(it.turned * PI / 180).toFloat() } ?: 0f
    if (lift <= 0f) return
    val shade = Color.Black.copy(alpha = .28f * .9f * lift)
    drawRect(if (side == PageSide.Right) Brush.horizontalGradient(0f to shade, .8f to Color.Transparent)
        else Brush.horizontalGradient(.2f to Color.Transparent, 1f to shade))
}

/**
 * The table's soft shade under the book, and the stacked page edges under each side, deeper as more pages lie there.
 * The shade narrows to the cover as the book shuts.
 */
private fun DrawScope.drawTableAndEdges(spread: Spread, total: Int, look: PaperLook, closedness: Float) {
    val width = size.width
    val height = size.height
    val left = width / 2f * closedness.coerceIn(0f, 1f)
    val center = Offset((left + width) / 2f, height * .52f)
    val radius = height * .62f
    scale(scaleX = (width - left) * .56f / radius, scaleY = 1f, pivot = center) {
        drawCircle(Brush.radialGradient(listOf(look.table, look.table.copy(alpha = 0f)), center, radius), radius, center)
    }
    val step = Offset(1.5.dp.toPx(), 1.1.dp.toPx())
    val corner = CornerRadius(4.dp.toPx())
    val pageSize = Size(width / 2f, height)
    for (layer in edgeLayers(spread.left, total) downTo 1)
        drawRoundRect(if (layer % 2 == 1) look.edge else look.edgeLine, Offset(-step.x * layer, step.y * layer), pageSize, corner)
    for (layer in edgeLayers(total - spread.right + 1, total) downTo 1)
        drawRoundRect(if (layer % 2 == 1) look.edge else look.edgeLine, Offset(width / 2f + step.x * layer, step.y * layer), pageSize, corner)
}

/**
 * A page turning around the spine. It shows its page on both faces: past upright, the back is drawn as a left-hand
 * page and mirrored, so it reads the right way round once it lies on the left.
 */
@Composable
private fun BoxScope.TurningLeaf(leaf: Leaf, source: PageSource, look: PaperLook) {
    val pastUpright by remember(leaf) { derivedStateOf { leaf.turned > 90f } }
    val showingBack = if (leaf.forward) pastUpright else !pastUpright
    // Pages still rising stack older over newer; pages that have fallen stack newer over older.
    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.5f)
        .zIndex(if (pastUpright) 1f + leaf.order else 1e6f - leaf.order)
        .graphicsLayer {
            transformOrigin = TransformOrigin(0f, .5f)
            rotationY = if (leaf.forward) -leaf.turned else leaf.turned - 180f
            cameraDistance = cameraAt(BookCamera * 2f, size.width)
        }) {
        val shade: DrawScope.() -> Unit = {
            val amount = sin(leaf.turned * PI / 180).toFloat()
            if (amount > 0f) drawRect(Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = .22f * amount), .55f to Color.White.copy(alpha = .06f * amount), 1f to Color.Black.copy(alpha = .08f * amount)))
        }
        if (showingBack) Box(Modifier.fillMaxSize().graphicsLayer { scaleX = -1f }) {
            // Drawn as a left-hand page, then mirrored: its shade must start at the spine, on the right before mirroring.
            BookPage(leaf.page, PageSide.Left, source, look, Modifier.fillMaxSize()) { scale(-1f, 1f) { shade() } }
        } else BookPage(leaf.page, PageSide.Right, source, look, Modifier.fillMaxSize(), shade)
    }
}

/**
 * One side of a page: the typeset preview of [page] (never a screenshot), or for page 1 the front cover on the right
 * and the inside cover on the left. There is nothing before page 1: the left side of a shut book is bare table.
 */
@Composable
private fun BookPage(page: Int, side: PageSide, source: PageSource, look: PaperLook, modifier: Modifier, overlay: (DrawScope.() -> Unit)? = null) {
    val face = pageFace(page, onLeft = side == PageSide.Left)
    // Bare table, but it keeps its half of the book, so a shut book's cover stays a page wide.
    if (face == PageFace.None) { Spacer(modifier); return }
    val shape = if (side == PageSide.Left) RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp) else RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
    val reading = page == source.reading
    val background = when (face) { PageFace.Cover -> look.cloth; else -> look.page }
    BoxWithConstraints(modifier.clip(shape).background(background).drawWithContent {
        drawContent()
        // The spine's shade, then the ribbon marking the page being read.
        val gutter = Color.Black.copy(alpha = .16f)
        drawRect(if (side == PageSide.Left) Brush.horizontalGradient(.78f to Color.Transparent, 1f to gutter) else Brush.horizontalGradient(0f to gutter, .22f to Color.Transparent))
        if (reading) {
            val em = size.width / 16f
            val right = size.width * .87f
            val ribbon = Path().apply {
                moveTo(right - em * 1.3f, 0f); lineTo(right, 0f); lineTo(right, em * 3.4f)
                lineTo(right - em * .65f, em * 3.4f * .76f); lineTo(right - em * 1.3f, em * 3.4f); close()
            }
            drawPath(ribbon, look.accent)
        }
        overlay?.invoke(this)
    }) {
        when (face) {
            PageFace.Cover -> FrontCover(source, maxWidth, maxHeight)
            PageFace.InsideCover -> InsideCover(look, maxWidth)
            else -> TextPage(page, source, look, maxWidth, maxHeight)
        }
    }
}

/** The front cover: the title's artwork cropped to the board, or cloth lettered with the title and author. */
@Composable
private fun FrontCover(source: PageSource, width: Dp, height: Dp) {
    source.cover?.let { art ->
        Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        return
    }
    val em = width / 16
    Box(Modifier.fillMaxSize().drawBehind {
        // A little light across the cloth and shadow toward the foot, and a blind-stamped rule inset from the edges.
        drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = .07f), .45f to Color.Transparent, 1f to Color.Black.copy(alpha = .14f)))
        val inset = size.width * .07f
        drawRoundRect(CoverInk.copy(alpha = .3f), Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2),
            CornerRadius(em.toPx() * .3f), style = Stroke(.8.dp.toPx()))
    }) {
        Column(Modifier.fillMaxSize().padding(horizontal = width * .14f, vertical = height * .15f), horizontalAlignment = Alignment.CenterHorizontally) {
            PageText(source.bookTitle, em * 1.35f, CoverInk, serif = true, weight = FontWeight.SemiBold, lineHeight = 1.12f, maxLines = 4)
            Box(Modifier.padding(top = em * .9f).size(width = em * 2f, height = .8.dp).background(CoverInk.copy(alpha = .6f)))
            Spacer(Modifier.weight(1f))
            if (source.author.isNotBlank()) PageText(source.author.uppercase(), em * .55f, CoverInk.copy(alpha = .85f), weight = FontWeight.SemiBold,
                spacing = .2f, maxLines = 2)
        }
    }
}

/** The inside of the front cover: an endpaper in the paper's colour with a faint repeating dot in its accent. */
@Composable
private fun InsideCover(look: PaperLook, width: Dp) = Canvas(Modifier.fillMaxSize()) {
    val pitch = (width / 22).toPx()
    val radius = pitch * .13f
    val dot = look.accent.copy(alpha = .1f)
    var row = 0
    var y = pitch / 2f
    while (y < size.height) {
        var x = if (row % 2 == 0) pitch / 2f else pitch
        while (x < size.width) { drawCircle(dot, radius, Offset(x, y)); x += pitch }
        y += pitch * .87f
        row++
    }
}

/** A text page: its chapter heading or running head, its first words, skeleton lines for the rest and its number. */
@Composable
private fun TextPage(page: Int, source: PageSource, look: PaperLook, maxWidth: Dp, maxHeight: Dp) {
    val em = maxWidth / 16
    val chapterIndex = chapterIndexAt(source.chapters, page)
    val chapter = source.chapters.getOrNull(chapterIndex)
    val excerpt = source.excerpts[page]
    Column(Modifier.fillMaxSize().padding(start = maxWidth * .11f, end = maxWidth * .11f, top = maxHeight * .10f, bottom = maxHeight * .07f),
        verticalArrangement = Arrangement.spacedBy(em * .55f)) {
        if (chapter != null && chapter.start == page) {
            chapter.label?.let { PageText(it, em * .62f, look.ink.copy(alpha = .6f), weight = FontWeight.SemiBold, spacing = .24f, top = em * 1.6f) }
            PageText(chapter.name, em * 1.2f, look.ink, serif = true, weight = FontWeight.SemiBold, lineHeight = 1.12f, maxLines = 3,
                top = if (chapter.label == null) em * 1.6f else 0.dp)
        } else {
            // Inset on both sides, so it stays centred and clear of the ribbon.
            PageText((chapter?.name ?: source.bookTitle).uppercase(), em * .56f, look.ink.copy(alpha = .5f), weight = FontWeight.SemiBold, spacing = .14f,
                maxLines = 1, inset = em * 1.8f)
        }
        if (!excerpt.isNullOrBlank()) PageText(excerpt, em * .8f, look.ink, serif = true, lineHeight = 1.35f, maxLines = 7, align = TextAlign.Start)
        SkeletonLines(page, em, look.ink, Modifier.weight(1f).fillMaxWidth())
        PageText(page.toString(), em * 1.15f, look.ink.copy(alpha = .75f), weight = FontWeight.Bold, maxLines = 1)
    }
}

/** Text at a fixed size on the page: previews are miniatures of the page, so they don't grow with the font scale. */
@Composable
private fun PageText(text: String, size: Dp, color: Color, serif: Boolean = false, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f,
    lineHeight: Float = 1.2f, maxLines: Int = 2, align: TextAlign = TextAlign.Center, top: Dp = 0.dp, inset: Dp = 0.dp) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Text(text, color = color, fontSize = fontSize, fontWeight = weight, fontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif,
        letterSpacing = spacing.em, lineHeight = lineHeight.em, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align,
        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"), modifier = Modifier.fillMaxWidth().padding(top = top, start = inset, end = inset))
}

/** Faint lines standing in for the rest of the page's text, as many as fit. */
@Composable
private fun SkeletonLines(page: Int, em: Dp, ink: Color, modifier: Modifier) = Canvas(modifier) {
    val line = em.toPx() * .3f
    val gap = em.toPx() * .66f
    var top = em.toPx() * .25f
    var index = 0
    while (top + line <= size.height) {
        drawRoundRect(ink.copy(alpha = .17f), Offset(0f, top), Size(size.width * skeletonLineWidth(page, index), line), CornerRadius(em.toPx() * .2f))
        top += line + gap
        index++
    }
}

private class ScrubFinger(val position: Offset, val fine: Boolean)

/**
 * The page edges under the book, as a scrubber: dragging along it jumps to the page under the finger, and sliding the
 * finger up off it switches to fine scrubbing. Chapter starts are ticked, and the ribbon marks the reading place.
 */
@Composable
private fun EdgeScrubber(book: FlipBookState, look: PaperLook, source: PageSource) {
    val colors = MaterialTheme.colorScheme
    val total = book.total
    val page = book.page
    var finger by remember { mutableStateOf<ScrubFinger?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(54.dp)
        .testTag("page-edge-scrubber")
        .semantics {
            contentDescription = "Page edges"
            stateDescription = "Page $page of $total"
            progressBarRangeInfo = ProgressBarRangeInfo(page.toFloat(), 1f..total.toFloat().coerceAtLeast(1f), steps = (total - 2).coerceAtLeast(0))
            setProgress("Change page") { value -> book.jumpTo(adjustedPage(book.page, value, total)) }
        }
        .pointerInput(book) {
            awaitEachGesture {
                val down = awaitFirstDown()
                // Consuming every change keeps the sheet from dragging as the finger rises into fine scrubbing.
                down.consume()
                if (book.busy) {
                    // Mid-riffle the edges wait, but still hold the gesture rather than let it drag the sheet.
                    do { val event = awaitPointerEvent(); event.changes.forEach { it.consume() } } while (event.changes.any { it.pressed })
                    return@awaitEachGesture
                }
                val width = size.width.toFloat()
                val stripTop = 16.dp.toPx()
                var fine = false
                var anchorX = down.position.x
                var anchorPage = book.page
                try {
                    book.jumpTo(scrubPage(down.position.x, width, total), flutter = true)
                    finger = ScrubFinger(down.position, false)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        val nowFine = isFineScrub((stripTop - change.position.y).toDp().value)
                        if (nowFine != fine) { fine = nowFine; anchorX = change.position.x; anchorPage = book.page }
                        book.jumpTo(if (fine) fineScrubPage(anchorPage, change.position.x - anchorX, width, total)
                            else scrubPage(change.position.x, width, total), flutter = true)
                        finger = ScrubFinger(change.position, fine)
                    }
                } finally {
                    finger = null
                    book.scrubEnded()
                }
            }
        }) {
        val stripWidth = constraints.maxWidth.toFloat()
        Canvas(Modifier.fillMaxSize()) {
            fun at(value: Int) = if (total <= 1) 0f else (value - 1f) / (total - 1) * size.width
            val stripTop = 16.dp.toPx()
            val stripHeight = 22.dp.toPx()
            val corner = CornerRadius(4.dp.toPx())
            drawRoundRect(look.edge, Offset(0f, stripTop), Size(size.width, stripHeight), corner)
            val pitch = 3.dp.toPx()
            val line = 1.dp.toPx()
            var x = 2.dp.toPx()
            while (x < size.width - line) { drawRect(look.edgeLine, Offset(x, stripTop), Size(line, stripHeight)); x += pitch }
            drawRoundRect(look.edgeLine, Offset(0f, stripTop), Size(size.width, stripHeight), corner, style = Stroke(line))
            for (chapter in source.chapters) drawRect(colors.onSurfaceVariant.copy(alpha = .55f),
                Offset(at(chapter.start) - .75.dp.toPx(), 12.dp.toPx()), Size(1.5.dp.toPx(), 30.dp.toPx()))
            // The reading place: a small ribbon over the edges.
            val here = at(source.reading)
            val ribbonWidth = 8.dp.toPx()
            val ribbonHeight = 9.dp.toPx()
            val top = 4.dp.toPx()
            drawPath(Path().apply {
                moveTo(here - ribbonWidth / 2, top); lineTo(here + ribbonWidth / 2, top); lineTo(here + ribbonWidth / 2, top + ribbonHeight)
                lineTo(here, top + ribbonHeight * .7f); lineTo(here - ribbonWidth / 2, top + ribbonHeight); close()
            }, look.accent)
            val knob = at(page)
            val ring = 3.dp.toPx()
            drawRoundRect(colors.surfaceContainerLow, Offset(knob - 2.dp.toPx() - ring, 10.dp.toPx() - ring), Size(4.dp.toPx() + ring * 2, 34.dp.toPx() + ring * 2), CornerRadius(2.dp.toPx() + ring))
            drawRoundRect(colors.primary, Offset(knob - 2.dp.toPx(), 10.dp.toPx()), Size(4.dp.toPx(), 34.dp.toPx()), CornerRadius(2.dp.toPx()))
        }
        finger?.let { touch ->
            val chapter = source.chapters.getOrNull(chapterIndexAt(source.chapters, page))
            val label = if (touch.fine) "fine" else if (page == 1) source.bookTitle else chapter?.name ?: source.bookTitle
            val density = LocalDensity.current
            // Above the finger, so the finger never covers it, and clamped inside the strip.
            val bottom = with(density) { min(0f, touch.position.y - 44.dp.toPx()) - 6.dp.toPx() }
            Text(buildAnnotatedString {
                append("p. $page ")
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = colors.surfaceContainerLow.copy(alpha = .75f))) { append("· $label") }
            }, color = colors.surfaceContainerLow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier
                    .clearAndSetSemantics { }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(Constraints(maxWidth = min(constraints.maxWidth, 260.dp.roundToPx())))
                        val x = (touch.position.x - placeable.width / 2f).coerceIn(0f, (stripWidth - placeable.width).coerceAtLeast(0f))
                        layout(placeable.width, placeable.height) { placeable.place(x.roundToInt(), (bottom - placeable.height).roundToInt()) }
                    }
                    .background(colors.onSurface, RoundedCornerShape(9.dp))
                    .padding(horizontal = 9.dp, vertical = 6.dp))
        }
    }
}

/** Typing a page, from the header: the quick way to a page number the reader already knows. */
@Composable
private fun PageNumberDialog(total: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    val target = entry.toIntOrNull()?.takeIf { it in 1..total }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Hide the keyboard while the field still holds it, through the dialog window's own controller (the one outside
    // the dialog belongs to the reader's window, which the keyboard isn't serving).
    val keyboard = remember { mutableStateOf<SoftwareKeyboardController?>(null) }
    fun go() = target?.let { keyboard.value?.hide(); onGo(it) }
    fun cancel() { keyboard.value?.hide(); onDismiss() }
    AlertDialog(onDismissRequest = ::cancel,
        title = { Text("Go to page") },
        text = {
            val dialogKeyboard = LocalSoftwareKeyboardController.current
            SideEffect { keyboard.value = dialogKeyboard }
            OutlinedTextField(entry, { value -> if (value.length <= 9 && value.all(Char::isDigit)) entry = value },
                label = { Text("Page number") }, placeholder = { Text("1–$total") }, singleLine = true,
                isError = entry.isNotEmpty() && target == null,
                supportingText = if (entry.isNotEmpty() && target == null) { { Text("Choose a page from 1 to $total") } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.focusRequester(focus))
        },
        confirmButton = { TextButton(onClick = { go() }, enabled = target != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Go") } },
        dismissButton = { TextButton(onClick = ::cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
}
