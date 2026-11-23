package com.progeo.luaflasher.ui.routers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.progeo.core.qr.CodeFormat
import com.progeo.core.qr.CodeScanDialog
import com.progeo.core.ui.MessageCard
import com.progeo.core.ui.theme.StatusColors
import com.progeo.luaflasher.graph
import com.progeo.luaflasher.router.Phase
import com.progeo.luaflasher.router.Via
import com.progeo.luaflasher.router.firmwareShort
import com.progeo.teltonika.label.RouterLabels

/**
 * The routers on the phone. Scanning the device label opens a router (and sets it up if it is
 * new); the box code or the serial barcode opens a router the backend already knows.
 */
@Composable
fun RoutersScreen(onOpen: () -> Unit) {
    val graph = LocalContext.current.graph
    val routers by graph.routerStore.routers.collectAsState()
    val outbox by graph.routerStore.outbox.collectAsState()
    val session by graph.router.state.collectAsState()
    var via by remember { mutableStateOf(Via.WIFI) }
    var scanning by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Routers", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text(
                "Teltonika RUT241, RUT986 and RUT901. Scan the code on the device label: the phone joins the router's Wi-Fi " +
                    "and logs in with the stored password, or with the factory password for a new router.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (session.phase != Phase.IDLE && session.serial != null) {
            item {
                Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Router, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Open: ${session.device?.modelName ?: "router"} ${session.serial}", style = MaterialTheme.typography.titleSmall)
                            Text(session.busy ?: session.phase.name.lowercase(), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = onOpen) { Text("Continue") }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Connect via", style = MaterialTheme.typography.bodyMedium)
                FilterChip(selected = via == Via.WIFI, onClick = { via = Via.WIFI }, label = { Text("Router Wi-Fi") })
                FilterChip(selected = via == Via.CABLE, onClick = { via = Via.CABLE }, label = { Text("Cable") })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scanError = null; scanning = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan router")
                }
                OutlinedButton(onClick = { typing = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Keyboard, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Serial number")
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { graph.router.prepareOffline() },
                enabled = session.busy == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.CloudSync, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sync for offline use")
            }
        }
        if (session.phase == Phase.IDLE) {
            session.busy?.let { busy ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(busy, style = MaterialTheme.typography.bodySmall)
                        session.progress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
                            ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            session.error?.let { item { MessageCard(it) } }
            session.message?.let { item { MessageCard(it, error = false) } }
        }
        if (outbox.isNotEmpty()) {
            item {
                Text(
                    "${outbox.size} changes wait for the server (sent automatically when online).",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.warn,
                )
            }
        }
        scanError?.let { item { MessageCard(it) } }
        if (routers.isEmpty()) item { Text("No routers on this phone yet.") }
        items(routers, key = { it.serial }) { r ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        graph.router.openKnown(r.serial, via)
                        onOpen()
                    },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${r.model ?: "Router"} · ${r.serial}", style = MaterialTheme.typography.titleMedium)
                    val line = listOfNotNull(
                        r.firmware.firmwareShort()?.let { "firmware $it" },
                        r.templateId?.let { "config $it v${r.templateVersion}" },
                        r.access?.ssid?.let { "Wi-Fi $it" },
                    ).joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall)
                    r.lastSeen?.let { Text("last connected ${it.take(16).replace('T', ' ')}", style = MaterialTheme.typography.bodySmall) }
                    if (r.onFactoryPassword) Text("factory password", style = MaterialTheme.typography.labelMedium, color = StatusColors.warn)
                    if (r.pendingPassword != null) {
                        Text("password change not confirmed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                    if (outbox.any { it.serial == r.serial }) {
                        Text("not on the server yet", style = MaterialTheme.typography.labelMedium, color = StatusColors.warn)
                    }
                }
            }
        }
    }

    if (scanning) {
        CodeScanDialog(
            onDismiss = { scanning = false },
            title = "Scan router",
            hint = "The code on the device label. The box code or the serial barcode works for routers we already know.",
            formats = CodeFormat.LABELS,
            onCode = { text ->
                scanning = false
                val label = RouterLabels.parse(text)
                if (label == null) {
                    scanError = "This is not a Teltonika router label."
                } else {
                    graph.router.open(label, via)
                    onOpen()
                }
            },
        )
    }

    if (typing) {
        var serial by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text("Router serial number") },
            text = {
                OutlinedTextField(
                    value = serial,
                    onValueChange = { serial = it.filter(Char::isDigit).take(10) },
                    label = { Text("10 digits, on the label") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = serial.length == 10,
                    onClick = {
                        typing = false
                        graph.router.open(RouterLabels.parse(serial)!!, via)
                        onOpen()
                    },
                ) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
        )
    }
}
