package com.natan.mediaportal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Files are built here byte by byte, so each case says exactly which layout the reader has to cope with. */
class EmbeddedCoverTest {
    private fun data(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { it.write() } }.toByteArray()
    private fun box(type: String, vararg bodies: ByteArray) = data { writeInt(8 + bodies.sumOf { it.size }); writeBytes(type); bodies.forEach { write(it) } }
    private fun fullBox(type: String, vararg bodies: ByteArray) = box(type, byteArrayOf(0, 0, 0, 0), *bodies)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte())
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 9, 8, 7)

    private fun cover(image: ByteArray, kind: Int = 13) = box("covr", box("data", data { writeInt(kind); writeInt(0) }, image))
    private fun hdlr() = fullBox("hdlr", ByteArray(4), "mdirappl".toByteArray(), ByteArray(9))
    private fun meta(vararg items: ByteArray, quickTime: Boolean = false) =
        if (quickTime) box("meta", hdlr(), box("ilst", *items)) else fullBox("meta", hdlr(), box("ilst", *items))
    private fun moov(udta: ByteArray) = box("moov", fullBox("mvhd", ByteArray(96)), box("trak", box("tkhd", ByteArray(84)), box("mdia", ByteArray(400))), udta)
    private val ftyp = box("ftyp", "M4B ".toByteArray(), ByteArray(4), "M4A mp42isom".toByteArray())

    private class Reads(val file: ByteArray) {
        val ranges = mutableListOf<LongRange>()
        suspend fun read(offset: Long, length: Int): ByteArray {
            ranges += offset until offset + length
            // Hands back at most 5 bytes a call, as a stream arriving in pieces would.
            return file.copyOfRange(offset.toInt(), minOf(file.size, offset.toInt() + minOf(length, 5)))
        }
    }
    private fun find(file: ByteArray) = runBlocking { EmbeddedCover.find(file.size.toLong(), Reads(file)::read) }
    private fun image(file: ByteArray) = (find(file) as? EmbeddedCover.Found)?.image

    @Test fun readsCoverAfterALargeAudioBoxWithoutReadingTheAudio() = runBlocking {
        val movie = moov(box("udta", meta(box("©nam", box("data", ByteArray(8), "Title".toByteArray())), cover(jpeg))))
        // A 3 GB `mdat` stands in for the audio: only its header exists, so any read of its body would fail.
        val audioSize = 3_000_000_000L
        val header = data { writeInt(1); writeBytes("mdat"); writeLong(audioSize) }
        val movieAt = ftyp.size + audioSize
        val size = movieAt + movie.size
        val ranges = mutableListOf<LongRange>()
        val result = EmbeddedCover.find(size) { offset, length ->
            ranges += offset until offset + length
            when {
                offset < ftyp.size -> ftyp.copyOfRange(offset.toInt(), minOf(ftyp.size, offset.toInt() + length))
                offset < ftyp.size + header.size -> header.copyOfRange((offset - ftyp.size).toInt(), minOf(header.size, (offset - ftyp.size).toInt() + length))
                offset >= movieAt -> movie.copyOfRange((offset - movieAt).toInt(), minOf(movie.size, (offset - movieAt).toInt() + length))
                else -> error("Read inside the audio at $offset")
            }
        }
        assertArrayEquals(jpeg, (result as EmbeddedCover.Found).image)
        val audioBody = ftyp.size + header.size until movieAt
        assertTrue("No read may touch the audio: $ranges", ranges.none { it.first in audioBody || it.last in audioBody })
        // Headers and the metadata box, nothing like the sample tables' size.
        assertTrue(ranges.sumOf { it.last - it.first + 1 } < 2_000)
    }

    @Test fun readsFaststartFilesAndBothKindsOfMeta() {
        val iso = ftyp + moov(box("udta", meta(cover(png, kind = 14)))) + box("mdat", ByteArray(64))
        assertArrayEquals(png, image(iso))
        val quickTime = ftyp + moov(box("udta", meta(cover(jpeg), quickTime = true))) + box("mdat", ByteArray(64))
        assertArrayEquals(jpeg, image(quickTime))
        // Some writers put `meta` straight under `moov`, without `udta`.
        val bare = ftyp + box("moov", fullBox("mvhd", ByteArray(96)), meta(cover(jpeg))) + box("mdat", ByteArray(8))
        assertArrayEquals(jpeg, image(bare))
    }

    @Test fun anMp4WithoutCoverIsMissingNotUnreadable() {
        val file = ftyp + moov(box("udta", meta(box("©nam", box("data", ByteArray(8), "Title".toByteArray()))))) + box("mdat", ByteArray(32))
        assertEquals(EmbeddedCover.Missing, find(file))
        assertEquals(EmbeddedCover.Missing, find(ftyp + moov(box("free", ByteArray(4)))))
    }

    private fun syncSafe(value: Int) = byteArrayOf((value shr 21 and 0x7F).toByte(), (value shr 14 and 0x7F).toByte(), (value shr 7 and 0x7F).toByte(), (value and 0x7F).toByte())
    private fun id3(version: Int, frames: ByteArray, flags: Int = 0) = data {
        writeBytes("ID3"); writeByte(version); writeByte(0); writeByte(flags); write(syncSafe(frames.size)); write(frames)
    } + ByteArray(256) { 0x55 }
    private fun frame(version: Int, id: String, body: ByteArray) = data {
        writeBytes(id)
        when (version) {
            2 -> { writeByte(body.size shr 16); writeByte(body.size shr 8); writeByte(body.size) }
            3 -> { writeInt(body.size); writeShort(0) }
            else -> { write(syncSafe(body.size)); writeShort(0) }
        }
        write(body)
    }
    private fun apic(type: Int, image: ByteArray, encoding: Int = 0, description: ByteArray = "Cover".toByteArray()) = data {
        writeByte(encoding); writeBytes("image/jpeg"); writeByte(0); writeByte(type); write(description)
        write(if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0)); write(image)
    }

    @Test fun readsId3PicturesInEveryVersion() {
        val title = "TIT2".toByteArray()
        for (version in listOf(3, 4)) {
            val tag = id3(version, frame(version, "TIT2", byteArrayOf(0) + "Book".toByteArray()) + frame(version, "APIC", apic(3, jpeg)) + ByteArray(40))
            assertArrayEquals("v2.$version", jpeg, image(tag))
        }
        val pic = data { writeByte(0); writeBytes("PNG"); writeByte(3); writeBytes("Cover"); writeByte(0); write(png) }
        assertArrayEquals(png, image(id3(2, frame(2, "TT2", byteArrayOf(0) + title) + frame(2, "PIC", pic))))
    }

    @Test fun skipsUtf16DescriptionsOnTheirEvenBoundary() {
        // "Cöver" in UTF-16 with a byte-order mark; its 0x00 bytes must not end the description early.
        val description = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Cöver".toByteArray(Charsets.UTF_16LE)
        val tag = id3(3, frame(3, "APIC", apic(3, jpeg, encoding = 1, description = description)))
        assertArrayEquals(jpeg, image(tag))
        val bigEndian = id3(4, frame(4, "APIC", apic(3, png, encoding = 2, description = "A".toByteArray(Charsets.UTF_16BE))))
        assertArrayEquals(png, image(bigEndian))
    }

    @Test fun prefersTheFrontCoverOverOtherPictures() {
        val tag = id3(3, frame(3, "APIC", apic(4, png)) + frame(3, "APIC", apic(3, jpeg)))
        assertArrayEquals(jpeg, image(tag))
        // Without a front cover, the first picture stands in.
        assertArrayEquals(png, image(id3(3, frame(3, "APIC", apic(4, png)) + frame(3, "APIC", apic(8, jpeg)))))
        assertEquals(EmbeddedCover.Missing, find(id3(3, frame(3, "TIT2", byteArrayOf(0, 'A'.code.toByte())))))
    }

    @Test fun undoesUnsynchronisation() {
        // Unsynchronised, every 0xFF is followed by a 0x00 the reader has to drop again.
        fun unsync(bytes: ByteArray) = ByteArrayOutputStream().also { out -> bytes.forEach { out.write(it.toInt()); if (it == 0xFF.toByte()) out.write(0) } }.toByteArray()
        val v3 = id3(3, unsync(frame(3, "APIC", apic(3, jpeg))), flags = 0x80)
        assertArrayEquals(jpeg, image(v3))
        // v2.4 unsynchronises frame by frame, so its frame size counts the stored bytes.
        val stored = unsync(apic(3, jpeg))
        val v4 = id3(4, data { writeBytes("APIC"); write(syncSafe(stored.size)); writeShort(0x0002); write(stored) }, flags = 0x80)
        assertArrayEquals(jpeg, image(v4))
    }

    @Test fun skipsExtendedHeaders() {
        val v3 = id3(3, data { writeInt(6); write(ByteArray(6)) } + frame(3, "APIC", apic(3, jpeg)), flags = 0x40)
        assertArrayEquals(jpeg, image(v3))
        val v4 = id3(4, data { write(syncSafe(6)); writeByte(1); writeByte(0) } + frame(4, "APIC", apic(3, png)), flags = 0x40)
        assertArrayEquals(png, image(v4))
    }

    @Test fun readsV24TagsWrittenWithPlainFrameSizes() {
        // Some taggers store v2.4 frame sizes as plain integers; 300 bytes read as syncsafe would be wrong.
        val large = ByteArray(300) { (it % 200).toByte() }
        val body = apic(3, large)
        val frames = data { writeBytes("APIC"); writeInt(body.size); writeShort(0); write(body) } + frame(4, "TIT2", byteArrayOf(0, 'A'.code.toByte()))
        assertArrayEquals(large, image(id3(4, frames)))
    }

    @Test fun truncatedOrForeignFilesAreUnreadableWithoutThrowing() {
        val whole = ftyp + moov(box("udta", meta(cover(jpeg))))
        for (cut in listOf(0, 8, 20, ftyp.size + 4, whole.size - 12)) {
            assertEquals("cut at $cut", EmbeddedCover.Unreadable, find(whole.copyOf(cut)))
        }
        val tag = id3(3, frame(3, "APIC", apic(3, jpeg)))
        assertEquals(EmbeddedCover.Unreadable, find(tag.copyOf(20)))
        assertEquals(EmbeddedCover.Unreadable, find("fLaC".toByteArray() + ByteArray(64)))
        assertEquals(EmbeddedCover.Unreadable, find(ByteArray(4096) { (it * 31).toByte() }))
        // A box claiming to be larger than the file, and a syncsafe size with its top bit set.
        assertEquals(EmbeddedCover.Unreadable, find(data { writeInt(99_999); writeBytes("ftyp") } + ByteArray(64)))
        assertEquals(EmbeddedCover.Unreadable, find(data { writeBytes("ID3"); writeByte(3); writeByte(0); writeByte(0); writeInt(0x00008000) } + ByteArray(64)))
        // Frames that stop lining up leave nothing to trust.
        assertEquals(EmbeddedCover.Unreadable, find(id3(3, "APIC".toByteArray() + byteArrayOf(0x7F, 0, 0, 0, 0, 0) + ByteArray(32))))
    }

    @Test fun passesOnAFailedReadSoTheCallerCanTryAgain() {
        val failure = runCatching { runBlocking { EmbeddedCover.find(1_000_000) { _, _ -> throw java.io.IOException("offline") } } }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
    }
}
