package com.natan.mediaportal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun KoSyncSettings() {
    val settings = KoSettings(LocalContext.current)
    var account by remember { mutableStateOf(settings.account()) }
    var address by remember { mutableStateOf(account?.url ?: "https://sync.koreader.rocks") }
    var username by remember { mutableStateOf(account?.user.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var bridge by remember { mutableStateOf(settings.bridgeInstalled) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SettingsSection("KOReader sync", if (account == null) "Not connected" else "Connected as ${account!!.user}", Icons.Outlined.Sync) {
        Text("Use the same sync server and account as KOReader. Create an account in KOReader’s Progress sync menu first. This is separate from BookLore.", Modifier.padding(bottom = 12.dp))
        OutlinedTextField(address, { address = it }, label = { Text("Sync server address") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !working)
        OutlinedTextField(username, { username = it }, label = { Text("Sync username") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true, enabled = !working)
        OutlinedTextField(password, { password = it }, label = { Text("Sync password") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true, enabled = !working, visualTransformation = PasswordVisualTransformation())
        Button(colors = ButtonDefaults.buttonColors(), onClick = {
            scope.launch {
                working = true; message = null
                try {
                    val candidate = KoAccount(KoProtocol.endpoint(address), username.trim(), KoProtocol.md5(password.toByteArray(Charsets.UTF_8)))
                    KoSync().login(candidate)
                    settings.save(candidate); account = candidate; password = ""; bridge = false
                    message = "Connected. Open a downloaded e-book and tap Sync."
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: java.io.IOException) { message = "Can’t reach the sync server. Your saved position is unchanged." }
                catch (e: Exception) { message = e.message ?: "Couldn’t connect. Check your sync login." }
                finally { working = false }
            }
        }, enabled = !working && address.isNotBlank() && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 12.dp)) { Text(if (working) "Connecting…" else "Connect progress sync") }
        Text("Receiving works with standard KOReader. To send positions back, install our Folio bridge plugin on every KOReader using this account. Select Binary document matching in KOReader.", Modifier.padding(top = 16.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Checkbox(bridge, onCheckedChange = { bridge = it; settings.bridgeInstalled = it }, enabled = account != null && !working)
            Text("Bridge installed on my KOReader devices", Modifier.padding(top = 12.dp))
        }
        Text("A book fingerprint, position and device identifier are sent. Books and audiobook progress are not uploaded. Positions can differ between reading engines.", style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, Modifier.padding(vertical = 12.dp)) }
        if (account != null) TextButton(onClick = { settings.disconnect(); account = null; bridge = false; message = "Disconnected. Your reading positions are kept." }, enabled = !working) { Text("Disconnect progress sync") }
    }
}
