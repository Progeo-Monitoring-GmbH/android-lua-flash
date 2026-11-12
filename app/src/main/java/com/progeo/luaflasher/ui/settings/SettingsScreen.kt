package com.progeo.luaflasher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.progeo.luaflasher.BuildConfig
import com.progeo.core.auth.AuthState
import com.progeo.luaflasher.data.AppSettings
import com.progeo.luaflasher.graph
import com.progeo.core.ui.Section
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onImpressum: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsState(AppSettings())
    val auth by graph.backend.state.collectAsState()
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    LaunchedEffect(settings.baseUrl) { baseUrl = settings.baseUrl }
    var backendUrl by remember { mutableStateOf(settings.backendUrl) }
    LaunchedEffect(settings.backendUrl) { backendUrl = settings.backendUrl }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Section("Release channel") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.CHANNELS.forEach { channel ->
                    FilterChip(
                        selected = settings.channel == channel,
                        onClick = {
                            scope.launch {
                                graph.settings.setChannel(channel)
                                graph.sync.loadCached()
                                graph.sync.sync()
                            }
                        },
                        label = { Text(channel) },
                    )
                }
            }
            Text("Releases are downloaded from ${settings.baseUrl}", style = MaterialTheme.typography.bodySmall)
        }

        Section("ProGeo account") {
            (auth as? AuthState.LoggedIn)?.let { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Signed in as ${a.username ?: "?"}" + if (a.claims?.isDemo == true) " (demo, read-only)" else "",
                        modifier = Modifier.weight(1f),
                    )
                    // Back to the login screen; profiles and bundles stay on the phone.
                    TextButton(onClick = { scope.launch { graph.backend.logout() } }) { Text("Log out") }
                }
            }
            Text("Server: ${settings.backendUrl}", style = MaterialTheme.typography.bodySmall)
        }

        Section("Boards") {
            SwitchRow(
                "Board has auto-reset (DTR/RTS)",
                "Our boards do not: set the jumper and press RST by hand. Turn on for boards with the classic reset circuit.",
                settings.autoReset,
            ) { scope.launch { graph.settings.setAutoReset(it) } }
        }

        Section("Developer") {
            SwitchRow(
                "Developer mode",
                "Allows importing unsigned bundles and changing the server. Unsigned bundles are marked everywhere.",
                settings.developerMode,
            ) { scope.launch { graph.settings.setDeveloperMode(it) } }
            if (settings.developerMode) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButton(onClick = { scope.launch { graph.settings.setBaseUrl(baseUrl) } }, enabled = baseUrl.startsWith("http")) { Text("Save") }
                    TextButton(onClick = { scope.launch { graph.settings.setBaseUrl(AppSettings.DEFAULT_BASE_URL) } }) { Text("Reset to api.progeo.com") }
                }
                OutlinedTextField(
                    value = backendUrl,
                    onValueChange = { backendUrl = it },
                    label = { Text("Backend URL (login, controller configs)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButton(
                        onClick = {
                            scope.launch {
                                // Tokens belong to one server; never send them to another.
                                graph.backend.logout()
                                graph.settings.setBackendUrl(backendUrl)
                            }
                        },
                        enabled = backendUrl.startsWith("https://") && backendUrl.trimEnd('/') != settings.backendUrl.trimEnd('/'),
                    ) { Text("Save (logs out)") }
                    TextButton(onClick = {
                        scope.launch {
                            graph.backend.logout()
                            graph.settings.setBackendUrl(AppSettings.DEFAULT_BACKEND_URL)
                        }
                    }) { Text("Reset") }
                }
            }
        }

        Section("About") {
            Text("LUA Flasher ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Text("Signing keys: ${graph.publicKeys.keys.keys.joinToString()}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onImpressum) { Text("Impressum & open-source licenses") }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
