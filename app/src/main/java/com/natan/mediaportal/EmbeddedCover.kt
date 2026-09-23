package com.natan.mediaportal

/**
 * Finds the cover picture embedded in an audiobook by reading only the parts of the file that can hold it, so a
 * streamed book's cover costs a few small reads instead of the file's whole index.
 *
 * MP4 audio (M4B, M4A) keeps its cover in `moov/udta/meta/ilst/covr`. Long audiobooks usually put `moov` after the
 * audio and fill it with sample tables for many hours, which a general-purpose metadata reader downloads before it
 * reaches the cover; it ran out of budget, so those covers only appeared once the book was played. MP3 keeps its cover
 * in an ID3v2 `APIC` frame at the start of the file. The iOS reader follows the same steps.
 */
internal object EmbeddedCover {
    /** The largest metadata box or tag read in one go. */
    const val LIMIT = 16 * 1024 * 1024
    /** Boxes or frames walked at most at one level, so a damaged file can't keep the reader going. */
    private const val MAX_ITEMS = 10_000

    sealed interface Search
    /** The cover's encoded image bytes. */
    class Found(val image: ByteArray) : Search
    /** An MP4 or ID3-tagged file read through without finding a picture. */
    data object Missing : Search
    /** Another format, or a file too damaged or cut short to search: another reader may still find something. */
    data object Unreadable : Search

    /**
     * Searches a file of [size] bytes. [read] returns up to `length` bytes at `offset`, fewer only at the end of the
     * file; an exception it throws (a failed download, say) is passed on, so the caller can try again later.
     */
    suspend fun find(size: Long, read: suspend (offset: Long, length: Int) -> ByteArray): Search {
        if (size < 16) return Unreadable
        val reader = Reader(size, read)
        val head = reader.bytes(0, 16) ?: return Unreadable
        return when {
            head.ascii(0, 3) == "ID3" -> id3(reader)
            head.ascii(4, 4) in topLevel -> mp4(reader)
            else -> Unreadable
        }
    }

    private class Reader(val size: Long, val read: suspend (Long, Int) -> ByteArray) {
        /** Exactly [length] bytes at [offset], or null when the file ends first. */
        suspend fun bytes(offset: Long, length: Int): ByteArray? {
            if (offset < 0 || length < 0 || offset + length > size) return null
            val result = ByteArray(length)
            var done = 0
            while (done < length) {
                val part = read(offset + done, length - done)
                if (part.isEmpty()) return null
                val count = minOf(part.size, length - done)
                part.copyInto(result, done, 0, count); done += count
            }
            return result
        }
    }

    // MP4

    /** Box types that open an MP4 or QuickTime file. */
    private val topLevel = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pnot")

    private class Box(val type: String, val start: Long, val header: Int, val length: Long) { val end get() = start + length }

    /** The box whose header starts at [offset], within [end]; null when it is damaged or runs past [end]. */
    private suspend fun box(reader: Reader, offset: Long, end: Long): Box? {
        val header = reader.bytes(offset, minOf(16L, end - offset).toInt()) ?: return null
        if (header.size < 8) return null
        var length = header.u32(0); var size = 8
        if (length == 1L) { if (header.size < 16) return null; length = header.s64(8); size = 16 }
        else if (length == 0L) length = end - offset
        if (length < size || length > end - offset) return null
        return Box(header.ascii(4, 4), offset, size, length)
    }

    private suspend fun mp4(reader: Reader): Search {
        // At the top level every box but `moov` is skipped by its size, so the audio in `mdat` is never read.
        var offset = 0L
        repeat(MAX_ITEMS) {
            if (offset + 8 > reader.size) return Unreadable
            val top = box(reader, offset, reader.size) ?: return Unreadable
            if (top.type == "moov") {
                // Inside `moov` the tracks are skipped the same way; only a metadata box is read whole.
                var child = top.start + top.header
                var oversized = false
                repeat(MAX_ITEMS) {
                    if (child + 8 > top.end) return if (oversized) Unreadable else Missing
                    val inner = box(reader, child, top.end) ?: return Unreadable
                    if (inner.type == "udta" || inner.type == "meta") {
                        if (inner.length > LIMIT) oversized = true
                        else mp4Cover(reader.bytes(inner.start, inner.length.toInt()) ?: return Unreadable)?.let { return Found(it) }
                    }
                    child = inner.end
                }
                return Unreadable
            }
            offset = top.end
        }
        return Unreadable
    }

    /** The cover in a whole `udta` or `meta` box held in [bytes]. */
    internal fun mp4Cover(bytes: ByteArray): ByteArray? {
        val root = children(bytes, 0, bytes.size).firstOrNull() ?: return null
        val metas = if (root.type == "udta") children(bytes, root.body, root.end).filter { it.type == "meta" } else listOf(root).filter { it.type == "meta" }
        for (meta in metas) {
            // `meta` is a full box, with four bytes of version and flags before its children, but QuickTime writes it
            // without them: then a child's size and `hdlr` come straight away.
            val quickTime = meta.body + 8 <= meta.end && bytes.ascii(meta.body + 4, 4) == "hdlr"
            val starts = if (quickTime) listOf(meta.body, meta.body + 4) else listOf(meta.body + 4, meta.body)
            val list = starts.firstNotNullOfOrNull { start -> children(bytes, start, meta.end).firstOrNull { it.type == "ilst" } } ?: continue
            val cover = children(bytes, list.body, list.end).firstOrNull { it.type == "covr" } ?: continue
            // `data`: a type (13 JPEG, 14 PNG, 27 BMP), a locale, then the image.
            val data = children(bytes, cover.body, cover.end).firstOrNull { it.type == "data" } ?: continue
            if (data.end - data.body > 8) return bytes.copyOfRange(data.body + 8, data.end)
        }
        return null
    }

    private class Child(val type: String, val body: Int, val end: Int)

    /** The boxes laid end to end in [bytes] from [start] to [end], stopping at the first damaged one. */
    private fun children(bytes: ByteArray, start: Int, end: Int): List<Child> {
        val result = mutableListOf<Child>()
        var offset = start
        while (offset + 8 <= end && result.size < MAX_ITEMS) {
            var length = bytes.u32(offset); var header = 8
            if (length == 1L) { if (offset + 16 > end) break; length = bytes.s64(offset + 8); header = 16 }
            else if (length == 0L) length = (end - offset).toLong()
            if (length < header || length > end - offset) break
            result += Child(bytes.ascii(offset + 4, 4), offset + header, offset + length.toInt())
            offset += length.toInt()
        }
        return result
    }

    // ID3v2

    private suspend fun id3(reader: Reader): Search {
        val header = reader.bytes(0, 10) ?: return Unreadable
        val version = header[3].toInt()
        val flags = header[5].toInt() and 0xFF
        if (version !in 2..4) return Unreadable
        val size = syncSafe(header, 6) ?: return Unreadable
        if (size > LIMIT || 10L + size > reader.size) return Unreadable
        var tag = reader.bytes(10, size) ?: return Unreadable
        // In ID3v2.2 this flag means a compression scheme that was never defined.
        if (version == 2 && flags and 0x40 != 0) return Unreadable
        // Unsynchronisation puts a 0 after every 0xFF; v2.4 applies it frame by frame instead.
        if (flags and 0x80 != 0 && version < 4) tag = resynchronise(tag, 0, tag.size)
        var start = 0
        if (flags and 0x40 != 0) {
            if (tag.size < 4) return Unreadable
            // The extended header's size leaves out its own four bytes in v2.3 and includes them in v2.4.
            start = if (version == 3) 4 + tag.u32(0).toInt() else syncSafe(tag, 0) ?: return Unreadable
            if (start < 0 || start > tag.size) return Unreadable
        }
        val pictures = frames(tag, start, version, flags and 0x80 != 0, syncSafeSizes = true)
            ?: if (version == 4) frames(tag, start, version, flags and 0x80 != 0, syncSafeSizes = false) else null
        if (pictures == null) return Unreadable
        // The front cover (picture type 3) if the tag names one, else the first picture.
        val picture = pictures.firstOrNull { it.first == 3 } ?: pictures.firstOrNull() ?: return Missing
        return Found(picture.second)
    }

    /**
     * The pictures in the frames from [start], as picture type and image bytes; null when the frames don't line up
     * before any picture is found. Read with syncsafe sizes, a v2.4 tag that doesn't line up at all gives null, since
     * that usually means a writer stored plain sizes where syncsafe ones belong, and a picture read so would be cut.
     */
    private fun frames(tag: ByteArray, start: Int, version: Int, unsynchronised: Boolean, syncSafeSizes: Boolean): List<Pair<Int, ByteArray>>? {
        val pictures = mutableListOf<Pair<Int, ByteArray>>()
        val idLength = if (version == 2) 3 else 4
        val headerLength = if (version == 2) 6 else 10
        var offset = start
        repeat(MAX_ITEMS) {
            if (offset + headerLength > tag.size || tag[offset] == 0.toByte()) return pictures
            val id = tag.ascii(offset, idLength)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) return if (version == 4 && syncSafeSizes) null else pictures.ifEmpty { null }
            val size = when {
                version == 2 -> ((tag[offset + 3].toInt() and 0xFF) shl 16) or ((tag[offset + 4].toInt() and 0xFF) shl 8) or (tag[offset + 5].toInt() and 0xFF)
                version == 4 && syncSafeSizes -> syncSafe(tag, offset + 4) ?: return null
                else -> tag.u32(offset + 4).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
            }
            val body = offset + headerLength
            if (size < 0 || size > tag.size - body) return null
            val formatFlags = if (version == 2) 0 else tag[offset + 9].toInt() and 0xFF
            if (id == "APIC" || id == "PIC") frameBody(tag, body, body + size, version, formatFlags, unsynchronised)?.let { data ->
                picture(data, version == 2)?.let { pictures += it }
            }
            offset = body + size
        }
        return pictures
    }

    /** A frame's own data, with the extra bytes its flags announce removed; null for compressed or encrypted frames. */
    private fun frameBody(tag: ByteArray, start: Int, end: Int, version: Int, flags: Int, unsynchronised: Boolean): ByteArray? {
        var from = start
        when (version) {
            3 -> {
                if (flags and 0x80 != 0 || flags and 0x40 != 0) return null
                if (flags and 0x20 != 0) from += 1
            }
            4 -> {
                if (flags and 0x08 != 0 || flags and 0x04 != 0) return null
                if (flags and 0x40 != 0) from += 1
                if (flags and 0x01 != 0) from += 4
            }
        }
        if (from > end) return null
        return if (version == 4 && (unsynchronised || flags and 0x02 != 0)) resynchronise(tag, from, end) else tag.copyOfRange(from, end)
    }

    /** An APIC (or v2.2 PIC) frame's picture type and image. */
    private fun picture(data: ByteArray, short: Boolean): Pair<Int, ByteArray>? {
        if (data.isEmpty()) return null
        val encoding = data[0].toInt()
        var offset = 1
        // v2.2 names a three-letter format; later versions a MIME type ending in a zero byte.
        if (short) offset += 3 else { while (offset < data.size && data[offset] != 0.toByte()) offset++; offset++ }
        if (offset >= data.size) return null
        val type = data[offset].toInt() and 0xFF
        offset++
        // The description ends in one zero byte, or two on an even step for the UTF-16 encodings.
        if (encoding == 1 || encoding == 2) {
            while (offset + 1 < data.size && !(data[offset] == 0.toByte() && data[offset + 1] == 0.toByte())) offset += 2
            offset += 2
        } else {
            while (offset < data.size && data[offset] != 0.toByte()) offset++
            offset++
        }
        if (offset >= data.size) return null
        return type to data.copyOfRange(offset, data.size)
    }

    /** Undoes unsynchronisation between [start] and [end]: every 0xFF 0x00 pair becomes 0xFF. */
    private fun resynchronise(bytes: ByteArray, start: Int, end: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(end - start)
        var index = start
        while (index < end) {
            val value = bytes[index]
            out.write(value.toInt())
            index += if (value == 0xFF.toByte() && index + 1 < end && bytes[index + 1] == 0.toByte()) 2 else 1
        }
        return out.toByteArray()
    }

    /** A four-byte syncsafe integer (seven bits a byte); null if a byte has its top bit set. */
    private fun syncSafe(bytes: ByteArray, offset: Int): Int? {
        if (offset + 4 > bytes.size) return null
        var value = 0
        for (index in offset until offset + 4) {
            val byte = bytes[index].toInt() and 0xFF
            if (byte >= 0x80) return null
            value = (value shl 7) or byte
        }
        return value
    }

    private fun ByteArray.u32(offset: Int): Long =
        ((this[offset].toLong() and 0xFF) shl 24) or ((this[offset + 1].toLong() and 0xFF) shl 16) or ((this[offset + 2].toLong() and 0xFF) shl 8) or (this[offset + 3].toLong() and 0xFF)
    private fun ByteArray.s64(offset: Int): Long = (u32(offset) shl 32) or u32(offset + 4)
    private fun ByteArray.ascii(offset: Int, length: Int): String =
        if (offset < 0 || offset + length > size) "" else String(this, offset, length, Charsets.ISO_8859_1)
}
