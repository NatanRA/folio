package com.natan.mediaportal

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesTransferSheet(id: String, dismiss: () -> Unit, updated: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }
    fun run(action: suspend () -> Unit) { scope.launch {
        busy = true; message = null
        try { withContext(Dispatchers.IO) { action() }; updated(); message = "Done. Your notes are saved." }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "Couldn’t transfer notes." }
        finally { busy = false }
    } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let {
        run { val text = NotesExchange(context, id).export(); requireNotNull(context.contentResolver.openOutputStream(it)).use { stream -> stream.write(text.toByteArray()) } }
    } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
        run { val text = requireNotNull(context.contentResolver.openInputStream(it)).use { stream -> stream.readBytesBounded(8_000_000).toString(Charsets.UTF_8) }; NotesExchange(context, id).export(); NotesExchange(context, id).merge(text) }
    } }
    ModalBottomSheet(onDismissRequest = { if (!busy) dismiss() }) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sync & export", style = MaterialTheme.typography.headlineSmall)
            Text("Includes this book’s highlights, notes, bookmarks and saved words. Import merges changes; it does not replace the entire collection.")
            Button(onClick = { run { NotesExchange(context, id).sync() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Sync notes") }
            OutlinedButton(onClick = { export.launch("book-notes.json") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Export notes") }
            OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Import notes") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it) }
        }
    }
}
