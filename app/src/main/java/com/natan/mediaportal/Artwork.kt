package com.natan.mediaportal

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

    /** Metadata only: bounded random reads, without creating a player. */
    suspend fun fromStream(size: Long, destination: File, read: suspend (Long, Int) -> ByteArray): Boolean = withContext(Dispatchers.IO) {
        val job = kotlinx.coroutines.currentCoroutineContext()
        val metadata = MediaMetadataRetriever()
        var budget = 8 * 1024 * 1024
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        val source = object : android.media.MediaDataSource() {
            override fun getSize() = size
            override fun close() {}
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
                job.ensureActive()
                if (count == 0) return 0
                if (position >= size) return -1
                if (budget <= 0 || android.os.SystemClock.elapsedRealtime() >= deadline) throw java.io.IOException("Metadata read limit")
                val length = minOf(count, 256 * 1024, budget)
                budget -= length
                val bytes = kotlinx.coroutines.runBlocking(job) { read(position, length) }
                bytes.copyInto(buffer, offset)
                return bytes.size
            }
        }
        try {
            metadata.setDataSource(source)
            metadata.embeddedPicture?.let { fromBytes(it, destination); true } ?: false
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { false }
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
