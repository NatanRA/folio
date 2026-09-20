package com.natan.mediaportal

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.JsonClient
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TelegramFile(val chat: Long, val message: Long, val file: Int, val name: String, val size: Long,
    val thumbnail: Int = 0, val title: String = "", val performer: String = "") {
    val audio get() = name.substringAfterLast('.').lowercase() in setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "wav")
    val key get() = "$chat:$message"
    companion object {
        fun parse(message: JSONObject): TelegramFile? {
            val content = message.optJSONObject("content") ?: return null
            val attachment = when (content.optString("@type")) {
                "messageDocument" -> content.optJSONObject("document")
                "messageAudio" -> content.optJSONObject("audio")
                else -> null
            } ?: return null
            val name = attachment.optString("file_name")
            if (name.substringAfterLast('.').lowercase() !in setOf("epub", "pdf", "cbz", "mp3", "m4a", "m4b", "aac", "flac", "ogg", "wav")) return null
            val file = attachment.optJSONObject("document") ?: attachment.optJSONObject("audio") ?: return null
            val thumbnail = attachment.optJSONObject("album_cover_thumbnail") ?: attachment.optJSONObject("thumbnail")
            return TelegramFile(message.getLong("chat_id"), message.getLong("id"), file.getInt("id"), name, file.optLong("size").coerceAtLeast(file.optLong("expected_size")),
                thumbnail?.optJSONObject("file")?.optInt("id") ?: 0, attachment.optString("title"), attachment.optString("performer"))
        }
    }
}

/** A TDLib error. [detail] is TDLib's own message; [message] is what the UI shows. */
class TelegramError(val code: Int, val detail: String) : Exception(friendly(code, detail)) {
    /** The selected chat can no longer be read: it was left, deleted, or TDLib has not loaded it after a restart. */
    val chatUnavailable get() = detail.contains("Chat not found") || detail.contains("CHANNEL_PRIVATE") || detail.contains("Can't access the chat") || detail.contains("CHAT_ID_INVALID")
    companion object {
        fun friendly(code: Int, detail: String) = when {
            detail.contains("PHONE_NUMBER_INVALID") -> "Check your phone number, including its country code."
            detail.contains("PHONE_CODE_INVALID") || detail.contains("PHONE_CODE_EXPIRED") -> "That code is invalid or expired. Please try again."
            detail.contains("PASSWORD_HASH_INVALID") -> "That two-step verification password is incorrect."
            detail.contains("FLOOD") || detail.contains("Too Many") -> "Telegram has asked you to wait before trying again."
            detail.contains("Chat not found") || detail.contains("CHAT_ID_INVALID") -> "That chat is no longer available. Choose another chat or use Saved Messages."
            detail.contains("CHANNEL_PRIVATE") || detail.contains("Can't access the chat") -> "You no longer have access to that chat. Choose another chat or use Saved Messages."
            detail.contains("Message not found") || detail.contains("MESSAGE_ID_INVALID") -> "This file is no longer in Telegram."
            detail.contains("Timeout expired") || detail.contains("Network", ignoreCase = true) || code == 502 -> "Telegram couldn’t reach its servers. Check your connection and try again."
            else -> "Telegram couldn’t complete this request ($detail). Try again."
        }
    }
}

/** One TDLib receiver per process. No server relay and no outgoing chat messages. */
class TelegramLibrary private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: TelegramLibrary? = null
        fun get(context: Context): TelegramLibrary = instance ?: synchronized(this) {
            instance ?: TelegramLibrary(context.applicationContext).also { instance = it }
        }
    }
    val auth = MutableStateFlow("disconnected")
    val error = MutableStateFlow<String?>(null)
    val entries = MutableStateFlow<List<TelegramFile>>(emptyList())
    val chats = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    val activity = MutableStateFlow<String?>(null)
    val progress = MutableStateFlow(0f)
    val more = MutableStateFlow(false)
    /** True while the selected chat's history is being scanned, including the automatic scan after sign-in. */
    val scanning = MutableStateFlow(false)
    val revision = MutableStateFlow(0)
    val selectedName = MutableStateFlow("Saved Messages")
    private val rangeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val files = ConcurrentHashMap<Int, JSONObject>()
    private var client = 0
    private var account = 0L
    private var chat = 0L
    private var cursor = 0L
    private var download: Job? = null
    private var refreshJob: Job? = null
    private var artworkJob: Job? = null
    private val artworkAttempted = ConcurrentHashMap.newKeySet<String>()
    private val root = File(context.noBackupFilesDir, "telegram").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("telegram-library", Context.MODE_PRIVATE)
    /** True once sign-in can start, whether the identity ships with the build or was entered here. */
    val configured get() = File(root, "app-identity").isFile || bundledIdentity() != null
    /**
     * Telegram issues an api_id and api_hash to the developer of a client, not to each person using
     * it, so they ship as an untracked `assets/telegram-private.json`. An identity entered on this
     * device is encrypted into `app-identity` and overrides the bundled one.
     */
    /** Reported to Telegram and shown in the reader's list of active sessions, so it has to be
     *  this build rather than a number written down once and left behind. */
    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0"
    private fun bundledIdentity(): JSONObject? = runCatching {
        context.assets.open("telegram-private.json").use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
    }.getOrNull()?.takeIf { it.optInt("api_id") > 0 && it.optString("api_hash").matches(Regex("[a-fA-F0-9]{32}")) }
    private fun identityKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("telegram-app-identity", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("telegram-app-identity", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun configure(id: String, hash: String) {
        require(id.toIntOrNull()?.let { it > 0 } == true && hash.matches(Regex("[a-fA-F0-9]{32}"))) { "Enter the API ID and hash from my.telegram.org/apps." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, identityKey()) }
        val data = JSONObject().put("api_id", id.toInt()).put("api_hash", hash).toString().toByteArray()
        File(root, "app-identity").writeBytes(cipher.iv + cipher.doFinal(data))
    }
    private fun identity(): JSONObject {
        val file = File(root, "app-identity")
        if (!file.isFile) return bundledIdentity()
            ?: throw IllegalStateException("This build has no Telegram application identity. Add one under Telegram in Server settings.")
        val data = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, identityKey(), GCMParameterSpec(128, data.copyOfRange(0, 12))) }
        return JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
    }

    @Synchronized fun start() {
        if (client != 0) return
        error.value = null
        try {
            JsonClient.execute("{\"@type\":\"setLogVerbosityLevel\",\"new_verbosity_level\":0}")
            client = JsonClient.createClientId()
            auth.value = "starting"
            scope.launch {
                while (isActive) {
                    val text = JsonClient.receive(1.0) ?: continue
                    val event = JSONObject(text)
                    pending.remove(event.optString("@extra"))?.complete(event)
                    when (event.optString("@type")) {
                        "updateAuthorizationState" -> {
                            val state = event.getJSONObject("authorization_state")
                            authorization(state)
                            if (state.optString("@type") == "authorizationStateClosed") break
                        }
                        "updateFile" -> event.getJSONObject("file").let { files[it.getInt("id")] = it }
                        "updateNewMessage" -> if (event.optJSONObject("message")?.optLong("chat_id") == chat && activity.value == null) {
                            refreshJob?.cancel(); refreshJob = scope.launch { delay(1000); runCatching { refresh() } }
                        }
                    }
                }
            }
            scope.launch { runCatching { request("getAuthorizationState") }.onFailure { error.value = "Couldn’t start Telegram. Try again." } }
        } catch (_: UnsatisfiedLinkError) { error.value = "Telegram isn’t included in this build."; auth.value = "disconnected" }
    }
    private suspend fun request(type: String, vararg fields: Pair<String, Any>): JSONObject {
        val id = UUID.randomUUID().toString()
        val wait = CompletableDeferred<JSONObject>()
        pending[id] = wait
        try {
            val body = JSONObject().put("@type", type).put("@extra", id)
            fields.forEach { body.put(it.first, it.second) }
            JsonClient.send(client, body.toString())
            val response = try { withTimeout(60_000) { wait.await() } }
                catch (_: TimeoutCancellationException) { throw IllegalStateException("Telegram didn’t respond. Check your connection and try again.") }
            if (response.optString("@type") == "error") throw TelegramError(response.optInt("code"), response.optString("message"))
            return response
        } finally { pending.remove(id) }
    }
    private fun databaseKey(): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (store.getKey("telegram-db", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("telegram-db", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        val sealed = File(root, "database-key")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val bytes = if (sealed.exists()) {
            val data = sealed.readBytes(); cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12))); cipher.doFinal(data.copyOfRange(12, data.size))
        } else {
            val data = ByteArray(32).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key); sealed.writeBytes(cipher.iv + cipher.doFinal(data)); data
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    private fun authorization(state: JSONObject) {
        val type = state.optString("@type")
        auth.value = type
        if (type == "authorizationStateWaitTdlibParameters") scope.launch {
            try {
                val config = identity()
                request("setTdlibParameters", "database_directory" to File(root, "database").absolutePath,
                    "files_directory" to File(root, "files").absolutePath, "database_encryption_key" to databaseKey(),
                    "use_file_database" to true, "use_chat_info_database" to true, "use_message_database" to false,
                    "use_secret_chats" to false, "api_id" to config.getInt("api_id"), "api_hash" to config.getString("api_hash"),
                    "system_language_code" to "en", "device_model" to "Android", "system_version" to android.os.Build.VERSION.RELEASE, "application_version" to appVersion())
            } catch (_: Exception) { error.value = "Couldn’t initialise the secure Telegram connection." }
        }
        if (type == "authorizationStateReady") scope.launch {
            try { account = request("getMe").getLong("id"); val saved = prefs.getLong("selected:$account", 0); if (saved != 0L) open(saved) else selectSaved() }
            catch (e: Exception) { error.value = e.message }
        }
        if (type == "authorizationStateClosed") { client = 0; auth.value = "disconnected" }
    }
    suspend fun signIn(value: String) {
        error.value = null
        when (auth.value) {
            "authorizationStateWaitPhoneNumber" -> request("setAuthenticationPhoneNumber", "phone_number" to value)
            "authorizationStateWaitCode" -> request("checkAuthenticationCode", "code" to value)
            "authorizationStateWaitPassword" -> request("checkAuthenticationPassword", "password" to value)
            "authorizationStateWaitEmailAddress" -> request("setAuthenticationEmailAddress", "email_address" to value)
            "authorizationStateWaitEmailCode" -> request("checkAuthenticationEmailCode", "code" to JSONObject().put("@type", "emailAddressAuthenticationCode").put("code", value))
            else -> throw IllegalStateException("Telegram requires another verification step. Complete it in your Telegram app and retry.")
        }
    }
    suspend fun selectSaved() {
        val saved = request("createPrivateChat", "user_id" to account, "force" to false)
        select(saved.getLong("id"))
    }
    suspend fun loadChats() {
        runCatching { request("loadChats", "limit" to 100) }
        val ids = request("getChats", "limit" to 100).getJSONArray("chat_ids")
        chats.value = (0 until ids.length()).map { ids.getLong(it) }.map { id -> id to request("getChat", "chat_id" to id).optString("title") }
    }
    /** Selects [id]; if that chat can no longer be read, forgets it and falls back to Saved Messages instead of leaving nothing selected. */
    suspend fun open(id: Long) {
        try { select(id) } catch (e: TelegramError) {
            if (!e.chatUnavailable) throw e
            prefs.edit().remove("selected:$account").apply()
            selectSaved()
            error.value = e.message
        }
    }
    private suspend fun chatTitle(id: Long): String {
        // Without a message database TDLib forgets the chat list between launches; loading it makes a remembered chat resolvable again.
        val chat = try { request("getChat", "chat_id" to id) } catch (e: TelegramError) {
            if (!e.chatUnavailable) throw e
            runCatching { request("loadChats", "limit" to 100) }
            request("getChat", "chat_id" to id)
        }
        return chat.optString("title").ifBlank { "Library" }
    }
    suspend fun select(id: Long) {
        artworkJob?.cancelAndJoin(); artworkAttempted.clear()
        chat = id; cursor = 0; entries.value = emptyList(); more.value = false; scanning.value = true
        try {
            selectedName.value = chatTitle(id)
            prefs.edit().putLong("selected:$account", id).apply()
            loadMore()
            var previous = 0L
            while (chat == id && more.value && cursor != previous) { previous = cursor; loadMore() }
        } finally { if (chat == id) scanning.value = false }
    }
    suspend fun refresh() { if (chat == 0L) selectSaved() else open(chat) }
    suspend fun loadMore() {
        if (chat == 0L) return
        val selected = chat
        val messages = request("getChatHistory", "chat_id" to selected, "from_message_id" to cursor, "offset" to 0, "limit" to 100, "only_local" to false).getJSONArray("messages")
        if (chat != selected) return
        more.value = messages.length() > 0
        if (messages.length() > 0) cursor = messages.getJSONObject(messages.length() - 1).getLong("id")
        entries.value = (entries.value + (0 until messages.length()).mapNotNull { TelegramFile.parse(messages.getJSONObject(it)) }).distinctBy { it.key }
        val store = LibraryStore(context)
        val catalog = store.all().toMutableList()
        entries.value.filter { it.audio }.forEach { entry ->
            val source = "$account:${entry.key}"
            if (catalog.none { it.telegramSource == source }) catalog.add(Title("tg-$account-${entry.chat}-${entry.message}", entry.title.ifBlank { entry.name.substringBeforeLast('.').replace('_', ' ') }, author = entry.performer, audio = "telegram://$account/${entry.chat}/${entry.message}/${Uri.encode(entry.name)}", telegramSource = source, addedAt = System.currentTimeMillis()))
        }
        store.save(catalog); revision.value++
        loadArtwork()
    }
    @Synchronized private fun loadArtwork() {
        if (artworkJob?.isActive == true) return
        val owner = account
        artworkJob = scope.launch {
            while (isActive) {
                val entry = entries.value.firstOrNull { it.audio && it.key !in artworkAttempted } ?: break
                while (activity.value != null) delay(500)
                artworkAttempted.add(entry.key)
                val title = LibraryStore(context).all().find { it.telegramSource == "$owner:${entry.key}" && it.streaming } ?: continue
                val destination = File(context.filesDir, "${title.id}.jpg")
                if (destination.exists() || File(context.filesDir, "${title.id}-custom.jpg").exists()) continue
                try {
                    withTimeout(25_000) {
                        if (entry.thumbnail != 0) try {
                            val info = request("getFile", "file_id" to entry.thumbnail)
                            val size = maxOf(info.optLong("size"), info.optLong("expected_size"))
                            if (size in 1..(2 * 1024 * 1024)) {
                                val thumb = entry.copy(file = entry.thumbnail, size = size)
                                val bytes = java.io.ByteArrayOutputStream()
                                while (bytes.size() < size) bytes.write(readRange(thumb, bytes.size().toLong(), minOf(256 * 1024, (size - bytes.size()).toInt()), true))
                                runCatching { Artwork.fromBytes(bytes.toByteArray(), destination) }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* Fall back to the embedded cover. */ }
                        if (!destination.exists()) Artwork.fromStream(entry.size, destination) { offset, length -> readRange(entry, offset, length, true) }
                    }
                    if (destination.exists()) revision.value++
                } catch (_: TimeoutCancellationException) { }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Retry missing artwork on refresh. */ }
            }
        }
        artworkJob?.invokeOnCompletion { cause ->
            if (cause == null && entries.value.any { it.audio && it.key !in artworkAttempted }) loadArtwork()
        }
    }
    fun downloaded(entry: TelegramFile): Boolean = LibraryStore(context).all().any { it.telegramSource == "$account:${entry.key}" && it.isLocal }
    suspend fun resolve(uri: Uri): TelegramFile {
        require(uri.scheme == "telegram" && uri.pathSegments.size >= 2)
        start()
        withTimeout(20_000) { auth.first { it == "authorizationStateReady" } }
        val me = request("getMe").getLong("id")
        require(uri.host == me.toString()) { "Sign in to the Telegram account that owns this book." }
        return TelegramFile.parse(request("getMessage", "chat_id" to uri.pathSegments[0].toLong(), "message_id" to uri.pathSegments[1].toLong()))
            ?: kotlin.error("This audiobook is no longer available in Telegram.")
    }
    suspend fun readRange(entry: TelegramFile, offset: Long, length: Int, background: Boolean = false): ByteArray = rangeLock.withLock {
        require(offset >= 0 && length > 0 && offset < entry.size)
        require(activity.value == null) { "Wait for the offline download to finish before streaming." }
        withTimeout(30_000) {
            var available = request("getFileDownloadedPrefixSize", "file_id" to entry.file, "offset" to offset).optLong("size")
            if (available == 0L) {
                request("downloadFile", "file_id" to entry.file, "priority" to if (background) 1 else 32, "offset" to offset, "limit" to minOf(if (background) length.toLong() else 1024 * 1024L, entry.size - offset), "synchronous" to false)
                while (available == 0L) {
                    delay(100)
                    available = request("getFileDownloadedPrefixSize", "file_id" to entry.file, "offset" to offset).optLong("size")
                }
            }
            val local = request("getFile", "file_id" to entry.file).getJSONObject("local")
            val path = local.getString("path")
            val bytes = ByteArray(minOf(length.toLong(), available, entry.size - offset).toInt())
            RandomAccessFile(path, "r").use { it.seek(offset); it.readFully(bytes) }
            if (local.optLong("downloaded_size") > 64 * 1024 * 1024L) {
                request("cancelDownloadFile", "file_id" to entry.file, "only_if_pending" to false)
                request("deleteFile", "file_id" to entry.file)
            }
            bytes
        }
    }
    fun cancel() { download?.cancel() }
    fun download(entry: TelegramFile, finished: () -> Unit) {
        if (download?.isActive == true || downloaded(entry)) return
        download = scope.launch {
            var staging: File? = null
            try {
                require(root.usableSpace > entry.size * 3 + 64L * 1024 * 1024) { "Not enough device storage for this file." }
                activity.value = "Downloading ${entry.name}"; progress.value = 0f; error.value = null
                files[entry.file] = request("downloadFile", "file_id" to entry.file, "priority" to 16, "synchronous" to false)
                var local: JSONObject
                while (true) {
                    ensureActive()
                    local = files[entry.file]?.optJSONObject("local") ?: JSONObject()
                    progress.value = if (entry.size > 0) (local.optLong("downloaded_size").toFloat() / entry.size).coerceIn(0f, 1f) else 0f
                    if (local.optBoolean("is_downloading_completed")) break
                    delay(300)
                }
                activity.value = "Adding to your library…"
                val source = File(local.getString("path"))
                require(source.isFile && source.length() > 0) { "The download is incomplete. Please try again." }
                staging = File(context.cacheDir, "telegram-${UUID.randomUUID()}").apply { mkdirs() }
                val copy = File(staging, File(entry.name).name)
                source.copyTo(copy)
                val existing = LibraryStore(context).all().find { it.telegramSource == "$account:${entry.key}" }
                val title = LibraryStore(context).import(Uri.fromFile(copy), entry.audio, existing?.id, telegramSource = "$account:${entry.key}")
                check(prefs.edit().putString("$account:${entry.key}", title.id).commit())
                runCatching { request("deleteFile", "file_id" to entry.file) }
                revision.value++; withContext(Dispatchers.Main) { finished() }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { runCatching { request("cancelDownloadFile", "file_id" to entry.file, "only_if_pending" to false) } }
                throw e
            } catch (e: Exception) { error.value = e.message ?: "Download failed. Please try again." }
            finally { staging?.deleteRecursively(); activity.value = null }
        }
    }
    suspend fun disconnect() { artworkJob?.cancelAndJoin(); cancel(); request("logOut"); entries.value = emptyList(); chats.value = emptyList(); account = 0; chat = 0 }
    suspend fun close() { if (client != 0) request("close") }
}
