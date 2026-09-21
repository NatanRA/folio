package com.natan.mediaportal

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal val MediaItem.chapterStartMs: Long
    get() = mediaMetadata.extras?.getLong("chapterStartMs", 0) ?: 0

internal val Player.bookPosition: Long
    get() = (currentMediaItem?.chapterStartMs ?: 0) + currentPosition.coerceAtLeast(0)

internal val Player.bookDuration: Long
    get() = currentMediaItem?.mediaMetadata?.extras?.getLong("bookDurationMs", 0)
        ?.takeIf { it > 0 } ?: duration.coerceAtLeast(0)

internal fun Player.seekBook(positionMs: Long) {
    // A book whose length is not known yet would coerce every position into 0..0 and send a
    // listener who asked for thirty seconds forward back to the first word instead.
    val total = bookDuration
    if (total <= 0 || mediaItemCount == 0) return
    val position = positionMs.coerceIn(0, total)
    val index = (0 until mediaItemCount).lastOrNull { getMediaItemAt(it).chapterStartMs <= position } ?: 0
    seekTo(index, position - getMediaItemAt(index).chapterStartMs)
}
