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

    /**
     * The stamp a position saved before this feature existed never got, taken once from the date
     * of the save that left it. Only once: reading that date as a stamp for good would let merely
     * opening a title, which dates it again, pass an untouched position off as the newest one and
     * rewind the device that really did move it. A book still at the start was opened rather than
     * listened to and has no position to date.
     */
    fun backfill(position: Long, audioModified: Long, lastOpened: Long): Long? =
        lastOpened.takeIf { it > 0 && position > 0 && audioModified <= 0 }

    /**
     * What this device has to offer for one book, or nothing when it has never written a position
     * for it. An unstamped book is equally unable to hold back a record arriving from elsewhere,
     * so the one stamp answers in both directions and the two ends cannot disagree.
     */
    fun offer(id: String, position: Long, duration: Long, audioModified: Long): ListeningPosition? {
        if (audioModified <= 0) return null
        return ListeningPosition(id, position.coerceIn(0, LIMIT_MS), duration.coerceIn(0, LIMIT_MS), audioModified)
    }

    /** A record stamped zero can never be applied by anyone, so it would only take up a row. */
    fun outgoing(ids: List<String>, saved: (String) -> ListeningPosition?): List<ListeningPosition> =
        ids.filter { shareable(it) }.mapNotNull(saved).filter { valid(it) && it.modified > 0 }.take(MAX_RECORDS)

    /**
     * What a merged answer may change here. A position this device wrote later is never rewound,
     * and the book being listened to is left alone rather than moved mid-sentence.
     */
    fun incoming(records: List<ListeningPosition>, active: String?, written: (String) -> Long?): List<ListeningPosition> =
        records.filter { it.key != active && valid(it) }
            // A null reading means the book is not in this library yet; the next pull will carry it.
            .filter { record -> written(record.key)?.let { it < record.modified } == true }

    /** A duration of zero means the other device does not know it; a length known here survives. */
    fun updates(record: ListeningPosition, lastOpened: Long): Map<String, Long> = buildMap {
        put("audio:${record.key}", record.position)
        if (record.duration > 0) put("audioDuration:${record.key}", record.duration)
        put("audioModified:${record.key}", record.modified)
        // Marked as another device's until the player has been moved there. A book loading while
        // this lands has already been told where to start, and the save that follows would write
        // that stale position back over this one and stamp it as a move made here.
        put("pulled:${record.key}", record.position)
        // Home offers the most recently opened title, so a position from elsewhere dates it too,
        // but forwards only: an older record would demote a book opened here since it was written.
        put("lastOpened:${record.key}", maxOf(lastOpened, record.modified))
    }

    /**
     * Whether saving this position is this device writing one, [stored] being negative when the
     * book has none saved yet. A paused book ticking the clock over an unchanged position is not,
     * and neither is the first save of a book that opens at the start having never been played:
     * either would outrank a genuinely newer position waiting on another device.
     */
    fun moved(position: Long, stored: Long) = position != stored && (stored >= 0 || position > 0)

    /**
     * The time to stamp a write made here with, never older than the one the book already carries.
     * A clock put back — an NTP correction, a hand-set date, a battery pulled out — would otherwise
     * date every later position before the ones this device has already exchanged, so it would lose
     * every merge against its own earlier work and keep pulling an older position back in.
     */
    fun stamp(now: Long, stored: Long) = maxOf(now, stored)
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
        @Volatile private var finished = 0L
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
                if (!immediate) {
                    // Waited out before the lock is taken: a pause or a departing app would otherwise
                    // queue behind this whole window, exactly when the process is likeliest to die.
                    val idle = SystemClock.elapsedRealtime() - finished
                    if (idle < INTERVAL_MS) delay(INTERVAL_MS - idle)
                    queued.set(false)
                }
                gate.withLock {
                    runCatching { PositionSync(application).exchange() }
                    finished = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    private val progress = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    suspend fun exchange() = withContext(Dispatchers.IO) {
        backfill()
        // Without a configured annotation service the feature is simply off.
        val account = BookServer(context, credentialNamespace = "notes-sync").account() ?: return@withContext
        val known = LibraryStore(context).all().map { it.id }
        val answer = post(account.url.trimEnd('/') + "/positions", account.password, encode(Positions.outgoing(known) { saved(it) }))
        val library = known.toSet()
        val merged = decode(answer)
        // Choosing what may land, moving the player and writing it are one step on the thread that
        // saves positions. Split across threads, a book that starts playing in between would keep
        // its own stale position while preferences held the new one, and the next save would put
        // the stale one back under a fresh stamp and rewind the device the record came from.
        val applied = withContext(Dispatchers.Main) {
            val records = Positions.incoming(merged, PlaybackService.activeBook) { id ->
                // A null reading is a book this library does not hold yet, not a book at time zero.
                if (id in library) progress.getLong("audioModified:$id", 0) else null
            }.mapNotNull { record -> PlaybackService.moveIfLoaded(record.key, record.position)?.let { record.copy(position = it) } }
            if (records.isNotEmpty()) {
                val edit = progress.edit()
                records.forEach { record ->
                    Positions.updates(record, progress.getLong("lastOpened:${record.key}", 0)).forEach { (key, value) -> edit.putLong(key, value) }
                }
                // Written to memory before this returns and to disk after, where a blocking commit
                // would stall the thread that draws. Nothing else runs here in between either way.
                edit.apply()
            }
            records
        }
        if (applied.isNotEmpty()) revision.value++
    }

    /** What this device has to offer for one book, or nothing if it has never written a position. */
    private fun saved(id: String) = Positions.offer(id, progress.getLong("audio:$id", 0),
        progress.getLong("audioDuration:$id", 0), progress.getLong("audioModified:$id", 0))

    /**
     * Once for this build, and settled with the flag in the same commit so a failure simply leaves
     * it for the next exchange: every position already on the device is given the stamp its save
     * never wrote. Afterwards nothing reads the opening time again, at either end of an exchange.
     */
    private fun backfill() {
        if (progress.getBoolean("audioModifiedBackfilled", false)) return
        val edit = progress.edit().putBoolean("audioModifiedBackfilled", true)
        LibraryStore(context).all().forEach { title ->
            Positions.backfill(progress.getLong("audio:${title.id}", 0), progress.getLong("audioModified:${title.id}", 0),
                progress.getLong("lastOpened:${title.id}", 0))?.let { edit.putLong("audioModified:${title.id}", it) }
        }
        edit.commit()
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
