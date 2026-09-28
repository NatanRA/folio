package app.folio.library

import android.content.Context
import android.widget.Button
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import kotlin.math.abs

object KoResume {
    fun locator(positions: List<Locator>, percentage: Double): Locator {
        require(percentage.isFinite() && percentage in 0.0..1.0 && positions.isNotEmpty()) { "This book has no usable position map." }
        return positions.minBy { abs((it.locations.totalProgression ?: 0.0) - percentage) }
    }
}

class ReaderSync(private val activity: ReaderActivity, private val title: Title, private val button: Button,
                 private val navigator: EpubNavigatorFragment, private val positions: List<Locator>, private val client: KoSync = KoSync(), private val refreshFinished: () -> Unit) {
    private val prefs = activity.getSharedPreferences("progress", Context.MODE_PRIVATE)
    private val settings = KoSettings(activity)
    private var digest: String? = null
    private fun message(text: String) {
        activity.showSyncDialog(ReaderDialog("KOReader sync", message = text))
    }
    private fun work(block: suspend () -> Unit) {
        if (!button.isEnabled) return
        button.isEnabled = false
        activity.lifecycleScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: java.io.IOException) { message("Can’t reach the sync server. Your saved place is safe. Tap Sync to retry when you’re online.") }
            catch (e: Exception) { message(e.message ?: "Sync failed. Your saved place is unchanged.") }
            finally { button.isEnabled = true }
        }
    }
    fun show() {
        if (prefs.contains("ebookSyncBackup:${title.id}")) {
            activity.showSyncDialog(ReaderDialog("Reading position", choices = listOf(
                "Check other reader’s position" to { fetch() }, "Undo last received position" to { undoReceived() }
            )))
        } else fetch()
    }
    private fun undoReceived() {
        try {
            val saved = prefs.getString("ebookSyncBackup:${title.id}", null) ?: return
            val backup = JSONObject(saved)
            val target = Locator.fromJSON(backup.getJSONObject("locator"))
            if (target != null && navigator.go(target)) {
                val progress = ReadingProgress(prefs, title.id)
                progress.record(target.toJSON().toString(), target.locations.totalProgression ?: 0.0)
                progress.setFinished(backup.optBoolean("finished"))
                prefs.edit().remove("ebookSyncBackup:${title.id}").apply()
                refreshFinished()
            } else message("Couldn’t restore the previous position.")
        } catch (_: Exception) { message("Couldn’t restore the previous position.") }
    }
    private fun fetch() {
        val account = settings.account()
        if (account == null) { message("Connect your KOReader account under Server → KOReader progress first."); return }
        work {
            val document = digest ?: withContext(Dispatchers.IO) { KoProtocol.document(LibraryStore(activity).file(title.ebook!!)) }.also { digest = it }
            val remote = client.pull(account, document)
            val local = navigator.currentLocator.value
            val percentage = local.locations.totalProgression ?: 0.0
            val options = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()
            if (remote != null) {
                options += "Use other reader’s position (~${(remote.percentage * 100).toInt()}%)"
                actions += {
                    val target = KoResume.locator(positions, remote.percentage)
                    // Retain the exact original locator and completion status for an explicit undo.
                    val backup = JSONObject().put("locator", navigator.currentLocator.value.toJSON())
                        .put("finished", ReadingProgress(prefs, title.id).finished)
                    if (navigator.go(target)) {
                        prefs.edit().putString("ebookSyncBackup:${title.id}", backup.toString()).apply()
                        ReadingProgress(prefs, title.id).setFinished(false)
                        ReadingProgress(prefs, title.id).record(target.toJSON().toString(), target.locations.totalProgression ?: 0.0)
                        refreshFinished()
                    } else message("Couldn’t open that position. Your saved place is unchanged.")
                }
            }
            if (settings.bridgeInstalled) {
                options += "Send this position (~${(percentage * 100).toInt()}%)"
                actions += {
                    work {
                        // Recheck after the user has reviewed the choice; don't overwrite a changed remote silently.
                        check(settings.account() == account && settings.bridgeInstalled) { "Sync settings changed. Tap Sync again." }
                        check(client.pull(account, document) == remote) { "The other reader moved while this dialog was open. Tap Sync again to review its latest position." }
                        client.push(account, document, navigator.currentLocator.value.locations.totalProgression ?: 0.0, settings.deviceId())
                        message("Position sent. In KOReader, use Progress sync → Pull progress from other devices now.")
                    }
                }
            }
            val info = if (remote == null) "No shared position yet. Use the identical EPUB and Binary matching in KOReader." else "Resume positions are approximate between reading engines."
            val extra = if (settings.bridgeInstalled) "" else " Install the bridge and enable sending in Server settings to send your position."
            if (options.isEmpty()) message(info + extra)
            else activity.showSyncDialog(ReaderDialog("Sync position (approximate)", choices = options.zip(actions),
                about = { message(info + extra + " Completion status and audiobook progress stay separate.") }))
        }
    }
}
