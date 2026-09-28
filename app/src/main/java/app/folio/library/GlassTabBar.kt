package app.folio.library

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.roundToInt

data class TabItem(val label: String, val icon: ImageVector)

/**
 * A floating capsule tab bar in the style of the iOS one: frosted glass over the page,
 * an accent pill that glides to the selected tab, and press-and-slide selection where the
 * pill follows the finger and tabs switch as it passes them.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun GlassTabBar(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, hazeState: HazeState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var width by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var fingerX by remember { mutableFloatStateOf(0f) }
    val itemWidth = if (items.isEmpty() || width == 0) 0f else width.toFloat() / items.size
    fun index(x: Float) = if (itemWidth == 0f) selected else (x / itemWidth).toInt().coerceIn(0, items.lastIndex)
    val target = if (dragging) (fingerX - itemWidth / 2).coerceIn(0f, (width - itemWidth).coerceAtLeast(0f)) else selected * itemWidth
    val pillX by animateFloatAsState(target, if (dragging) snap() else spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "Tab pill")
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier
        .padding(horizontal = 28.dp).fillMaxWidth().height(64.dp)
        .clip(CircleShape)
        .hazeEffect(hazeState, HazeMaterials.thin(MaterialTheme.colorScheme.surfaceContainer))
        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), CircleShape)
        .onSizeChanged { width = it.width }
        .testTag("tab-bar")
        .pointerInput(items.size) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var last = index(down.position.x)
                dragging = true; fingerX = down.position.x; onSelect(last)
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (change.pressed) {
                        fingerX = change.position.x
                        val next = index(fingerX)
                        if (next != last) { last = next; onSelect(next) }
                        change.consume()
                    }
                } while (event.changes.any { it.pressed })
                dragging = false
            }
        }
    ) {
        if (itemWidth > 0f) Box(Modifier.offset { IntOffset(pillX.roundToInt(), 0) }.width(with(density) { itemWidth.toDp() }).fillMaxHeight().padding(6.dp)
            .background(accent.copy(alpha = .2f), CircleShape))
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, item ->
                val active = i == selected
                Column(Modifier.weight(1f).fillMaxHeight().semantics(mergeDescendants = true) { role = Role.Tab; this.selected = active; onClick { onSelect(i); true } },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(item.icon, item.label, Modifier.size(24.dp), tint = if (active) accent else muted)
                    Text(item.label, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, color = if (active) accent else muted, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
