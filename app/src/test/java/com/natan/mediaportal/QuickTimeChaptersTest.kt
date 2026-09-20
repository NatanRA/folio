package com.natan.mediaportal

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class QuickTimeChaptersTest {
    private fun data(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { it.write() }
    }.toByteArray()
    private fun ints(vararg values: Int) = data { values.forEach { writeInt(it) } }
    private fun box(type: String, vararg bodies: ByteArray) = data {
        writeInt(8 + bodies.sumOf { it.size }); writeBytes(type); bodies.forEach { write(it) }
    }
    private fun fixture(wide: Boolean = false, edited: Boolean = false, referenced: Boolean = true,
                        invalidSize: Boolean = false, codec: String = "text"): ByteArray {
        val names = listOf("Opening", "The journey", "Fin")
        val samples = names.mapIndexed { i, name ->
            val text = name.toByteArray(if (i == 2) Charsets.UTF_16 else Charsets.UTF_8)
            data { writeShort(text.size); write(text); writeInt(0) }
        }
        val media = box("mdat", *samples.toTypedArray())
        val offsets = listOf(8L, 8L + samples[0].size + samples[1].size)
        fun header(id: Int) = box("tkhd", if (wide) data { writeInt(0x01000000); writeLong(0); writeLong(0); writeInt(id) } else ints(0, 0, 0, id))
        fun timing(type: String) = box(type, if (wide) data { writeInt(0x01000000); writeLong(0); writeLong(0); writeInt(1000) } else ints(0, 0, 0, 1000))
        val edit = if (edited) box("edts", box("elst", ints(0, 2, 2000, -1, 0x10000, 12000, 0, 0x10000))) else byteArrayOf()
        val tables = box("stbl",
            box("stsd", ints(0, 1), box(codec)),
            box("stts", ints(0, 2, 2, 6000, 1, 3000)),
            box("stsc", ints(0, 2, 1, 2, 1, 2, 1, 1)),
            box("stsz", ints(0, 0, 3), ints(*samples.map { if (invalidSize) Int.MAX_VALUE else it.size }.toIntArray())),
            box(if (wide) "co64" else "stco", ints(0, 2), data { offsets.forEach { if (wide) writeLong(it) else writeInt(it.toInt()) } }))
        return media + box("moov", timing("mvhd"),
            box("trak", header(1), if (referenced) box("tref", box("chap", ints(2))) else byteArrayOf()),
            box("trak", header(2), edit, box("mdia", timing("mdhd"), box("minf", tables))))
    }
    private fun read(bytes: ByteArray, partial: Boolean = false): List<AudioChapter> = AudioChapters.read(
        AudioChapters.RangeInput(bytes.size.toLong()) { offset, count ->
            bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + if (partial) minOf(count, 3) else count))
        })

    @Test fun readsReferencedTextTracksAndChangingChunkRuns() {
        val expected = listOf(AudioChapter("Opening", 0), AudioChapter("The journey", 6000), AudioChapter("Fin", 12000))
        assertEquals(expected, read(fixture()))
        assertEquals(expected, read(fixture(wide = true), partial = true))
    }
    @Test fun appliesEmptyEditsAndExcludesChaptersOutsideTheEdit() {
        assertEquals(listOf(AudioChapter("Opening", 2000), AudioChapter("The journey", 8000)), read(fixture(edited = true)))
    }
    @Test fun ignoresUnreferencedAndUnsupportedTracksAndBoundsInvalidSamples() {
        assertTrue(read(fixture(referenced = false)).isEmpty())
        assertTrue(read(fixture(codec = "jpeg")).isEmpty())
        assertTrue(read(fixture(invalidSize = true)).isEmpty())
        val bytes = fixture()
        assertTrue(QuickTimeChapters.read(AudioChapters.RangeInput(bytes.size.toLong()) { _, _ -> byteArrayOf() }).isEmpty())
    }
    @Test fun skipsLargeAudioPayloads() {
        val fixture = fixture()
        // Move the movie far past a sparse audio payload; chapter samples stay at their original offsets.
        val oldMovie = java.nio.ByteBuffer.wrap(fixture).int
        val movie = fixture.copyOfRange(oldMovie, fixture.size)
        val movieOffset = 3_000_000_000L
        val header = data { writeInt(movieOffset.toInt()); writeBytes("mdat") }
        var readBytes = 0
        val chapters = AudioChapters.read(AudioChapters.RangeInput(movieOffset + movie.size) { offset, count ->
            readBytes += count
            when {
                offset >= movieOffset -> movie.copyOfRange((offset - movieOffset).toInt(), (offset - movieOffset).toInt() + count)
                offset < 8 -> header.copyOfRange(offset.toInt(), offset.toInt() + count)
                else -> fixture.copyOfRange(offset.toInt(), offset.toInt() + count)
            }
        })
        assertEquals(listOf(AudioChapter("Opening", 0), AudioChapter("The journey", 6000), AudioChapter("Fin", 12000)), chapters)
        assertTrue(readBytes < 4096)
    }
    @Test fun optionalLocalSampleMatchesIndependentChapterMetadata() {
        val sample = System.getenv("FOLIO_CHAPTER_SAMPLE")
        val reference = System.getenv("FOLIO_CHAPTER_REFERENCE")
        assumeTrue(sample != null && reference != null)
        val expected = File(reference!!).readLines().map {
            val parts = it.split('\t', limit = 2); AudioChapter(parts[1], parts[0].toLong())
        }
        val actual = AudioChapters.read(File(sample!!))
        assertEquals(expected, actual)
        java.io.RandomAccessFile(sample, "r").use { file ->
            var readBytes = 0
            val streamed = AudioChapters.read(AudioChapters.RangeInput(file.length()) { offset, count ->
                file.seek(offset)
                ByteArray(minOf(count, 3)).also { file.readFully(it); readBytes += it.size }
            })
            assertEquals(expected, streamed)
            assertTrue("Chapter extraction should skip the audio payload", readBytes < 32768)
        }
    }
}
