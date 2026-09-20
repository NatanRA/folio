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
    val position = positionMs.coerceIn(0, bookDuration)
    val index = (0 until mediaItemCount).lastOrNull { getMediaItemAt(it).chapterStartMs <= position } ?: 0
    if (mediaItemCount > 0) seekTo(index, position - getMediaItemAt(index).chapterStartMs)
}
