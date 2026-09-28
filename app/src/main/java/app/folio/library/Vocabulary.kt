package app.folio.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.ui.Alignment
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DictionarySheet(word: String, dismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember(word) { mutableStateOf(word.take(80)) }
    var lookup by remember(word) { mutableStateOf(query) }
    var meanings by remember { mutableStateOf<List<WordMeaning>?>(null) }
    var failure by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(lookup, retry) {
        meanings = null; failure = false
        try { meanings = OfflineDictionary.lookup(context, lookup) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = true; meanings = emptyList() }
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ReaderSheetSystemBars()
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        fun find() { keyboard?.hide(); focus.clearFocus(); lookup = query.trim() }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .78f).dp), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("Dictionary", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Close dictionary") }
                }
                Text("English · Available offline", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it.take(80) }, label = { Text("Word or phrase") }, singleLine = true,
                        modifier = Modifier.weight(1f), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { find() }))
                    FilledTonalButton(onClick = { find() }, enabled = query.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Find") }
                }
            }
            if (meanings == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Finding definition…", Modifier.padding(top = 12.dp)) }
            else if (failure) item {
                Text("Couldn’t open the dictionary.")
                TextButton(onClick = { retry++ }) { Text("Try again") }
            }
            else if (meanings!!.isEmpty()) item {
                Text("No definition found", style = MaterialTheme.typography.titleLarge)
                Text("Try the base form or a single English word. Names and newer words may not be included.", Modifier.padding(top = 8.dp))
            }
            else meanings!!.groupBy { it.word to it.partOfSpeech }.forEach { (heading, definitions) ->
                item {
                    Text(heading.first, style = MaterialTheme.typography.headlineMedium)
                    Text(heading.second, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                items(definitions) { meaning ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${definitions.indexOf(meaning) + 1}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        Column {
                            Text(meaning.definition, style = MaterialTheme.typography.bodyLarge)
                            meaning.examples.forEach { Text("“$it”", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            item { HorizontalDivider(); Text("WordNet® 3.0 · Copyright 2006 Princeton University", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VocabularySheet(words: List<ReadingNote>, dismiss: () -> Unit, define: (String) -> Unit, remove: (ReadingNote) -> Unit) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("Saved words", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(query, { query = it }, label = { Text("Search words") }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
            LazyColumn(Modifier.heightIn(max = 440.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (words.isEmpty()) item { Text("Select a word in your book and choose Save word.", Modifier.padding(vertical = 24.dp)) }
                items(words.filter { it.quote.contains(query, true) }, key = { it.id }) { word ->
                    ListItem(headlineContent = { Text(word.quote) }, supportingContent = { Text(word.locator.text.before.orEmpty().takeLast(80) + word.quote + word.locator.text.after.orEmpty().take(80), maxLines = 3) })
                    Row { TextButton(onClick = { define(word.quote) }) { Text("Define") }; TextButton(onClick = { remove(word) }) { Text("Remove") } }
                    HorizontalDivider()
                }
            }
        }
    }
}
