package com.natan.mediaportal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.readium.r2.shared.publication.*
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse

internal fun chapterLinks(links: List<Link>, depth: Int = 0): List<Pair<Link, Int>> =
    links.flatMap { listOf(it to depth) + chapterLinks(it.children, depth + 1) }

@OptIn(ExperimentalMaterial3Api::class, org.readium.r2.shared.ExperimentalReadiumApi::class)
@Composable
internal fun ReaderNavigation(publication: Publication, initialTab: Int, bookmarks: List<ReadingNote>,
    current: () -> Locator, saveBookmarks: (List<ReadingNote>) -> Unit,
    jump: (Locator) -> Boolean, chapter: (Link) -> Boolean, dismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(initialTab) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Locator>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var capped by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        results = emptyList(); failure = null; capped = false; busy = false
        if (query.trim().length < 2) return@LaunchedEffect
        busy = true
        try {
            delay(300)
            withContext(Dispatchers.IO) {
                val iterator = publication.search(query.trim()) ?: error("Search isn’t available for this book.")
                try {
                    val found = mutableListOf<Locator>()
                    while (true) {
                        ensureActive()
                        val batch = iterator.next().getOrElse { error("Couldn’t search this book. Try again.") } ?: break
                        found += batch.locators
                        if (found.size >= 500) { capped = true; break }
                    }
                    ensureActive(); results = found.take(500)
                } finally { iterator.close() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message }
        finally { busy = false }
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Explore book", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Search", "Chapters", "Bookmarks").forEachIndexed { index, text ->
                    FilterChip(tab == index, { tab = index }, label = { Text(text) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            when (tab) {
                0 -> {
                    OutlinedTextField(query, { query = it.take(200) }, label = { Text("Search inside this book") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    Text(failure ?: when {
                        query.trim().length < 2 -> "Enter at least two characters."
                        busy -> "Searching…"
                        capped -> "First 500 matches. Refine your search for more specific results."
                        results.isEmpty() -> "No matches found."
                        else -> "${results.size} matches"
                    }, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(results) { _, result ->
                            Column(Modifier.fillMaxWidth().clickable { if (jump(result)) dismiss() }.padding(vertical = 16.dp)) {
                                Text(result.title ?: "Match", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text((result.text.before.orEmpty().takeLast(80) + result.text.highlight.orEmpty() + result.text.after.orEmpty().take(100)).replace(Regex("\\s+"), " "), maxLines = 4)
                            }; HorizontalDivider()
                        }
                    }
                }
                1 -> {
                    val links = remember(publication) { chapterLinks(publication.tableOfContents.ifEmpty { publication.readingOrder }) }
                    LazyColumn(Modifier.heightIn(max = 480.dp)) {
                        itemsIndexed(links) { index, (link, depth) ->
                            Text(link.title?.takeIf { it.isNotBlank() } ?: "Section ${index + 1}", Modifier.fillMaxWidth()
                                .clickable { if (chapter(link)) dismiss() }.padding(start = (depth.coerceAtMost(4) * 12).dp, top = 16.dp, bottom = 16.dp))
                            HorizontalDivider()
                        }
                    }
                }
                2 -> {
                    FilledTonalButton(onClick = {
                        val location = current()
                        if (bookmarks.none { it.locator == location }) saveBookmarks(bookmarks + ReadingNote(locator = location))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Bookmark this page") }
                    if (bookmarks.isEmpty()) Text("Save a place to return to later.", Modifier.padding(vertical = 24.dp))
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(bookmarks, key = { _, entry -> entry.id }) { _, entry ->
                            Row(Modifier.fillMaxWidth()) {
                                TextButton(onClick = { if (jump(entry.locator)) dismiss() }, modifier = Modifier.weight(1f)) {
                                    Text("${entry.locator.title ?: "Saved place"} · ${((entry.locator.locations.totalProgression ?: 0.0) * 100).toInt()}%", Modifier.fillMaxWidth())
                                }
                                TextButton(onClick = { saveBookmarks(bookmarks.filterNot { it.id == entry.id }) }) { Text("Remove") }
                            }; HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
