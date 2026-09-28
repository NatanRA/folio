package app.folio.library

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import java.util.UUID

internal data class ReadingNote(val id: String = UUID.randomUUID().toString(), val locator: Locator, val note: String = "") {
    val quote get() = locator.text.highlight.orEmpty()
}

/** A separate durable file owned by SharedPreferences, independent of book progress. */
internal class ReadingNotes(private val context: Context, private val bookId: String, private val collection: String = "reading-notes") {
    private val prefs = context.getSharedPreferences(collection, Context.MODE_PRIVATE)
    fun all(): List<ReadingNote> {
        val array = JSONArray(prefs.getString(bookId, "[]"))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ReadingNote(item.getString("id"), requireNotNull(Locator.fromJSON(item.getJSONObject("locator"))), item.optString("note"))
        }
    }
    fun save(notes: List<ReadingNote>, recordChanges: Boolean = true) = synchronized(NotesExchange.lock) {
        if (recordChanges) NotesExchange(context, bookId).record(collection, all(), notes)
        val array = JSONArray()
        notes.forEach { array.put(JSONObject().put("id", it.id).put("locator", it.locator.toJSON()).put("note", it.note)) }
        check(prefs.edit().putString(bookId, array.toString()).commit()) { "Could not save your notes. Please try again." }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingNotesSheet(notes: List<ReadingNote>, dismiss: () -> Unit, jump: (ReadingNote) -> Unit,
    edit: (ReadingNote) -> Unit, delete: (ReadingNote) -> Unit, exchange: () -> Unit = {}) {
    var query by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ReadingNote?>(null) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("Highlights & notes", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = exchange) { Text("Sync & export") }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(query, { query = it }, label = { Text("Search this book’s notes") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(16.dp))
            val matches = notes.filter { it.quote.contains(query, true) || it.note.contains(query, true) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (matches.isEmpty()) item { Text(if (notes.isEmpty()) "Long-press text in your book, then choose Highlight or Add note." else "No matching notes.", Modifier.padding(vertical = 24.dp)) }
                items(matches, key = { it.id }) { entry ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(entry.quote, style = MaterialTheme.typography.bodyLarge, maxLines = 5)
                        if (entry.note.isNotBlank()) Text(entry.note, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
                        Row {
                            TextButton(onClick = { jump(entry) }) { Text("Go to text") }
                            TextButton(onClick = { edit(entry) }) { Text("Edit note") }
                            TextButton(onClick = { deleting = entry }) { Text("Delete") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete highlight?") },
        text = { Text("Its attached note will also be removed.") },
        confirmButton = { TextButton(onClick = { delete(entry); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }) }
}

@Composable
internal fun ReadingNoteEditor(entry: ReadingNote, dismiss: () -> Unit, save: (ReadingNote) -> Unit) {
    var text by remember(entry.id) { mutableStateOf(entry.note) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Note") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(entry.quote, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(text, { text = it.take(10000) }, label = { Text("Your note") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
        }
    }, confirmButton = { TextButton(onClick = { save(entry.copy(note = text)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
