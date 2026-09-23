package com.natan.mediaportal

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

internal data class Appearance(val mode: String = "Dark", val palette: String = "Aquamarine")
internal val LocalAppearance = staticCompositionLocalOf { Appearance() }
internal val LocalChangeAppearance = staticCompositionLocalOf<(Appearance) -> Unit> { {} }

/** The palettes with fixed colours, in the order the appearance sheet lists them. "Wallpaper" follows on Android 12+. */
internal val PortalPalettes = listOf("Aquamarine", "Blue", "Lilac", "Peach", "Sakura")

/** A palette's accent: a deep shade for light schemes and a pale one for dark schemes, both at least 4.5:1 on that scheme's surfaces. */
internal fun paletteAccent(palette: String, dark: Boolean): Color = when (palette) {
    "Blue" -> if (dark) Color(0xFFA8C7FA) else Color(0xFF245DB2)
    "Lilac" -> if (dark) Color(0xFFD0BCFF) else Color(0xFF6750A4)
    "Peach" -> if (dark) Color(0xFFFFB59A) else Color(0xFF98452A)
    "Sakura" -> if (dark) Color(0xFFF4A6BC) else Color(0xFFA43455)
    else -> if (dark) Color(0xFF58DCCB) else Color(0xFF006B60)
}

/** Strongest accent tint that accent-coloured text can sit on and stay 4.5:1 in light schemes. */
internal const val AccentTextTint = .12f

internal fun portalColorScheme(primary: Color, dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = primary, onPrimary = Color(0xFF101819), primaryContainer = Color(0xFF26343A), onPrimaryContainer = primary,
    secondary = primary, onSecondary = Color(0xFF101819), secondaryContainer = Color(0xFF26343A), onSecondaryContainer = Color(0xFFDCE8EC),
    background = Color(0xFF0B0D12), surface = Color(0xFF0B0D12), onBackground = Color(0xFFE4E5EB), onSurface = Color(0xFFE4E5EB),
    surfaceContainer = Color(0xFF171C25), surfaceContainerLow = Color(0xFF141820), surfaceContainerHigh = Color(0xFF222833), surfaceVariant = Color(0xFF222833), onSurfaceVariant = Color(0xFFB6BDC9)
) else lightColorScheme(
    primary = primary, onPrimary = Color.White, primaryContainer = Color(0xFFE0ECE8), onPrimaryContainer = primary,
    secondary = primary, secondaryContainer = Color(0xFFE0E8EC), onSecondaryContainer = Color(0xFF233139),
    background = Color(0xFFF7F9FC), surface = Color(0xFFF7F9FC), onBackground = Color(0xFF191C20), onSurface = Color(0xFF191C20),
    surfaceContainer = Color(0xFFEBEEF4), surfaceContainerLow = Color(0xFFF0F3F8), surfaceContainerHigh = Color(0xFFE2E6ED), surfaceVariant = Color(0xFFE2E6ED), onSurfaceVariant = Color(0xFF505964)
)

/**
 * The app's theme from the saved appearance. [darkOverride] replaces the light/dark mode and [accentOverride]
 * the palette's accent, for screens that follow something other than the app setting (see [ReaderTheme]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PortalTheme(styleWindow: Boolean = true, darkOverride: Boolean? = null, accentOverride: Color? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("appearance", 0) }
    var appearance by remember { mutableStateOf(Appearance(prefs.getString("mode", "Dark")!!, prefs.getString("palette", "Aquamarine")!!)) }
    val dark = darkOverride ?: when (appearance.mode) { "Light" -> false; "System" -> isSystemInDarkTheme(); else -> true }
    val scheme = when {
        accentOverride != null -> portalColorScheme(accentOverride, dark)
        appearance.palette == "Wallpaper" && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> portalColorScheme(paletteAccent(appearance.palette, dark), dark)
    }
    SideEffect {
        (context as? Activity)?.takeIf { styleWindow }?.let { activity ->
            activity.window.decorView.setBackgroundColor(scheme.background.toArgb())
            activity.window.statusBarColor = scheme.background.toArgb()
            activity.window.navigationBarColor = scheme.background.toArgb()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalAppearance provides appearance, LocalChangeAppearance provides { value ->
        appearance = value
        prefs.edit().putString("mode", value.mode).putString("palette", value.palette).apply()
    }) {
        MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(),
            shapes = Shapes(small = RoundedCornerShape(16.dp), medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp)), content = content)
    }
}

/**
 * The reader's controls and sheets follow its paper, not the app's mode: a light scheme on light papers and a dark
 * one on dark papers, with the paper's own accent when it has one and the app palette's otherwise.
 */
@Composable
internal fun ReaderTheme(paper: ReaderPaper, content: @Composable () -> Unit) =
    PortalTheme(styleWindow = false, darkOverride = paper.dark, accentOverride = paper.accent, content = content)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceSheet(onDismiss: () -> Unit) {
    val current = LocalAppearance.current
    val change = LocalChangeAppearance.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Appearance", style = MaterialTheme.typography.headlineMedium)
            Text("Theme", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Light", "Dark", "System").forEach { mode ->
                    FilterChip(selected = current.mode == mode, onClick = { change(current.copy(mode = mode)) }, label = { Text(mode) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Text("Colour palette", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (PortalPalettes + if (Build.VERSION.SDK_INT >= 31) listOf("Wallpaper") else emptyList()).forEach { palette ->
                    FilterChip(selected = current.palette == palette, onClick = { change(current.copy(palette = palette)) }, label = { Text(palette) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Text("Saved on this device. Book pages keep their reading style.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Done") }
        }
    }
}
