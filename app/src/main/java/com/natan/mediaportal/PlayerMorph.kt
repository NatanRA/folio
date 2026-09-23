package com.natan.mediaportal

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * The mini player and the full audiobook player are one card. A single expansion value, 0 for the mini player and 1
 * for the full player, drives every part of the morph: the card's bounds and corners, the cover's flight, and which
 * content shows. The value is animated with one spring that any tap, Back or drag retargets mid-flight, keeping its
 * velocity, so nothing ever waits for an animation to finish. The numbers are shared with the iOS player.
 */

/** The morph's spring: a little under critical damping, answering in about 0.42 s. */
internal const val MorphDamping = .86f
internal const val MorphStiffness = 224f
/** A release flung downwards faster than this (dp/s) collapses the card; flung upwards as fast, it opens. */
internal const val MorphFlingSpeed = 800f
/** Released slower than a fling, the card collapses once dragged more than this share of the way down. */
internal const val MorphDismissShare = 1f / 3f

private val MorphSpring = spring<Float>(dampingRatio = MorphDamping, stiffness = MorphStiffness)

/** [value]'s progress from [from] to [to], held to 0..1: the parts of the morph that happen in a window of it. */
internal fun ramp(value: Float, from: Float, to: Float): Float = if (to <= from) (if (value >= to) 1f else 0f) else ((value - from) / (to - from)).coerceIn(0f, 1f)

/** The rectangle [fraction] of the way from [start] to [stop]. */
internal fun lerpRect(start: Rect, stop: Rect, fraction: Float) = Rect(
    start.left + (stop.left - start.left) * fraction, start.top + (stop.top - start.top) * fraction,
    start.right + (stop.right - start.right) * fraction, start.bottom + (stop.bottom - start.bottom) * fraction)

/** The expansion a drag of [dragDown] px sets, from [start], when the card's top travels [travel] px from open to closed: 1:1 with the finger. */
internal fun draggedExpansion(start: Float, dragDown: Float, travel: Float): Float =
    if (travel <= 0f) start else (start - dragDown / travel).coerceIn(0f, 1f)

/** The finger's velocity, [downward] px/s, in expansion per second over a [travel] px journey. */
internal fun expansionVelocity(downward: Float, travel: Float): Float = if (travel <= 0f) 0f else -downward / travel

/** Whether a card released at [expansion], moving [downward] dp/s, springs open (or else collapses). */
internal fun settlesOpen(expansion: Float, downward: Float): Boolean = when {
    downward > MorphFlingSpeed -> false
    downward < -MorphFlingSpeed -> true
    else -> expansion >= 1f - MorphDismissShare
}

/**
 * The player card's motion: one [expansion] spring, and while a finger holds the card, the finger's own value over it.
 * The card collapses toward the mini player's capsule when one is on screen, or down into the bottom edge otherwise.
 */
@Stable
internal class PlayerMorph(private val scope: CoroutineScope) {
    private val expansion = Animatable(0f)
    private var held by mutableStateOf<Float?>(null)
    /** How open the card is: 0 is the mini player (or nothing), 1 the full player. Read it in layout and draw. */
    val progress: Float get() = held ?: expansion.value
    /** Whether anything of the card shows or moves; false only when it rests shut. */
    val active by derivedStateOf { held != null || expansion.value > 0f || expansion.targetValue > 0f }
    /** Whether the card rests fully open, hiding everything under it. */
    val covering by derivedStateOf { held == null && !expansion.isRunning && expansion.value >= 1f }

    /** Where the mini player's capsule and cover are, in the root's coordinates, while it is on screen. */
    var miniCapsule by mutableStateOf(Rect.Zero)
    var miniCover by mutableStateOf(Rect.Zero)
    var miniPlaced = false
    /** Where the full player's cover lies when the card is open. */
    var bigCover by mutableStateOf(Rect.Zero)
    /** The card's bounds when open: the whole overlay, which sits at the root's origin. Set as the card is laid out. */
    var full = Rect.Zero
        private set
    /** Whether the card morphs to and from the mini player; chosen as it leaves rest, and kept through a flight. */
    var toMini by mutableStateOf(false)
        private set

    private val atRest get() = held == null && !expansion.isRunning

    /** Where the card is shut: the mini player's capsule, or a sliver along the bottom edge. */
    val collapsed: Rect get() = if (toMini && miniCapsule != Rect.Zero) miniCapsule else Rect(full.left, full.bottom, full.right, full.bottom)
    /** How far the card's top travels between shut and open. */
    val travel: Float get() = collapsed.top - full.top
    /** The card's bounds now, inside an overlay [width] by [height] px. */
    fun cardRect(width: Int, height: Int): Rect {
        full = Rect(0f, 0f, width.toFloat(), height.toFloat())
        return lerpRect(collapsed, full, progress.coerceIn(0f, 1f))
    }

    /** Heads for open or shut from wherever the card is, keeping its velocity. Taps, Back and the close button end here. */
    fun retarget(open: Boolean) {
        val target = if (open) 1f else 0f
        if (held == null && expansion.targetValue == target) return
        if (atRest) toMini = miniPlaced
        // Called while the last spring still runs, so animateTo starts from its velocity.
        scope.launch { expansion.animateTo(target, MorphSpring) }
    }

    /** A finger takes the card, catching it wherever it is. */
    fun grab() {
        if (atRest) toMini = miniPlaced
        held = progress
        scope.launch { expansion.stop() }
    }

    fun dragBy(down: Float) { held?.let { held = draggedExpansion(it, down, travel) } }

    /** The finger lets go at [downward] px/s ([density] px per dp): spring open or shut from the finger's velocity. Returns whether it opens. */
    fun release(downward: Float, density: Float): Boolean {
        val at = held ?: return expansion.targetValue == 1f
        val open = settlesOpen(at, downward / density)
        val velocity = expansionVelocity(downward, travel)
        scope.launch {
            expansion.snapTo(at)
            held = null
            expansion.animateTo(if (open) 1f else 0f, MorphSpring, velocity)
        }
        return open
    }
}

@Composable
internal fun rememberPlayerMorph(): PlayerMorph {
    val scope = rememberCoroutineScope()
    return remember { PlayerMorph(scope) }
}

/**
 * The mini player's part of the morph: fading out in the first moment of the card growing out of it, as the card
 * fades in over it, so its glass stops drawing early (a blur is costly to redraw every frame). Under the open card it
 * stays composed, ready to be shrunk into, but leaves the semantics tree, so TalkBack and tests see only the player.
 */
internal fun Modifier.miniPlayerMorph(morph: PlayerMorph) = (if (morph.covering) clearAndSetSemantics { } else this).graphicsLayer {
    val t = morph.progress
    alpha = 1f - ramp(t, 0f, .12f)
    // Drawn once into a layer while it fades, rather than redrawing its glass at every step.
    compositingStrategy = if (t > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
}

internal fun Modifier.miniCapsuleBounds(morph: PlayerMorph) = onGloballyPositioned { morph.miniCapsule = it.boundsInRoot() }

/** The mini player's cover, hidden while [flying] carries it to and from the full player. */
internal fun Modifier.miniCoverMorph(morph: PlayerMorph, flying: Boolean) =
    onGloballyPositioned { morph.miniCover = it.boundsInRoot() }
        .graphicsLayer { alpha = if (flying && morph.progress > 0f) 0f else 1f }

/**
 * The player card: laid out at its current bounds, so it takes touches only where it is, with the full player's
 * content laid out once at full size inside it, scaled to the card's width and faded in. [artwork], when the card
 * morphs from the mini player, flies between the two covers. While [closing], a tap anywhere on the card reopens it,
 * and a drag anywhere catches it; [onRelease] hears whether a drag left it open. [content] gets the full player's
 * cover modifier.
 */
@Composable
internal fun PlayerCard(morph: PlayerMorph, background: Color, capsule: Color, artwork: ImageBitmap?, closing: Boolean,
    onReopen: () -> Unit, onRelease: (open: Boolean) -> Unit, content: @Composable (coverModifier: Modifier) -> Unit) {
    val density = LocalDensity.current
    val view = LocalView.current
    // The display's own corner, so the open card lines up with the screen's edge.
    val screenCorner = remember(view) {
        if (Build.VERSION.SDK_INT >= 31) view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f else 0f
    }
    val capsuleCorner = with(density) { 28.dp.toPx() }
    val flying = artwork != null && morph.toMini
    val inner = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // A scrim deepening as the card opens gives it an edge against a page of the same colour. It draws only while
    // the card moves, and takes no touches.
    Box(Modifier.fillMaxSize().drawBehind { val t = morph.progress; if (t > 0f && t < 1f) drawRect(Color.Black.copy(alpha = .32f * t)) }) {
        Box(Modifier
            .layout { measurable, constraints ->
                val card = morph.cardRect(constraints.maxWidth, constraints.maxHeight)
                val placeable = measurable.measure(Constraints.fixed(card.width.roundToInt().coerceAtLeast(0), card.height.roundToInt().coerceAtLeast(0)))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(card.left.roundToInt(), card.top.roundToInt()) }
            }
            .graphicsLayer {
                val t = morph.progress
                val corner = if (morph.toMini) capsuleCorner + (screenCorner - capsuleCorner) * t.coerceIn(0f, 1f) else screenCorner
                shape = RoundedCornerShape(corner)
                clip = true
            }
            // Fading in over the mini player through its fill, not a layer alpha, which would need an offscreen buffer.
            .drawBehind {
                val t = morph.progress
                drawRect(if (morph.toMini) lerp(capsule, background, ramp(t, 0f, .6f)).copy(alpha = ramp(t, 0f, .12f)) else background)
            }
            .draggable(rememberDraggableState { delta -> morph.dragBy(delta) }, Orientation.Vertical,
                onDragStarted = { morph.grab() },
                onDragStopped = { velocity -> onRelease(morph.release(velocity, density.density)) })
            .testTag("audio-player")) {
            // The full player's content keeps its full-screen layout; only its layer follows the card.
            Box(Modifier
                .layout { measurable, constraints ->
                    val size = morph.full
                    val placeable = measurable.measure(Constraints.fixed(size.width.roundToInt(), size.height.roundToInt()))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.placeWithLayer(0, 0) {
                            val t = morph.progress
                            val scale = if (size.width > 0f) constraints.maxWidth / size.width else 1f
                            scaleX = scale; scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                            alpha = if (morph.toMini) ramp(t, .1f, .5f) else ramp(t, 0f, .3f)
                            // While the card moves, the player is drawn once into a layer and then only composited as
                            // its scale and alpha change; at rest it draws directly, without holding a screen-sized layer.
                            compositingStrategy = if (t < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                        }
                    }
                }
                .onPlaced { inner[0] = it }) {
                content(Modifier
                    .onGloballyPositioned { cover -> inner[0]?.takeIf { it.isAttached }?.let { morph.bigCover = it.localBoundingBoxOf(cover, clipBounds = false) } }
                    .graphicsLayer { alpha = if (flying && morph.progress < .999f) 0f else 1f })
            }
            // Shrinking, the whole card is one target: a tap brings it back, whatever lies under the finger.
            if (closing) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { onReopen() } })
        }
        // The cover flies between the mini player's thumbnail and the full player's cover.
        if (flying) Image(artwork, null, contentScale = ContentScale.Crop, modifier = Modifier.layout { measurable, constraints ->
            val t = morph.progress.coerceIn(0f, 1f)
            val cover = lerpRect(morph.miniCover, morph.bigCover, t)
            val placeable = measurable.measure(Constraints.fixed(cover.width.roundToInt().coerceAtLeast(0), cover.height.roundToInt().coerceAtLeast(0)))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.placeWithLayer(cover.left.roundToInt(), cover.top.roundToInt()) {
                    val shown = morph.progress
                    alpha = if (shown > 0f && shown < .999f) 1f else 0f
                    shape = RoundedCornerShape(with(density) { (8.dp + (14.dp - 8.dp) * t).toPx() })
                    clip = true
                    // The full cover's shadow builds up only as it lands; a moving shadow is costly to redraw.
                    shadowElevation = with(density) { (16.dp * ramp(t, .7f, 1f)).toPx() }
                }
            }
        })
    }
}
