package com.progeo.luaflasher.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.progeo.luaflasher.core.config.LuaJson
import com.progeo.luaflasher.data.Profile
import com.progeo.luaflasher.graph
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun ProfilesScreen(onEdit: (String?) -> Unit, onFetch: () -> Unit, onFlash: (String) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val profiles by graph.profiles.profiles.collectAsState()
    var confirmDelete by remember { mutableStateOf<Profile?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Profiles", style = MaterialTheme.typography.headlineMedium) }
            item {
                Text(
                    "A profile is a complete device_config.lua. Read it from a controller over USB (named after its device hash) or create one; every value can be edited. Profiles are encrypted on the phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Button(onClick = onFetch, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Read from controller")
                }
            }
            if (profiles.isEmpty()) item { Text("No profiles yet. Read one from a controller or add one.") }
            items(profiles, key = { it.id }) { p ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(p.id) },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            fun value(path: String) = (LuaJson.get(p.config, path) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                            val summary = listOfNotNull(
                                value("device.project_id")?.let { "project $it" },
                                value("device.model"),
                                value("wifi.ssid")?.let { "Wi-Fi $it" },
                            ).joinToString(" · ")
                            if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall)
                            p.boardReadAt?.let {
                                Text(
                                    "read from board · ${it.take(16).replace('T', ' ')}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            p.source?.let { s ->
                                Text(
                                    "from controller · location ${s.locationId}" + (s.fetchedAt?.let { " · ${it.take(16).replace('T', ' ')}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        IconButton(onClick = { onFlash(p.id) }) { Icon(Icons.Default.FlashOn, contentDescription = "Flash") }
                        IconButton(onClick = { confirmDelete = p }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { onEdit(null) },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("New profile") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${p.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { graph.profiles.delete(p.id) }
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
