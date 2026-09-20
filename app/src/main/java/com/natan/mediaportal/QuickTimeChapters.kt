package com.natan.mediaportal

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Resolves tref/chap text tracks using sample tables, for local files and Telegram ranges. */
internal object QuickTimeChapters {
    private const val MAX_SAMPLES = 10000
    private data class Box(val type: String, val body: Long, val end: Long)
    private data class ChunkRun(val first: Int, val count: Int)

    fun read(input: AudioChapters.RangeInput): List<AudioChapter> = runCatching {
        Parser(input).read()
    }.getOrDefault(emptyList())

    private class Parser(val input: AudioChapters.RangeInput) {
        private var budget = 4 * 1024 * 1024
        private fun bytes(offset: Long, count: Int): ByteArray {
            require(count >= 0 && count <= budget && offset >= 0 && offset <= input.length() - count)
            budget -= count
            input.seek(offset)
            return ByteArray(count).also { input.readFully(it) }
        }
        private fun boxes(start: Long, end: Long): List<Box> {
            val result = mutableListOf<Box>()
            var offset = start
            while (end - offset >= 8) {
                require(result.size < MAX_SAMPLES)
                val header = ByteBuffer.wrap(bytes(offset, 8))
                var size = header.uint()
                val type = ByteArray(4).also { header.get(it) }.toString(Charsets.US_ASCII)
                var length = 8
                if (size == 1L) { require(end - offset >= 16); size = ByteBuffer.wrap(bytes(offset + 8, 8)).long; length = 16 }
                if (size == 0L) size = end - offset
                require(size >= length && size <= end - offset)
                result += Box(type, offset + length, offset + size)
                offset += size
            }
            require(offset == end)
            return result
        }
        private fun Box.children() = boxes(body, end)
        private fun Box.data(max: Int = 256 * 1024): ByteBuffer {
            require(end - body in 0..max.toLong())
            return ByteBuffer.wrap(bytes(body, (end - body).toInt())).order(ByteOrder.BIG_ENDIAN)
        }
        private fun Box.prefix(count: Int): ByteBuffer {
            require(end - body >= count)
            return ByteBuffer.wrap(bytes(body, count))
        }
        private fun List<Box>.one(type: String) = single { it.type == type }
        private fun Box.timeScale(): Long {
            val version = prefix(1).get().toInt()
            require(version == 0 || version == 1)
            val data = prefix(if (version == 1) 24 else 16)
            data.position(if (version == 1) 20 else 12)
            return data.uint().also { require(it > 0) }
        }
        private fun List<Box>.trackID(): Long {
            val box = one("tkhd")
            val version = box.prefix(1).get().toInt()
            require(version == 0 || version == 1)
            val data = box.prefix(if (version == 1) 24 else 16)
            data.position(if (version == 1) 20 else 12)
            return data.uint()
        }
        fun read(): List<AudioChapter> {
            val movie = boxes(0, input.length()).one("moov").children()
            val tracks = movie.filter { it.type == "trak" }.map { it.children() }
            val referenced = tracks.flatMap { track ->
                track.filter { it.type == "tref" }.flatMap { it.children() }.filter { it.type == "chap" }.flatMap { box ->
                    val data = box.data(); require(data.remaining() % 4 == 0)
                    List(data.remaining() / 4) { data.uint() }
                }
            }.toSet()
            for (track in tracks.filter { it.trackID() in referenced }) {
                val chapters = runCatching { chapters(track, movie) }.getOrDefault(emptyList())
                if (chapters.isNotEmpty()) return chapters
            }
            return emptyList()
        }
        private fun chapters(track: List<Box>, movie: List<Box>): List<AudioChapter> {
            val media = track.one("mdia").children()
            val scale = media.one("mdhd").timeScale()
            val table = media.one("minf").children().one("stbl").children()
            val description = table.one("stsd").data()
            require(description.int == 0 && description.uint() == 1L)
            require(description.uint() >= 8)
            val codec = ByteArray(4).also { description.get(it) }.toString(Charsets.US_ASCII)
            require(codec == "text" || codec == "tx3g")
            // Chapter text uses decode times; don't silently misinterpret composition offsets.
            require(table.none { it.type == "ctts" })
            val sizesData = table.one("stsz").data()
            require(sizesData.int == 0)
            val fixedSize = sizesData.uint()
            val count = sizesData.count()
            val sizes = IntArray(count) {
                (if (fixedSize == 0L) sizesData.uint() else fixedSize).also { require(it in 2..65536) }.toInt()
            }
            val timesData = table.one("stts").data()
            require(timesData.int == 0)
            val times = LongArray(count)
            var sample = 0; var ticks = 0L
            repeat(timesData.count()) {
                val run = timesData.count(); val delta = timesData.uint()
                require(run <= count - sample && delta > 0)
                repeat(run) { times[sample++] = ticks; ticks = Math.addExact(ticks, delta) }
            }
            require(sample == count)
            val chunksBox = table.single { it.type == "stco" || it.type == "co64" }
            val chunksData = chunksBox.data()
            require(chunksData.int == 0)
            val chunks = LongArray(chunksData.count()) { if (chunksBox.type == "co64") chunksData.long else chunksData.uint() }
            val runsData = table.one("stsc").data()
            require(runsData.int == 0)
            val runs = List(runsData.count()) {
                val first = runsData.count(); val samples = runsData.count()
                require(first in 1..chunks.size && samples > 0 && runsData.uint() == 1L)
                ChunkRun(first, samples)
            }
            require(runs.isNotEmpty() && runs.first().first == 1 && runs.zipWithNext().all { (a, b) -> a.first < b.first })
            val starts = editedTimes(times, scale, track, movie)
            val result = mutableListOf<AudioChapter>()
            sample = 0
            var run = 0
            chunks.forEachIndexed { index, chunk ->
                if (run + 1 < runs.size && runs[run + 1].first == index + 1) run++
                require(runs[run].count <= count - sample)
                var offset = chunk
                repeat(runs[run].count) {
                    val data = ByteBuffer.wrap(bytes(offset, sizes[sample]))
                    val length = data.short.toInt() and 65535
                    require(length <= data.remaining())
                    val title = ByteArray(length).also { data.get(it) }
                    val charset = if (title.size >= 2 && ((title[0] == 0xFE.toByte() && title[1] == 0xFF.toByte()) ||
                        (title[0] == 0xFF.toByte() && title[1] == 0xFE.toByte()))) Charsets.UTF_16 else Charsets.UTF_8
                    starts[sample]?.let { result += AudioChapter(title.toString(charset).trim().ifBlank { "Chapter ${sample + 1}" }, it) }
                    offset += sizes[sample++]
                }
            }
            require(sample == count)
            return result
        }
        private fun editedTimes(times: LongArray, scale: Long, track: List<Box>, movie: List<Box>): List<Long?> {
            val edits = track.filter { it.type == "edts" }.flatMap { it.children() }.singleOrNull { it.type == "elst" }
                ?: return times.map { it * 1000 / scale }
            val data = edits.data()
            val version = data.int ushr 24
            require(version == 0 || version == 1)
            val movieScale = movie.one("mvhd").timeScale()
            val result = arrayOfNulls<Long>(times.size)
            var elapsed = 0.0
            repeat(data.count()) {
                val duration = if (version == 1) data.long else data.uint()
                val start = if (version == 1) data.long else data.int.toLong()
                require(duration >= 0 && start >= -1 && data.int == 0x00010000)
                val seconds = duration.toDouble() / movieScale
                if (start >= 0) times.forEachIndexed { index, time ->
                    val relative = (time - start).toDouble() / scale
                    if (relative >= 0 && relative < seconds && result[index] == null) result[index] = ((elapsed + relative) * 1000).toLong()
                }
                elapsed += seconds
            }
            return result.toList()
        }
    }
    private fun ByteBuffer.uint() = int.toLong() and 0xffffffffL
    private fun ByteBuffer.count() = uint().also { require(it <= MAX_SAMPLES) }.toInt()
}
