package com.progeo.luaflasher.ui.profiles

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.config.LuaJson
import com.progeo.luaflasher.data.Profile
import com.progeo.luaflasher.data.ProfileExport
import com.progeo.luaflasher.graph
import com.progeo.luaflasher.ui.MessageCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

private sealed interface PassAction {
    data class Export(val uri: Uri) : PassAction
    data class Import(val uri: Uri) : PassAction
}

@Composable
fun ProfilesScreen(onEdit: (String?) -> Unit, onFetch: () -> Unit, onFlash: (String) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val profiles by graph.profiles.profiles.collectAsState()
    var confirmDelete by remember { mutableStateOf<Profile?>(null) }
    var passAction by remember { mutableStateOf<PassAction?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) passAction = PassAction.Export(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) passAction = PassAction.Import(uri)
    }

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
            message?.let { (text, error) -> item { MessageCard(text, error) } }
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
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("profiles.lfprofiles") }, enabled = profiles.isNotEmpty()) { Text("Export…") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import…") }
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

    passAction?.let { action ->
        var pass by remember { mutableStateOf("") }
        val exporting = action is PassAction.Export
        AlertDialog(
            onDismissRequest = { passAction = null },
            title = { Text(if (exporting) "Passphrase for the export" else "Passphrase of the file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (exporting) Text("The file contains tokens and passwords. Use at least 10 characters and share the passphrase separately.")
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = if (exporting) pass.length >= 10 else pass.isNotEmpty(),
                    onClick = {
                        val chars = pass.toCharArray()
                        passAction = null
                        scope.launch {
                            message = try {
                                withContext(Dispatchers.IO) {
                                    when (action) {
                                        is PassAction.Export -> {
                                            val bytes = ProfileExport.export(profiles, chars)
                                            context.contentResolver.openOutputStream(action.uri)!!.use { it.write(bytes) }
                                            "Exported ${profiles.size} profile(s)." to false
                                        }
                                        is PassAction.Import -> {
                                            val bytes = context.contentResolver.openInputStream(action.uri)!!.use { it.readBytes() }
                                            val imported = ProfileExport.import(bytes, chars, graph.fieldKind)
                                            graph.profiles.importAll(imported)
                                            "Imported ${imported.size} profile(s)." to false
                                        }
                                    }
                                }
                            } catch (e: FlasherException) {
                                e.userMessage to true
                            } catch (e: Exception) {
                                "Failed: ${e.message}" to true
                            } finally {
                                chars.fill('\u0000')
                            }
                        }
                    },
                ) { Text(if (exporting) "Export" else "Import") }
            },
            dismissButton = { TextButton(onClick = { passAction = null }) { Text("Cancel") } },
        )
    }
}
