package com.progeo.luaflasher.ui.bundles

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.bundle.BundleFormat
import com.progeo.luaflasher.data.AppSettings
import com.progeo.luaflasher.data.StoredBundle
import com.progeo.luaflasher.graph
import com.progeo.core.ui.MessageCard
import com.progeo.luaflasher.ui.formatBytes
import com.progeo.core.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun BundlesScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val bundles by graph.bundles.bundles.collectAsState()
    val sync by graph.sync.state.collectAsState()
    val settings by graph.settings.settings.collectAsState(AppSettings())
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var confirmDelete by remember { mutableStateOf<StoredBundle?>(null) }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = try {
                val tmp = File(context.cacheDir, "import.zip")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                }
                val stored = try {
                    graph.bundles.add(tmp, signed = false, keyId = null, notes = "Imported in developer mode")
                } finally {
                    tmp.delete()
                }
                "Imported ${stored.id} (unsigned)" to false
            } catch (e: FlasherException) {
                (e.userMessage + "\n" + (e.message ?: "")) to true
            } catch (e: Exception) {
                "Import failed: ${e.message}" to true
            }
        }
    }

    val available = sync.channel?.bundles.orEmpty().filter { entry -> bundles.none { it.id == entry.id } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Bundles", style = MaterialTheme.typography.headlineMedium) }
        message?.let { (text, isError) -> item { MessageCard(text, error = isError) } }
        sync.error?.let { item { MessageCard(it) } }

        item { Text("On this phone", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        if (bundles.isEmpty()) item { Text("None yet. Sync on the home screen while online.") }
        items(bundles, key = { it.id }) { b ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.release, style = MaterialTheme.typography.titleMedium)
                            Text("firmware ${b.firmware}", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { scope.launch { graph.bundles.setPinned(b.id, !b.pinned) } }) {
                            Icon(if (b.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = if (b.pinned) "Unpin" else "Pin")
                        }
                        IconButton(onClick = { confirmDelete = b }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    if (b.signed) "signed (${b.keyId})" else "UNSIGNED",
                                    color = if (b.signed) StatusColors.ok else MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                        if (sync.channel?.latest == b.id) AssistChip(onClick = {}, label = { Text("latest") })
                    }
                    Text("${formatBytes(b.size)} · created ${b.created ?: "?"}", style = MaterialTheme.typography.bodySmall)
                    Text("modules: ${b.lfsModules.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    b.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }

        if (available.isNotEmpty()) {
            item { Text("Available in channel ${sync.channelName}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(available, key = { "remote-" + it.id }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.release, style = MaterialTheme.typography.titleMedium)
                        Text("firmware ${entry.firmware} · ${formatBytes(entry.size)}", style = MaterialTheme.typography.bodySmall)
                        entry.notes?.takeIf { it.isNotBlank() }?.let { Text(it) }
                        if (entry.configSchema > BundleFormat.SUPPORTED_CONFIG_SCHEMA) {
                            Text("Needs a newer version of this app (config schema ${entry.configSchema}).", color = MaterialTheme.colorScheme.error)
                        } else if (sync.downloading == entry.id) {
                            LinearProgressIndicator(progress = { sync.downloadProgress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            OutlinedButton(
                                onClick = { scope.launch { graph.sync.downloadOnDemand(entry) } },
                                enabled = sync.downloading == null && !sync.running,
                            ) { Text("Download") }
                        }
                    }
                }
            }
        }

        if (settings.developerMode) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Developer", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Import an unsigned bundle built with scripts/build_flash_bundle.ps1. It is marked unsigned everywhere.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        OutlinedButton(onClick = { import.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                            Text("Import bundle…")
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }
    }

    confirmDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${b.release}?") },
            text = { Text("The bundle can be downloaded again while online.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { graph.bundles.delete(b.id) }
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
