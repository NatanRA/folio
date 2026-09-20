package com.natan.mediaportal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagePicker(title: String, total: Int, current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val compact = LocalConfiguration.current.screenHeightDp < 700
    PortalTheme(styleWindow = false) {
        val colors = MaterialTheme.colorScheme
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.82f).padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pages", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(title, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, contentDescription = "Close page picker") }
                }
                var entry by remember { mutableStateOf("") }
                val target = entry.toIntOrNull()?.takeIf { it in 1..total }
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(entry, { value -> if (value.length <= 9 && value.all(Char::isDigit)) entry = value },
                        label = { Text("Page number") }, placeholder = { Text("1–$total") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { target?.let(onSelect) }),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f))
                    Button(onClick = { target?.let(onSelect) }, enabled = target != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Go") }
                }
                Text("Page $current of $total", color = colors.primary, modifier = Modifier.padding(bottom = 12.dp))
                LazyVerticalGrid(columns = GridCells.Adaptive(72.dp), state = rememberLazyGridState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0)),
                    modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(total, key = { it }) { index ->
                        val page = index + 1
                        val selected = page == current
                        Surface(onClick = { onSelect(page) }, shape = RoundedCornerShape(12.dp),
                            color = if (selected) colors.primary else colors.surfaceContainerHigh,
                            contentColor = if (selected) colors.onPrimary else colors.onSurface,
                            border = if (selected) null else BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.fillMaxWidth().aspectRatio(if (compact) 1.05f else .8f).semantics { contentDescription = if (selected) "Page $page, current page" else "Go to page $page" }) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Text(if (selected) "HERE" else "PAGE", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                Text(page.toString(), fontSize = 26.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                Text("Estimated pages; screen breaks may differ.", color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 20.dp))
            }
        }
    }
}
