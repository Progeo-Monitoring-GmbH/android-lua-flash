package com.progeo.luaflasher.ui.m5

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.progeo.core.ui.MessageCard
import com.progeo.core.ui.Section
import com.progeo.core.ui.theme.StatusColors
import com.progeo.luaflasher.core.m5.M5Config
import com.progeo.luaflasher.graph
import com.progeo.luaflasher.m5.M5Job
import com.progeo.luaflasher.m5.M5SessionState
import com.progeo.luaflasher.m5.M5Source
import kotlinx.coroutines.launch

/** Keeps the screen on while an M5 job runs (a sleeping phone may suspend USB). */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(on) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/**
 * One M5 profile (the config.py of a device): its values, and what can be done with the M5 on the
 * USB port: write this config.py, update the firmware from the mstack channel, or both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M5ProfileScreen(id: String, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val profiles by graph.m5Profiles.profiles.collectAsState()
    val profile = profiles.firstOrNull { it.id == id }
    val firmware by graph.m5Firmware.state.collectAsState()
    val session by graph.m5.state.collectAsState()
    val devices by graph.usb.devices.collectAsState()
    var showSecrets by rememberSaveable { mutableStateOf(false) }
    var writeConfig by rememberSaveable { mutableStateOf(true) }
    var updateFirmware by rememberSaveable { mutableStateOf(false) }
    var autostart by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    KeepScreenOn(session.running)

    // A finished job from another screen is not shown here.
    LaunchedEffect(id) { if (!session.running && session.lastJob?.profileId != id) graph.m5.clear() }

    val channel = firmware.channel
    val bundleId = selected ?: channel?.latest ?: firmware.downloaded.maxOrNull()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(profile?.name?.let { "M5 · $it" } ?: "M5 profile") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = {
                IconButton(onClick = { confirmDelete = true }, enabled = profile != null && !session.running) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            },
        )
        if (profile == null) {
            Text("The profile is gone.", Modifier.padding(16.dp))
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("config.py") {
                Text(
                    (if (profile.source == M5Source.QR) "From the config QR code" else "Read from the M5") +
                        " · ${profile.savedAt.take(16).replace('T', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                val problems = profile.config.problems()
                if (problems.isNotEmpty()) MessageCard("The M5 would not start with this config: ${problems.joinToString("; ")}")
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for ((name, value) in profile.config.values) {
                            val secret = name in M5Config.SECRET_KEYS
                            val shown = if (secret && !showSecrets) "••••••••" else value.display
                            Text("$name = $shown", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                TextButton(onClick = { showSecrets = !showSecrets }) {
                    Icon(if (showSecrets) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (showSecrets) "Hide token" else "Show token")
                }
            }

            Section("Firmware (channel mstack)") {
                when {
                    firmware.appTooOld -> MessageCard("The M5 releases need a newer version of this app. Update the app.")
                    firmware.notPublished -> Text("No M5 firmware is released yet.", style = MaterialTheme.typography.bodySmall)
                    channel == null && firmware.downloaded.isEmpty() ->
                        Text("No release list yet. Connect to the internet and sync.", style = MaterialTheme.typography.bodySmall)
                }
                val entries = channel?.bundles.orEmpty()
                for (entry in entries) {
                    val here = entry.id in firmware.downloaded
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = entry.id == bundleId, enabled = here && !session.running) { selected = entry.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = entry.id == bundleId, onClick = null, enabled = here)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "FW ${entry.firmware}" + if (entry.id == channel?.latest) " (latest)" else "",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                if (here) "on the phone" else "not downloaded",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (here) StatusColors.ok else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!here) {
                            TextButton(
                                onClick = { scope.launch { graph.m5Firmware.download(graph.settings.current().baseUrl, entry) } },
                                enabled = firmware.downloading == null,
                            ) { Text("Download") }
                        }
                    }
                }
                firmware.downloading?.let {
                    Text("Downloading $it", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { firmware.progress }, modifier = Modifier.fillMaxWidth())
                }
                firmware.error?.let { MessageCard(it) }
                OutlinedButton(onClick = { scope.launch { graph.syncM5() } }, enabled = !firmware.running) { Text("Check for releases") }
            }

            Section("Connected M5 (USB)") {
                Text(
                    "Connect the M5 with a USB-C data cable through the OTG adapter. It keeps running until the app " +
                        "stops it for the transfer and restarts afterwards. Measurements pause for about a minute.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    devices.firstOrNull()?.let { "Adapter: ${it.label}" } ?: "No USB device found. Use an OTG adapter and a data cable.",
                    color = if (devices.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                CheckRow("Write config.py for ${profile.name}", writeConfig, !session.running) { writeConfig = it }
                CheckRow(
                    "Update the firmware" + (bundleId?.let { " to ${it.removePrefix("m5-ain4-mpy-")}" } ?: ""),
                    updateFirmware && bundleId != null,
                    !session.running && bundleId != null && bundleId in firmware.downloaded,
                ) { updateFirmware = it }
                if (updateFirmware) {
                    CheckRow("Start the firmware at boot (first install on a new M5)", autostart, !session.running) { autostart = it }
                }
                val job = M5Job(
                    profileId = profile.id.takeIf { writeConfig },
                    bundleId = bundleId?.takeIf { updateFirmware && it in firmware.downloaded },
                    autostart = autostart && updateFirmware,
                )
                Button(
                    onClick = { graph.m5.start(job) },
                    enabled = !session.running && devices.isNotEmpty() && !job.readOnly && profile.config.problems().isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start") }
                SessionProgress(session)
            }
        }
    }

    session.otherDevice?.let { (onDevice, wanted) ->
        AlertDialog(
            onDismissRequest = { graph.m5.clear() },
            title = { Text("Another device") },
            text = {
                Text(
                    "This M5 is configured as $onDevice, the profile is for $wanted. Overwrite its config.py? " +
                        "The current config.py is kept on the phone as profile $onDevice.",
                )
            },
            confirmButton = {
                TextButton(onClick = { session.lastJob?.let { graph.m5.start(it.copy(overwriteOtherDevice = true)) } }) { Text("Overwrite") }
            },
            dismissButton = { TextButton(onClick = { graph.m5.clear() }) { Text("Cancel") } },
        )
    }

    if (confirmDelete && profile != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${profile.name}?") },
            text = { Text("The profile is removed from this phone. The M5 keeps its config.py.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { graph.m5Profiles.delete(profile.id) }
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Reads config.py from the M5 on the USB port and saves it as an M5 profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M5ReadScreen(onBack: () -> Unit, onSaved: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val session by graph.m5.state.collectAsState()
    val devices by graph.usb.devices.collectAsState()
    KeepScreenOn(session.running)
    LaunchedEffect(Unit) { if (!session.running) graph.m5.clear() }
    LaunchedEffect(session.savedProfileId, session.running) {
        val saved = session.savedProfileId
        if (!session.running && saved != null && session.lastJob?.readOnly == true) onSaved(saved)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Read from M5Stack") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Connected M5 (USB)") {
                Text(
                    "Connect the M5 (CoreS3 with the ProGeo MicroPython firmware) with a USB-C data cable through the OTG adapter. " +
                        "The app stops the firmware for a moment, reads config.py and the firmware version, saves the config " +
                        "as a profile named after the device hash and restarts the M5.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    devices.firstOrNull()?.let { "Adapter: ${it.label}" } ?: "No USB device found. Use an OTG adapter and a data cable.",
                    color = if (devices.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Button(
                    onClick = { graph.m5.start(M5Job()) },
                    enabled = !session.running && devices.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Read config.py from the M5") }
                SessionProgress(session)
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = checked, enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SessionProgress(session: M5SessionState) {
    if (session.running) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(session.step?.label?.let { "$it…" } ?: "Starting…")
        }
        session.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
    }
    session.device?.let { d ->
        Text(
            "On the M5: firmware ${d.firmware ?: "not installed"} · config.py ${d.deviceHash ?: if (d.configText == null) "missing" else "unreadable"}" +
                " · start at boot ${if (d.autostart) "on" else "off"}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    session.done?.let { done ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusColors.ok)
            Spacer(Modifier.width(8.dp))
            Text(done)
        }
    }
    if (session.otherDevice == null) session.error?.let { MessageCard(it) }
    if (session.log.isNotEmpty()) {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide log" else "Show log") }
        if (open) {
            SelectionContainer {
                Text(session.log.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
