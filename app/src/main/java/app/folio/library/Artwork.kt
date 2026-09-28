package app.folio.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AtomicFile
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.readium.r2.shared.publication.services.coverFitting
import java.io.File

object Artwork {
    fun fromBytes(bytes: ByteArray, destination: File) {
        val bitmap = decode(bytes)
        try { save(bitmap, destination) } finally { bitmap.recycle() }
    }

    /**
     * Metadata only: bounded random reads, without creating a player. False when the file has no picture or needs more
     * than [budget] bytes or [timeoutMs] to find it; a failure of [read] itself is thrown, so the caller can retry.
     */
    suspend fun fromStream(size: Long, destination: File, budget: Int = 8 * 1024 * 1024, timeoutMs: Long = 20_000,
        read: suspend (Long, Int) -> ByteArray): Boolean = withContext(Dispatchers.IO) {
        val job = kotlinx.coroutines.currentCoroutineContext()
        val metadata = MediaMetadataRetriever()
        var left = budget
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        // The retriever reports every failed read the same way, so the reason is kept here.
        var failure: Exception? = null
        // The retriever asks for a few kilobytes at a time; each ask is a round trip when streaming, so reads go ahead
        // in larger blocks and later asks are answered from the last one.
        var block = ByteArray(0); var blockAt = 0L
        val source = object : android.media.MediaDataSource() {
            override fun getSize() = size
            override fun close() {}
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
                job.ensureActive()
                if (count == 0) return 0
                if (position >= size) return -1
                if (position < blockAt || position >= blockAt + block.size) {
                    if (left <= 0 || android.os.SystemClock.elapsedRealtime() >= deadline) throw java.io.IOException("Metadata read limit")
                    val length = minOf(64 * 1024L, left.toLong(), size - position).toInt()
                    left -= length
                    block = try { kotlinx.coroutines.runBlocking(job) { read(position, length) } } catch (e: Exception) { failure = e; throw e }
                    blockAt = position
                    if (block.isEmpty()) return -1
                }
                val start = (position - blockAt).toInt()
                val served = minOf(count, block.size - start)
                block.copyInto(buffer, offset, start, start + served)
                return served
            }
        }
        try {
            metadata.setDataSource(source)
            val picture = metadata.embeddedPicture
            failure?.let { throw it }
            picture?.let { fromBytes(it, destination); true } ?: false
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { throw failure ?: return@withContext false }
        finally { metadata.release(); source.close() }
    }
    private val writeLock = Any()
    private fun decode(bytes: ByteArray): Bitmap {
        require(bytes.size <= 20 * 1024 * 1024) { "Choose an image smaller than 20 MB." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image could not be opened." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("This image could not be opened.")
    }
    private fun save(bitmap: Bitmap, destination: File) {
        synchronized(writeLock) {
            val atomic = AtomicFile(destination); val output = atomic.startWrite()
            try { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
        }
    }
    fun fromAudio(file: File, destination: File): Boolean {
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(file.path)
            val bytes = metadata.embeddedPicture ?: return false
            val bitmap = decode(bytes)
            try { save(bitmap, destination) } finally { bitmap.recycle() }
            true
        } catch (_: Exception) { false } finally { metadata.release() }
    }
    suspend fun upload(context: Context, id: String, uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited() } ?: error("This image could not be opened.")
        val bitmap = decode(bytes)
        try { save(bitmap, File(context.filesDir, "$id-custom.jpg")) } finally { bitmap.recycle() }
    }
    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        var count: Int
        while (read(buffer).also { count = it } != -1) { require(output.size() + count <= 20 * 1024 * 1024) { "Choose an image smaller than 20 MB." }; output.write(buffer, 0, count) }
        return output.toByteArray()
    }
    /** The largest power-of-two step down that still leaves [longSide] px at least [size] px. */
    internal fun sampleFor(longSide: Int, size: Int): Int {
        var sample = 1
        while (longSide / (sample * 2) >= size) sample *= 2
        return sample
    }
    suspend fun restore(context: Context, title: Title): Boolean = withContext(Dispatchers.IO) {
        val destination = File(context.filesDir, "${title.id}.jpg")
        var found = title.audio?.let { fromAudio(File(context.filesDir, it), destination) } ?: false
        if (!found && title.ebook != null) {
            val publication = Books(context).open(File(context.filesDir, title.ebook))
            try { publication.coverFitting(Size(600, 900))?.let { bitmap -> save(bitmap, destination); found = true } }
            finally { publication.close() }
        }
        if (found) File(context.filesDir, "${title.id}-custom.jpg").delete()
        found
    }
}

/**
 * Covers decoded at the size they are shown, kept in memory. Opening the player or coming back to a list draws a cover
 * already decoded at once, rather than reading and decoding its file again and swapping it in a few frames later.
 * An entry is only reused while its file is unchanged, so a new or recovered cover replaces it.
 */
internal object CoverCache {
    private class Entry(val bitmap: Bitmap, val path: String, val modified: Long, val length: Long)
    private val entries = object : android.util.LruCache<String, Entry>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Entry) = value.bitmap.byteCount
    }
    private fun key(id: String, size: Int) = "$id@$size"
    /** What was last decoded for [id] at [size] px, without touching the disk: safe to call while composing. */
    fun peek(id: String, size: Int): Bitmap? = entries.get(key(id, size))?.bitmap
    /** [file]'s cover for [id], at least [size] px along its longer side where the image allows. Reads the disk: call off the main thread. */
    fun load(id: String, file: File, size: Int): Bitmap? {
        val key = key(id, size)
        val modified = file.lastModified(); val length = file.length()
        entries.get(key)?.takeIf { it.path == file.path && it.modified == modified && it.length == length }?.let { return it.bitmap }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { entries.remove(key); return null }
        val options = BitmapFactory.Options().apply { inSampleSize = Artwork.sampleFor(maxOf(bounds.outWidth, bounds.outHeight), size) }
        val sampled = BitmapFactory.decodeFile(file.path, options) ?: run { entries.remove(key); return null }
        // Sampling only halves, so a cover can still come out nearly twice the size it is drawn; it is scaled the rest of the way.
        val long = maxOf(sampled.width, sampled.height)
        val bitmap = if (long <= size * 5 / 4) sampled
            else Bitmap.createScaledBitmap(sampled, (sampled.width * size / long).coerceAtLeast(1), (sampled.height * size / long).coerceAtLeast(1), true)
                .also { if (it !== sampled) sampled.recycle() }
        entries.put(key, Entry(bitmap, file.path, modified, length))
        return bitmap
    }
}
