package app.folio.library

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class KoAccount(val url: String, val user: String, val key: String) {
    override fun toString() = "KoAccount(credentials=<redacted>)"
}
data class KoPosition(val progress: String, val percentage: Double, val device: String) {
    override fun toString() = "KoPosition(percentage=$percentage)"
}

object KoProtocol {
    fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    fun endpoint(value: String): String {
        val uri = URI(value.trim().trimEnd('/'))
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Enter an HTTPS sync server address." }
        require(uri.path.split('/').none { it == ".." }) { "Invalid server path." }
        return uri.toString()
    }
    // KOReader util.partialMD5: LuaJIT's negative shift wraps to 30, so the first offset is zero.
    // This is deliberately not a whole-file hash or a filename hash.
    fun document(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        RandomAccessFile(file, "r").use { input ->
            val buffer = ByteArray(1024)
            for (offset in listOf(0L) + (0..10).map { 1024L shl (2 * it) }) {
                if (offset >= input.length()) break
                input.seek(offset)
                val size = minOf(1024L, input.length() - offset).toInt()
                input.readFully(buffer, 0, size)
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun parse(json: JSONObject): KoPosition? {
        if (!json.has("progress") || json.isNull("progress")) return null
        val percent = json.getDouble("percentage")
        require(percent.isFinite() && percent in 0.0..1.0) { "The server returned an invalid reading position." }
        val progress = json.getString("progress")
        require(progress.isNotBlank() && progress.length <= 16384) { "The server returned an invalid bookmark." }
        return KoPosition(progress, percent, json.optString("device", "Another reader").take(80))
    }
}

/** Separate from BookLore credentials; only the password-derived KOReader key is retained. */
class KoSettings(context: Context) {
    private val prefs = context.getSharedPreferences("koreader-sync", Context.MODE_PRIVATE)
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("koreader-sync", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("koreader-sync", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun account(): KoAccount? = runCatching {
        val encrypted = prefs.getString("secret", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        KoAccount(prefs.getString("url", "")!!, prefs.getString("user", "")!!, String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
    }.getOrNull()
    fun save(account: KoAccount) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        check(prefs.edit().putString("url", account.url).putString("user", account.user)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("secret", Base64.encodeToString(cipher.doFinal(account.key.toByteArray()), Base64.NO_WRAP))
            .putBoolean("bridge", false).commit())
    }
    fun disconnect() { check(prefs.edit().clear().commit()) }
    var bridgeInstalled: Boolean
        get() = prefs.getBoolean("bridge", false)
        set(value) { prefs.edit().putBoolean("bridge", value).apply() }
    fun deviceId(): String = prefs.getString("device", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).commit() }
}

class KoSync(private val connect: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection }) {
    private suspend fun request(account: KoAccount, path: String, method: String = "GET", body: JSONObject? = null, missing: Boolean = false): JSONObject? = withContext(Dispatchers.IO) {
        require(account.user.isNotBlank() && account.user.none { it == '\r' || it == '\n' }) { "Enter a valid sync username." }
        val connection = connect(KoProtocol.endpoint(account.url) + path)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000; connection.readTimeout = 10000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/vnd.koreader.v1+json")
            connection.setRequestProperty("x-auth-user", account.user)
            connection.setRequestProperty("x-auth-key", account.key)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code == 404 && missing) return@withContext null
            check(code != 401 && code != 403) { "Sync sign-in failed. Use your KOReader sync login, not your OPDS login." }
            check(code in 200..299) { "Sync server returned HTTP $code. Check its address and try again." }
            val bytes = connection.inputStream.use { it.readBytesBounded(65536) }
            if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    suspend fun login(account: KoAccount) { request(account, "/users/auth") }
    suspend fun pull(account: KoAccount, document: String): KoPosition? {
        require(document.matches(Regex("[0-9a-f]{32}")))
        return request(account, "/syncs/progress/$document", missing = true)?.let {
            require(!it.has("document") || it.optString("document") == document) { "The sync server returned a different book’s position." }
            KoProtocol.parse(it)
        }
    }
    suspend fun push(account: KoAccount, document: String, percentage: Double, device: String) {
        require(document.matches(Regex("[0-9a-f]{32}")) && percentage.isFinite() && percentage in 0.0..1.0)
        request(account, "/syncs/progress", "PUT", JSONObject().put("document", document)
            .put("progress", "nmp-percent-v1:$percentage").put("percentage", percentage)
            .put("device", "Folio").put("device_id", device))
    }
}
