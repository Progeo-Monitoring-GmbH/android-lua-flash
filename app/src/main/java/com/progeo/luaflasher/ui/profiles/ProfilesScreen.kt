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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.progeo.luaflasher.m5.M5Source
import com.progeo.luaflasher.ui.SwipeToDelete
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * The profiles on the phone. A profile is created in one of two ways: from a config QR code
 * ([onScan]: the editor opens with the scanner) or read from a board over USB ([onRead]).
 */
@Composable
fun ProfilesScreen(
    onEdit: (String) -> Unit,
    onScan: () -> Unit,
    onRead: () -> Unit,
    onFlash: (String) -> Unit,
    onOpenM5: (String) -> Unit,
    onReadM5: () -> Unit,
) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val profiles by graph.profiles.profiles.collectAsState()
    val m5Profiles by graph.m5Profiles.profiles.collectAsState()
    var confirmDelete by remember { mutableStateOf<Profile?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Profiles", style = MaterialTheme.typography.headlineMedium) }
            item {
                Text(
                    "A profile is a complete device_config.lua, named after its device hash. Scan it from a config QR code " +
                        "or read it from a controller over USB; then every value can be edited. The QR code of an M5Stack " +
                        "(config.py) is recognised and listed under M5Stack. Profiles are encrypted on the phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onScan, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR code")
                    }
                    Button(onClick = onRead, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Usb, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Read from controller")
                    }
                }
            }
            item {
                TextButton(onClick = onReadM5) {
                    Icon(Icons.Default.Usb, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Read from M5Stack")
                }
            }
            if (m5Profiles.isNotEmpty()) {
                item { Text("M5Stack", style = MaterialTheme.typography.titleMedium) }
                items(m5Profiles, key = { "m5-" + it.id }) { p ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenM5(p.id) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    p.config.projectId?.let { "project $it" },
                                    p.config.model,
                                    if (p.config.rs485Enabled) "RS485" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                (if (p.source == M5Source.QR) "config QR code" else "read from M5") + " · ${p.savedAt.take(16).replace('T', ' ')}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                item { Text("Controllers (LUA)", style = MaterialTheme.typography.titleMedium) }
            }
            if (profiles.isEmpty()) item { Text("No profiles yet. Scan a config QR code or read one from a controller.") }
            if (profiles.isNotEmpty()) item { Text("Swipe a profile sideways to delete it.", style = MaterialTheme.typography.bodySmall) }
            items(profiles, key = { it.id }) { p ->
                SwipeToDelete(onDelete = { confirmDelete = p }) {
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
                        }
                        IconButton(onClick = { onFlash(p.id) }) { Icon(Icons.Default.FlashOn, contentDescription = "Flash") }
                        IconButton(onClick = { confirmDelete = p }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                }
                }
            }
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text("The profile is removed from this phone. Boards already flashed with it keep their config.") },
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
