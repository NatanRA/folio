package app.folio.library

import kotlin.math.abs
import kotlin.math.pow

/**
 * Where playback is, as a line the progress bar can draw from on every frame: [position] milliseconds into the book at
 * [at] (a monotonic clock in milliseconds), moving on at [rate] milliseconds of audio per millisecond. The player
 * reports its position only now and then, so the bar extrapolates between reports instead of stepping.
 */
internal data class PlaybackClock(val position: Double = 0.0, val at: Long = 0, val rate: Double = 0.0) {
    fun positionAt(now: Long): Double = position + (now - at).coerceAtLeast(0) * rate
}

/** A jump in playback: where the bar was drawn when it started, where it is going, and when. */
internal data class PlaybackSeek(val from: Double, val to: Long, val at: Long)

/**
 * How the audiobook progress bar moves, as a value each event turns into the next one, so the rules can be checked
 * without a player or a screen. Times are milliseconds on one monotonic clock; rates are the playback speed while
 * audio is actually playing and 0 otherwise. The iOS player follows the same rules with the same numbers.
 */
internal data class PlaybackProgress(
    val clock: PlaybackClock = PlaybackClock(),
    /** A jump the player hasn't confirmed yet. Reports far from it are ignored until one lands near it or [HOLD_MS] passes. */
    val pending: PlaybackSeek? = null,
    /** The latest jump, which the bar eases into for [EASE_MS] instead of snapping. */
    val easing: PlaybackSeek? = null,
) {
    /** Where the bar is drawn at [now]: the running clock, eased in from where the bar was just after a jump. */
    fun shownAt(now: Long): Double {
        val live = clock.positionAt(now)
        val seek = easing ?: return live
        val progress = (now - seek.at).toDouble() / EASE_MS
        if (progress >= 1) return live
        return seek.from + (live - seek.from) * (1 - (1 - progress.coerceAtLeast(0.0)).pow(3))
    }

    /** Whether the bar moves by itself at [now], so it needs to be drawn again on the next frame. */
    fun movingAt(now: Long): Boolean = clock.rate > 0 || easing != null && now - easing.at < EASE_MS

    /**
     * Where a skip counts from: the jump still on its way, or else where the player [reported] it is. So three quick
     * taps of +30 s move 90 s even if the player hasn't caught up with the first one yet.
     */
    fun skipBase(reported: Long, now: Long): Long = pending?.takeIf { now - it.at < HOLD_MS }?.to ?: reported

    /**
     * Starts a jump to [target]. The bar eases there from where it is drawn, unless [eased] is false (a drag already
     * put it there) or the jump lands in another of [chapters]: the bar's scale changes with the chapter, so it lands
     * there directly rather than sweeping across a scale it no longer shows.
     */
    fun seek(target: Long, rate: Double, now: Long, eased: Boolean = true, chapters: List<AudioChapter> = emptyList()): PlaybackProgress {
        val shown = shownAt(now)
        val sameChapter = chapterIndexAt(chapters, shown) == chapterIndexAt(chapters, target.toDouble())
        val jump = PlaybackSeek(from = if (eased && sameChapter) shown else target.toDouble(), to = target, at = now)
        return PlaybackProgress(PlaybackClock(target.toDouble(), now, rate), pending = jump, easing = jump)
    }

    /**
     * Takes a position the player [reported] at [now]. Until a pending jump lands, reports from before it are ignored
     * and the bar carries on from the jump. While playing, a small difference (like the moment audio takes to restart
     * after a jump) is absorbed by drawing on from where the bar is at a speed that meets playback [CATCH_UP_MS]
     * later, never below zero, so the bar neither steps nor runs backwards. A bigger difference (the lock screen or
     * another device moved playback) moves straight there, and a paused bar always shows exactly where playback is.
     */
    fun report(reported: Long, rate: Double, now: Long): PlaybackProgress {
        val jump = pending
        if (jump != null && abs(reported - jump.to) > ARRIVED_MS && now - jump.at < HOLD_MS)
            return copy(clock = PlaybackClock(clock.positionAt(now), now, rate))
        val shown = clock.positionAt(now)
        val gap = reported - shown
        val next = if (rate <= 0 || abs(gap) >= CONVERGE_MS) PlaybackClock(reported.toDouble(), now, rate)
            else PlaybackClock(shown, now, (rate + gap / CATCH_UP_MS).coerceAtLeast(0.0))
        return copy(clock = next, pending = null)
    }

    companion object {
        /** How long the bar eases into a jump. */
        const val EASE_MS = 180L
        /** How long a jump holds off reports far from it, at most. */
        const val HOLD_MS = 1500L
        /** How near a report must be to a jump for the jump to count as landed. */
        const val ARRIVED_MS = 1000L
        /** Differences smaller than this are smoothed out while playing; bigger ones are jumped. */
        const val CONVERGE_MS = 1500L
        /** How long the bar takes to meet playback after a small difference. */
        const val CATCH_UP_MS = 1000.0
    }
}

/** The chapter [position] falls in; the first one for a book without chapters or a position before them all. */
internal fun chapterIndexAt(chapters: List<AudioChapter>, position: Double): Int =
    chapters.indexOfLast { it.startMs <= position }.coerceAtLeast(0)

/**
 * The elapsed time the labels under the bar show, in whole seconds so they change once a second, both together.
 * [position] is milliseconds into the book, [start] and [length] the chapter's.
 */
internal fun elapsedLabelMs(position: Double, start: Long, length: Long): Long =
    (position - start).coerceIn(0.0, length.coerceAtLeast(0).toDouble()).toLong() / 1000 * 1000
