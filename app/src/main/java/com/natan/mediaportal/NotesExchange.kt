package com.natan.mediaportal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Portable annotation journal. Deletions remain as tombstones so another device cannot revive them. */
internal class NotesExchange(private val context: Context, private val id: String) {
    companion object { val lock = Any() }
    private val prefs = context.getSharedPreferences("annotation-journal", Context.MODE_PRIVATE)
    private val kinds = mapOf("reading-notes" to "note", "reading-bookmarks" to "bookmark", "reading-words" to "word")
    private fun journal() = JSONArray(prefs.getString(id, "[]"))
    private fun write(records: JSONArray) { check(prefs.edit().putString(id, records.toString()).commit()) { "Could not save annotation history." } }
    fun record(collection: String, before: List<ReadingNote>, after: List<ReadingNote>) = synchronized(lock) {
        val records = journal()
        val known = (0 until records.length()).associate { records.getJSONObject(it).getString("id") to records.getJSONObject(it) }.toMutableMap()
        val now = System.currentTimeMillis()
        (after + before.filter { old -> after.none { it.id == old.id } }).forEach { note ->
            val deleted = after.none { it.id == note.id }
            if (deleted || before.find { it.id == note.id } != note || known[note.id] == null) {
                known[note.id] = JSONObject().put("id", note.id).put("kind", kinds.getValue(collection)).put("locator", note.locator.toJSON().toString())
                    .put("note", note.note).put("modified", now).put("revision", java.util.UUID.randomUUID().toString()).put("deleted", deleted)
            }
        }
        write(JSONArray(known.values.toList()))
    }
    private fun fingerprint(): String {
        val title = LibraryStore(context).all().first { it.id == id }
        val digest = MessageDigest.getInstance("SHA-256")
        LibraryStore(context).file(requireNotNull(title.ebook)).inputStream().use { input ->
            val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun export(): String = synchronized(lock) {
        // Migrate annotations created before journal support without treating them as new edits.
        val records = journal(); val ids = (0 until records.length()).map { records.getJSONObject(it).getString("id") }.toSet()
        kinds.forEach { (collection, kind) -> ReadingNotes(context, id, collection).all().filter { it.id !in ids }.forEach { note ->
            records.put(JSONObject().put("id", note.id).put("kind", kind).put("locator", note.locator.toJSON().toString()).put("note", note.note).put("modified", 0L).put("revision", "").put("deleted", false))
        } }
        write(records)
        JSONObject().put("version", 1).put("document", fingerprint()).put("records", records).toString(2)
    }
    fun merge(input: String) = synchronized(lock) {
        require(input.toByteArray().size <= 8_000_000) { "Annotation file is too large." }
        val incoming = JSONObject(input)
        require(incoming.getInt("version") == 1 && incoming.getString("document") == fingerprint()) { "These notes belong to a different book file. Use the same EPUB on both devices." }
        val incomingRecords = incoming.getJSONArray("records")
        require(incomingRecords.length() <= 10000)
        val all = journal(); val merged = (0 until all.length()).associate { all.getJSONObject(it).getString("id") to all.getJSONObject(it) }.toMutableMap()
        for (i in 0 until incomingRecords.length()) {
            val record = incomingRecords.getJSONObject(i)
            require(record.getString("kind") in kinds.values && record.getString("note").length <= 10000 && record.getString("id").length <= 100)
            requireNotNull(Locator.fromJSON(JSONObject(record.getString("locator"))))
            require(record.getLong("modified") >= 0)
            val previous = merged[record.getString("id")]
            if (previous == null || record.getLong("modified") > previous.getLong("modified") ||
                (record.getLong("modified") == previous.getLong("modified") && (record.getBoolean("deleted") && !previous.getBoolean("deleted") ||
                    record.getBoolean("deleted") == previous.getBoolean("deleted") && record.optString("revision") > previous.optString("revision")))) merged[record.getString("id")] = record
        }
        write(JSONArray(merged.values.toList()))
        kinds.forEach { (collection, kind) ->
            ReadingNotes(context, id, collection).save(merged.values.filter { it.getString("kind") == kind && !it.getBoolean("deleted") }.map {
                ReadingNote(it.getString("id"), requireNotNull(Locator.fromJSON(JSONObject(it.getString("locator")))), it.getString("note"))
            }, recordChanges = false)
        }
    }
    suspend fun sync() = withContext(Dispatchers.IO) {
        val account = BookServer(context, credentialNamespace = "notes-sync").account() ?: error("Connect Notes sync under Server first.")
        val payload = export()
        val connection = URI(account.url.trimEnd('/') + "/" + JSONObject(payload).getString("document")).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 15000; connection.readTimeout = 30000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer " + account.password)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload.toByteArray()) }
            check(connection.responseCode == 200) { "Notes sync failed (${connection.responseCode}). Local notes are safe." }
            val bytes = connection.inputStream.use { it.readBytesBounded(8_000_000) }
            merge(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384)
    while (true) { val count = read(buffer); if (count < 0) break; require(out.size() + count <= limit) { "File is too large." }; out.write(buffer, 0, count) }
    return out.toByteArray()
}
