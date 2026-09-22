package com.natan.mediaportal

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The player's sleep timer: a moon that fills while a timer is set and counts the minutes of a timed one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepTimer(prefs: SharedPreferences) {
    var open by remember { mutableStateOf(false) }
    var minutesLeft by remember { mutableStateOf<Long?>(null) }
    var endOfChapter by remember { mutableStateOf(false) }
    LaunchedEffect(prefs) {
        while (true) {
            val left = prefs.getLong("sleep:deadline", 0) - System.currentTimeMillis()
            endOfChapter = prefs.getBoolean("sleep:chapter", false)
            minutesLeft = if (!endOfChapter && left > 0) (left + 59999) / 60000 else null
            delay(500)
        }
    }
    val active = endOfChapter || minutesLeft != null
    TextButton(onClick = { open = true }, colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
        modifier = Modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) {
            contentDescription = "Sleep timer"
            stateDescription = if (endOfChapter) "End of chapter" else minutesLeft?.let { "$it minutes left" } ?: "Off"
        }) {
        Icon(if (active) Icons.Filled.Bedtime else Icons.Outlined.Bedtime, null, Modifier.size(24.dp))
        minutesLeft?.let { Spacer(Modifier.width(6.dp)); Text("$it min") }
    }
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
