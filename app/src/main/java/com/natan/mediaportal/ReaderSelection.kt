package com.natan.mediaportal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

internal enum class SelectionAction(val label: String, val icon: ImageVector) {
    Highlight("Highlight", Icons.Outlined.BorderColor), Note("Add note", Icons.Outlined.EditNote),
    Copy("Copy", Icons.Outlined.ContentCopy), Define("Define", Icons.Outlined.MenuBook),
    Save("Save word", Icons.Outlined.BookmarkAdd)
}

/** Reader paper can be light while the app's contextual panels use a dark palette. */
@Composable
internal fun ReaderSheetSystemBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
    val light = MaterialTheme.colorScheme.surfaceContainerLow.luminance() > .5f
    SideEffect { window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view).isAppearanceLightNavigationBars = light } }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReaderSelectionToolbar(bounds: android.graphics.RectF, act: (SelectionAction?) -> Unit) {
    val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
    val provider = object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val x = (bounds.centerX().toInt() - popupContentSize.width / 2).coerceIn(margin, maxOf(margin, windowSize.width - popupContentSize.width - margin))
            val above = bounds.top.toInt() - popupContentSize.height - margin
            val y = if (above >= margin) above else bounds.bottom.toInt() + margin
            return IntOffset(x, y.coerceIn(margin, maxOf(margin, windowSize.height - popupContentSize.height - margin)))
        }
    }
    Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
        HorizontalFloatingToolbar(expanded = true, modifier = Modifier.testTag("reader-selection-toolbar")
            .widthIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 24).dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { act(SelectionAction.Highlight) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Highlight") }
                FilledTonalButton(onClick = { act(SelectionAction.Define) }, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = AccentTextTint), contentColor = MaterialTheme.colorScheme.primary)) {
                    Icon(Icons.Outlined.MenuBook, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Define")
                }
                IconButton(onClick = { act(null) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreHoriz, "More selection actions") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionActionsSheet(quote: String, dismiss: () -> Unit, act: (SelectionAction) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ReaderSheetSystemBars()
        Column(Modifier.fillMaxWidth().heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .8f).dp)
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Selected text", style = MaterialTheme.typography.headlineSmall)
            Text(quote, Modifier.padding(vertical = 16.dp), maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionAction.entries.forEach { action ->
                TextButton(onClick = { act(action) }, enabled = action != SelectionAction.Save || quote.length <= 200, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(action.icon, null); Spacer(Modifier.width(16.dp)); Text(action.label, Modifier.weight(1f))
                }
            }
        }
    }
}
