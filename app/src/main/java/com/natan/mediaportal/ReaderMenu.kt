package com.natan.mediaportal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderMenu(syncEnabled: () -> Boolean, sync: () -> Unit, pinnedInitially: Boolean,
    appearance: ReaderAppearance, changeAppearance: (ReaderAppearance) -> Unit, notes: () -> Unit,
    explore: (Int) -> Unit, words: () -> Unit, pin: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(pinnedInitially) }
    Box {
        FilledTonalIconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Menu, "Reader menu", Modifier.size(24.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.large, containerColor = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.width(264.dp)) {
            DropdownMenuItem(text = { Text("Sync") }, leadingIcon = { Icon(Icons.Outlined.Sync, null) },
                enabled = syncEnabled(), onClick = { expanded = false; sync() })
            DropdownMenuItem(text = { Text("Options") }, leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                onClick = { expanded = false; options = true })
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            DropdownMenuItem(text = { Text("Highlights & notes") }, leadingIcon = { Icon(Icons.Outlined.EditNote, null) },
                onClick = { expanded = false; notes() })
            listOf("Search book", "Chapters", "Bookmarks").forEachIndexed { index, text ->
                DropdownMenuItem(text = { Text(text) }, leadingIcon = {
                    Icon(listOf(Icons.Outlined.Search, Icons.Outlined.FormatListNumbered, Icons.Outlined.Bookmarks)[index], null)
                }, onClick = { expanded = false; explore(index) })
            }
            DropdownMenuItem(text = { Text("Saved words") }, leadingIcon = { Icon(Icons.Outlined.MenuBook, null) }, onClick = { expanded = false; words() })
        }
    }
    if (options) ModalBottomSheet(onDismissRequest = { options = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Reader options", style = MaterialTheme.typography.headlineSmall)
            Text("Saved for all books on this device", style = MaterialTheme.typography.bodyMedium)
            Text("Paper", style = MaterialTheme.typography.titleSmall)
            PaperPicker(appearance.paper) { changeAppearance(appearance.copy(paper = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to "Serif", true to "Sans serif").forEach { (sans, label) ->
                    FilterChip(appearance.sans == sans, { changeAppearance(appearance.copy(sans = sans)) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            AppearanceSlider("Text size", appearance.size, 80f..180f, { "${it.toInt()}%" }) { changeAppearance(appearance.copy(size = it)) }
            AppearanceSlider("Line spacing", appearance.spacing, 1.1f..2f, { String.format("%.2f", it) }) { changeAppearance(appearance.copy(spacing = it)) }
            AppearanceSlider("Margins", appearance.margins, .5f..2f, { String.format("%.1f", it) }) { changeAppearance(appearance.copy(margins = it)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Keep navigation buttons visible", Modifier.weight(1f))
                Switch(checked = pinned, onCheckedChange = { pinned = it; pin(it) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { changeAppearance(ReaderAppearance()) }) { Text("Reset appearance") }
                FilledTonalButton(onClick = { options = false }) { Text("Done") }
            }
        }
    }
}

/** Swatches that show each page colour with its ink, so a paper can be judged before it is picked. Matches iOS. */
@Composable
private fun PaperPicker(selected: ReaderPaper, select: (ReaderPaper) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tile = RoundedCornerShape(12.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReaderPaper.entries.forEach { paper ->
            val chosen = paper == selected
            // One button per paper; its visible text is decoration for the content description.
            Column(Modifier.weight(1f).clip(tile).selectable(chosen, role = Role.Button) { select(paper) }
                .semantics { contentDescription = paper.name },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(52.dp).background(paper.page, tile)
                    .border(if (chosen) 3.dp else 1.dp, if (chosen) colors.primary else colors.outlineVariant, tile),
                    contentAlignment = Alignment.Center) {
                    Text("Aa", color = paper.ink, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
                }
                Text(paper.name, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    color = if (chosen) colors.onSurface else colors.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

@Composable
private fun AppearanceSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: (Float) -> String, commit: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$label · ${display(draft)}", style = MaterialTheme.typography.titleSmall)
        Slider(draft, { draft = it }, valueRange = range, onValueChangeFinished = { commit(draft) }, modifier = Modifier.semantics { contentDescription = label })
    }
}

internal data class ReaderDialog(val title: String, val message: String? = null,
    val choices: List<Pair<String, () -> Unit>> = emptyList(), val about: (() -> Unit)? = null)

@Composable
internal fun ReaderSyncDialog(dialog: ReaderDialog, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, icon = { Icon(Icons.Outlined.Sync, null) },
        title = { Text(dialog.title) }, text = {
            Column {
                dialog.message?.let { Text(it) }
                dialog.choices.forEach { (label, action) ->
                    TextButton(onClick = { dismiss(); action() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(label, Modifier.fillMaxWidth())
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = dismiss) { Text(if (dialog.choices.isEmpty()) "OK" else "Cancel") } },
        dismissButton = { dialog.about?.let { action -> TextButton(onClick = { dismiss(); action() }) { Text("About") } } })
}
