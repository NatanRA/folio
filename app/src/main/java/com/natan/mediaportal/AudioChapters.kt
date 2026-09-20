package com.natan.mediaportal

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.io.File
import java.io.RandomAccessFile

data class AudioChapter(val name: String, val startMs: Long) {
    fun displayName(index: Int): String = name.trim().takeUnless { it.isEmpty() || it.all(Char::isDigit) } ?: "Chapter ${index + 1}"
}

/** A small magnetic zone around chapter starts; the rest of the timeline remains freely seekable. */
fun snapAudioChapter(positionMs: Long, durationMs: Long, chapters: List<AudioChapter>): AudioChapter? {
    if (durationMs <= 0) return null
    return chapters.filter { it.startMs in 0 until durationMs }
        .minByOrNull { kotlin.math.abs(it.startMs - positionMs) }
        ?.takeIf { kotlin.math.abs(it.startMs - positionMs) <= durationMs * 0.015 }
}

/** Reads embedded chapters without loading the audio into memory. */
@androidx.annotation.OptIn(UnstableApi::class)
object AudioChapters {
    class RangeInput(private val size: Long, private val read: (Long, Int) -> ByteArray) {
        var filePointer = 0L
        fun length() = size
        fun seek(offset: Long) { require(offset in 0..size); filePointer = offset }
        fun skipBytes(count: Int) { seek(filePointer + count) }
        fun readFully(bytes: ByteArray) {
            var done = 0
            while (done < bytes.size) {
                val part = read(filePointer, bytes.size - done)
                require(part.isNotEmpty() && part.size <= bytes.size - done)
                part.copyInto(bytes, done); done += part.size; filePointer += part.size
            }
        }
        fun readUnsignedByte() = ByteArray(1).also { readFully(it) }[0].toInt() and 255
        fun readInt() = java.nio.ByteBuffer.wrap(ByteArray(4).also { readFully(it) }).int
        fun readLong() = java.nio.ByteBuffer.wrap(ByteArray(8).also { readFully(it) }).long
    }
    fun read(input: RangeInput): List<AudioChapter> {
        if (input.length() < 10) return emptyList()
        val magic = ByteArray(3).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        return (if (magic == "ID3") id3(input) else mp4(input, 0, input.length(), 0)
            .ifEmpty { QuickTimeChapters.read(input) })
            .filter { it.startMs >= 0 }.distinctBy { it.startMs }.sortedBy { it.startMs }
    }
    fun read(file: File): List<AudioChapter> = runCatching {
        if (file.name.endsWith(".audiobook.json")) return@runCatching AudioBundle.read(file).map { AudioChapter(it.name, it.startMs) }
        RandomAccessFile(file, "r").use { input ->
            read(RangeInput(input.length()) { offset, count -> input.seek(offset); ByteArray(count).also { input.readFully(it) } })
        }
    }.getOrDefault(emptyList())

    private fun id3(input: RangeInput): List<AudioChapter> {
        input.seek(6)
        var length = 0
        repeat(4) { val b = input.readUnsignedByte(); require(b < 128); length = (length shl 7) or b }
        require(length <= 16 * 1024 * 1024 && length + 10L <= input.length())
        input.seek(0)
        val bytes = ByteArray(length + 10).also { input.readFully(it) }
        val metadata = Id3Decoder().decode(bytes, bytes.size) ?: return emptyList()
        return (0 until metadata.length()).mapNotNull { index ->
            val chapter = metadata[index] as? ChapterFrame ?: return@mapNotNull null
            val name = (0 until chapter.subFrameCount).mapNotNull { chapter.getSubFrame(it) as? TextInformationFrame }
                .firstOrNull { it.id == "TIT2" }?.values?.firstOrNull()
            AudioChapter(name?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}", chapter.startTimeMs.toLong())
        }
    }

    private fun mp4(input: RangeInput, start: Long, end: Long, depth: Int): List<AudioChapter> {
        require(depth <= 5)
        var offset = start
        var atoms = 0
        while (end - offset >= 8) {
            require(++atoms <= 10000)
            input.seek(offset)
            var size = input.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            var header = 8L
            if (size == 1L) { size = input.readLong(); header = 16 }
            if (size == 0L) size = end - offset
            require(size >= header && size <= end - offset)
            val body = offset + header
            if (type == "chpl") {
                require(size - header >= 5 && size <= 1024 * 1024)
                val version = input.readUnsignedByte(); input.skipBytes(3)
                if (version == 1) input.skipBytes(4) else require(version == 0)
                val count = input.readUnsignedByte()
                return (0 until count).map { index ->
                    require(offset + size - input.filePointer >= 9)
                    val ticks = input.readLong(); val length = input.readUnsignedByte()
                    require(ticks >= 0 && length <= offset + size - input.filePointer)
                    val name = ByteArray(length).also { input.readFully(it) }.toString(Charsets.UTF_8)
                    AudioChapter(name.ifBlank { "Chapter ${index + 1}" }, ticks / 10000)
                }
            }
            if (type == "moov" || type == "udta") {
                val chapters = mp4(input, body, offset + size, depth + 1)
                if (chapters.isNotEmpty()) return chapters
            }
            offset += size
        }
        return emptyList()
    }
}
