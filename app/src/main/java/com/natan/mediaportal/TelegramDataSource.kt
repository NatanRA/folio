package com.natan.mediaportal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Media3 requests byte ranges, including the MP4 index at the end of an M4B. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TelegramDataSource(private val resolve: suspend (Uri) -> TelegramFile, private val readRange: suspend (TelegramFile, Long, Int) -> ByteArray) : BaseDataSource(true) {
    constructor(telegram: TelegramLibrary) : this(telegram::resolve, telegram::readRange)
    private var uri: Uri? = null
    private var entry: TelegramFile? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    override fun getUri() = uri
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            val resolved = runBlocking { resolve(dataSpec.uri) }
            require(dataSpec.position in 0..resolved.size)
            entry = resolved; uri = dataSpec.uri; position = dataSpec.position
            remaining = (resolved.size - position).let { if (dataSpec.length == C.LENGTH_UNSET.toLong()) it else minOf(it, dataSpec.length) }
            opened = true; transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) { throw IOException("Couldn’t open Telegram audio. Check your connection and sign-in.", e) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        try {
            val bytes = runBlocking { readRange(entry ?: throw IOException("Stream closed"), position, minOf(length.toLong(), remaining, 256 * 1024L).toInt()) }
            if (bytes.isEmpty()) throw IOException("Telegram returned an empty audio range")
            bytes.copyInto(buffer, offset); position += bytes.size; remaining -= bytes.size
            bytesTransferred(bytes.size)
            return bytes.size
        } catch (e: Exception) { throw IOException("Audio buffering failed. Check your connection and retry.", e) }
    }
    override fun close() { entry = null; uri = null; if (opened) { opened = false; transferEnded() } }
}
