package com.natan.mediaportal

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Where a listener is in one audiobook, in milliseconds, as it travels between their devices. */
data class ListeningPosition(val key: String, val position: Long, val duration: Long, val modified: Long)

/**
 * The rules of the shared positions service, held clear of storage and network so both ends of an
 * exchange read in one place. Milliseconds throughout, the unit Android already saves.
 */
object Positions {
    /** Thirty days: generous for any audiobook, and the ceiling the service itself enforces. */
    const val LIMIT_MS = 2_592_000_000L
    const val MAX_RECORDS = 5000
    private val key = Regex("[A-Za-z0-9:_.-]{1,200}")

    /**
     * Only Telegram books travel. Their id comes from the same account, chat and message on every
     * device, while a downloaded or imported copy is identified differently on each one.
     */
    fun shareable(id: String) = id.startsWith("tg-") && key.matches(id)

    fun valid(record: ListeningPosition) = shareable(record.key) && record.modified >= 0 &&
        record.position in 0..LIMIT_MS && record.duration in 0..LIMIT_MS

    fun outgoing(ids: List<String>, saved: (String) -> ListeningPosition?): List<ListeningPosition> =
        ids.filter { shareable(it) }.mapNotNull(saved).filter { valid(it) }.take(MAX_RECORDS)

    /**
     * What a merged answer may change here. A position this device wrote later is never rewound,
     * and the book held by the player is left alone rather than moved mid-sentence.
     */
    fun incoming(records: List<ListeningPosition>, active: String?, written: (String) -> Long?): List<ListeningPosition> =
        records.filter { it.key != active && valid(it) }
            // A null reading means the book is not in this library yet; the next pull will carry it.
            .filter { record -> written(record.key)?.let { it < record.modified } == true }

    /** A duration of zero means the other device does not know it; a length known here survives. */
    fun updates(record: ListeningPosition): Map<String, Long> = buildMap {
        put("audio:${record.key}", record.position)
        if (record.duration > 0) put("audioDuration:${record.key}", record.duration)
        put("audioModified:${record.key}", record.modified)
        // Home offers the most recently opened title, so a position from elsewhere dates it too.
        put("lastOpened:${record.key}", record.modified)
    }
}

/**
 * Listening positions through the reader's own annotation service, reusing its saved address and
 * token. One POST both offers what this device knows and applies the merged answer, so there is
 * nothing to press and nothing to hand across.
 */
class PositionSync(private val context: Context, private val connect: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection }) {
    companion object {
        private const val INTERVAL_MS = 30_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val gate = Mutex()
        private val queued = AtomicBoolean(false)
        private var finished = 0L
        /** Bumped once a pulled position has landed, so Home can show it without another visit. */
        val revision = MutableStateFlow(0)

        /**
         * Fire and forget: a failed exchange stays invisible and is retried by the next trigger.
         * Playback saves a position every second, so a throttled request waits its window out
         * instead of being dropped, and the last position of a session still leaves the device.
         */
        fun schedule(context: Context, immediate: Boolean) {
            if (!immediate && !queued.compareAndSet(false, true)) return
            val application = context.applicationContext
            scope.launch {
                gate.withLock {
                    val idle = SystemClock.elapsedRealtime() - finished
                    if (!immediate) {
                        if (idle < INTERVAL_MS) delay(INTERVAL_MS - idle)
                        queued.set(false)
                    }
                    runCatching { PositionSync(application).exchange() }
                    finished = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    private val progress = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    suspend fun exchange() = withContext(Dispatchers.IO) {
        // Without a configured annotation service the feature is simply off.
        val account = BookServer(context, credentialNamespace = "notes-sync").account() ?: return@withContext
        val known = LibraryStore(context).all().map { it.id }
        val answer = post(account.url.trimEnd('/') + "/positions", account.password, encode(Positions.outgoing(known) { saved(it) }))
        val library = known.toSet()
        val records = Positions.incoming(decode(answer), PlaybackService.activeBook) { id ->
            if (id in library) progress.getLong("audioModified:$id", 0) else null
        }
        if (records.isNotEmpty()) {
            val edit = progress.edit()
            records.forEach { record -> Positions.updates(record).forEach { (key, value) -> edit.putLong(key, value) } }
            check(edit.commit())
            revision.value++
        }
    }

    /** What this device has to offer for one book, or nothing if it has never written a position. */
    private fun saved(id: String): ListeningPosition? {
        val modified = progress.getLong("audioModified:$id", 0)
        if (modified <= 0) return null
        return ListeningPosition(id, progress.getLong("audio:$id", 0).coerceIn(0, Positions.LIMIT_MS),
            progress.getLong("audioDuration:$id", 0).coerceIn(0, Positions.LIMIT_MS), modified)
    }

    private fun encode(records: List<ListeningPosition>): String {
        val array = JSONArray()
        records.forEach { array.put(JSONObject().put("key", it.key).put("position", it.position).put("duration", it.duration).put("modified", it.modified)) }
        return JSONObject().put("version", 1).put("records", array).toString()
    }

    private fun decode(answer: String): List<ListeningPosition> {
        val body = JSONObject(answer)
        require(body.getInt("version") == 1) { "Unsupported position format." }
        val records = body.getJSONArray("records")
        require(records.length() <= Positions.MAX_RECORDS) { "Too many positions." }
        return (0 until records.length()).map { records.getJSONObject(it) }
            .map { ListeningPosition(it.getString("key"), it.getLong("position"), it.getLong("duration"), it.getLong("modified")) }
    }

    private fun post(address: String, token: String, payload: String): String {
        val connection = connect(address)
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 15000; connection.readTimeout = 30000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload.toByteArray()) }
            check(connection.responseCode == 200) { "Position sync returned HTTP ${connection.responseCode}." }
            return connection.inputStream.use { it.readBytesBounded(8_000_000) }.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }
}
