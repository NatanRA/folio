package com.natan.mediaportal

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepTimer(prefs: SharedPreferences) {
    var open by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("Sleep timer") }
    LaunchedEffect(prefs) {
        while (true) {
            val left = prefs.getLong("sleep:deadline", 0) - System.currentTimeMillis()
            label = if (prefs.getBoolean("sleep:chapter", false)) "End of chapter" else if (left > 0) "Sleep · ${(left + 59999) / 60000} min" else "Sleep timer"
            delay(500)
        }
    }
    TextButton(onClick = { open = true }) { Icon(Icons.Outlined.Bedtime, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label) }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.headlineSmall)
            Text("Stops playback even when your phone is locked.", Modifier.padding(vertical = 12.dp))
            listOf(15, 30, 45, 60).forEach { minutes ->
                TextButton(onClick = { prefs.edit().putLong("sleep:deadline", System.currentTimeMillis() + minutes * 60000L).putBoolean("sleep:chapter", false).apply(); open = false }, modifier = Modifier.fillMaxWidth()) { Text("$minutes minutes") }
            }
            TextButton(onClick = { prefs.edit().remove("sleep:deadline").putBoolean("sleep:chapter", true).apply(); open = false }, modifier = Modifier.fillMaxWidth()) { Text("End of chapter") }
            TextButton(onClick = { prefs.edit().remove("sleep:deadline").remove("sleep:chapter").apply(); open = false }, modifier = Modifier.fillMaxWidth()) { Text("Turn off timer") }
        }
    }
}
