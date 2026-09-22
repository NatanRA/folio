package com.natan.mediaportal

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable fun TelegramSettings(imported: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val telegram = remember { TelegramLibrary.get(context) }
    val auth by telegram.auth.collectAsState()
    val error by telegram.error.collectAsState()
    val entries by telegram.entries.collectAsState()
    val chats by telegram.chats.collectAsState()
    val activity by telegram.activity.collectAsState()
    val progress by telegram.progress.collectAsState()
    val more by telegram.more.collectAsState()
    val scanning by telegram.scanning.collectAsState()
    var value by remember(auth) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var chatMenu by remember { mutableStateOf(false) }
    val selected by telegram.selectedName.collectAsState()
    var revision by remember { mutableIntStateOf(0) }
    var configured by remember { mutableStateOf(telegram.configured) }
    var ownCredentials by remember { mutableStateOf(false) }
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun run(action: suspend () -> Unit) { scope.launch {
        busy = true; telegram.error.value = null
        try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { telegram.error.value = e.message }
        finally { busy = false }
    } }
    SettingsSection("Telegram", if (auth == "authorizationStateReady") "Connected · $selected" else "Not connected", Icons.Outlined.Cloud) {
        Text("Audiobooks appear in your library. Tap a title to stream, or keep a copy offline.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        val credentialFields: @Composable () -> Unit = {
            OutlinedTextField(apiId, { apiId = it }, label = { Text("API ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(apiHash, { apiHash = it }, label = { Text("API hash") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = { run { telegram.configure(apiId.trim(), apiHash.trim()); apiHash = ""; configured = true; ownCredentials = false; telegram.start() } }, enabled = !busy && apiId.isNotBlank() && apiHash.isNotBlank()) { Text("Connect Telegram") }
        }
        if (!configured) {
            Text("To get started, sign in at my.telegram.org/apps and create an app with any name. Paste its API ID and API hash below. You only do this once, and they’re stored securely on this device.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { uriHandler.openUri("https://my.telegram.org/apps") }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("Open my.telegram.org/apps") }
            credentialFields()
        }
        else if (auth == "disconnected") {
            Button(onClick = { telegram.start() }) { Text("Connect Telegram") }
            Text("Telegram sends a login code to your other device, or by text message if this is your only one.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { ownCredentials = !ownCredentials }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (ownCredentials) "Hide my own credentials" else "Use my own Telegram credentials")
            }
            if (ownCredentials) credentialFields()
        }
        else if (auth == "authorizationStateReady") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { run { telegram.selectSaved() } }, enabled = !busy && !scanning && activity == null) { Text("Saved Messages") }
                TextButton(onClick = { run { telegram.loadChats(); chatMenu = true } }, enabled = !busy && !scanning && activity == null) { Text("Choose chat") }
            }
            if (chatMenu) chats.forEach { (id, title) ->
                TextButton(onClick = { chatMenu = false; run { telegram.select(id) } }, enabled = !busy) { Text(title) }
            }
            if (scanning) Text("Scanning $selected for books…")
            else if (entries.isEmpty() && !busy) Text("No supported books in this part of the chat. Upload an e-book or audiobook to $selected, then refresh.")
            Text("${entries.count { it.audio }} audiobooks available in Library")
            entries.filter { !it.audio }.forEach { entry ->
                revision
                val downloaded = telegram.downloaded(entry)
                ListItem(headlineContent = { Text(entry.name) }, supportingContent = { Text("${entry.size / (1024 * 1024)} MB · ${if (entry.audio) "Audiobook" else "E-book"}") }, trailingContent = {
                    TextButton(onClick = { telegram.download(entry) { revision++; imported() } }, enabled = !downloaded && activity == null && !busy) { Text(if (downloaded) "Downloaded" else "Download") }
                })
            }
            if (more) TextButton(onClick = { run { telegram.loadMore() } }, enabled = !busy && !scanning) { Text("Load older files") }
            TextButton(onClick = { run { telegram.refresh() } }, enabled = !busy && !scanning && activity == null) { Text("Refresh") }
            TextButton(onClick = { run { telegram.disconnect() } }, enabled = !busy && activity == null) { Text("Disconnect Telegram") }
        } else {
            val label = when (auth) {
                "authorizationStateWaitPhoneNumber" -> "Phone number with country code"
                "authorizationStateWaitCode" -> "Telegram login code"
                "authorizationStateWaitPassword" -> "Two-step verification password"
                "authorizationStateWaitEmailAddress" -> "Email address for Telegram verification"
                "authorizationStateWaitEmailCode" -> "Email verification code"
                else -> null
            }
            if (label != null) {
                Text(if (auth == "authorizationStateWaitCode") "Check Telegram on your other device for the login code." else "Sign in to your own Telegram account.")
                OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true, visualTransformation = if (auth == "authorizationStateWaitPhoneNumber") VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(onClick = { val input = value; value = ""; run { telegram.signIn(input) } }, enabled = value.isNotBlank() && !busy) { Text("Continue") }
            } else Text(if (auth == "starting" || auth == "authorizationStateWaitTdlibParameters") "Opening secure connection…" else "Telegram is requesting an additional verification step. Open Telegram on your other device, then reconnect.")
            TextButton(onClick = { run { telegram.close() } }, enabled = !busy) { Text("Cancel sign-in") }
        }
        if (busy || scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        activity?.let {
            Text(it)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { telegram.cancel() }) { Text("Cancel download") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        Text("Streaming needs an internet connection. Offline copies stay on this device when you disconnect.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
}
