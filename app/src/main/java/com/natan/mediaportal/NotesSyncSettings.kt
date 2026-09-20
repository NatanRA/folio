package com.natan.mediaportal

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.net.URI

@Composable
internal fun NotesSyncSettings() {
    val store = BookServer(LocalContext.current, credentialNamespace = "notes-sync")
    var account by remember { mutableStateOf(store.account()) }
    var url by remember { mutableStateOf(account?.url.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    SettingsSection("Notes sync", if (account == null) "Not configured" else "Configured", Icons.Outlined.Sync) {
        Text("Sync highlights, notes, bookmarks and saved words between Android and iOS. Both devices need the identical book file. This uses your private annotation service.")
        OutlinedTextField(url, { url = it }, label = { Text("HTTPS sync address") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true)
        OutlinedTextField(token, { token = it }, label = { Text("Access token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true)
        Button(onClick = {
            try {
                val address = URI(url.trim().trimEnd('/'))
                require(address.scheme == "https" && address.host != null && address.userInfo == null && address.query == null && address.fragment == null) { "Enter a valid HTTPS address." }
                require(token.length >= 24) { "Enter the full access token." }
                val saved = ServerAccount(address.toString(), "", token.trim()); store.save(saved); account = saved; token = ""
                message = "Saved. Open Highlights & notes in a book and choose Sync notes."
            } catch (e: Exception) { message = e.message }
        }, enabled = token.isNotBlank()) { Text("Save connection") }
        if (account != null) TextButton(onClick = { store.disconnect(); account = null; message = "Disconnected. Your notes are kept." }) { Text("Disconnect") }
        message?.let { Text(it, Modifier.padding(vertical = 12.dp)) }
    }
}
