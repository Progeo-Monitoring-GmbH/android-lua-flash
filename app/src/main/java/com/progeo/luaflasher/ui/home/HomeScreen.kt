package com.progeo.luaflasher.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.progeo.luaflasher.core.bundle.BundleFormat
import com.progeo.luaflasher.data.AppSettings
import com.progeo.luaflasher.flash.FlashState
import com.progeo.luaflasher.graph
import com.progeo.core.ui.MessageCard
import com.progeo.core.ui.theme.StatusColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onFlash: () -> Unit, onBundles: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val sync by graph.sync.state.collectAsState()
    val bundles by graph.bundles.bundles.collectAsState()
    val profiles by graph.profiles.profiles.collectAsState()
    val settings by graph.settings.settings.collectAsState(AppSettings())
    val devices by graph.usb.devices.collectAsState()
    val flash by graph.flash.state.collectAsState()

    val usable = bundles.filter { it.signed || settings.developerMode }
    val newest = usable.maxByOrNull { it.created ?: "" }
    val channelLatest = sync.channel?.latestBundle
    val missingLatest = channelLatest != null && bundles.none { it.id == channelLatest.id }

    PullToRefreshBox(
        isRefreshing = sync.running,
        onRefresh = { scope.launch { graph.sync.sync() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("LUA Flasher", style = MaterialTheme.typography.headlineMedium)

            if (sync.appTooOld) MessageCard("Update the app: this release list needs a newer app version. Bundles already on the phone still work.")

            // Readiness card (PLAN.md section 4, step 2)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (newest != null) {
                        StatusLine(
                            Icons.Default.CheckCircle,
                            "Ready offline: ${newest.release} (fw ${newest.firmware}) · ${profiles.size} profile${if (profiles.size == 1) "" else "s"}",
                            StatusColors.ok,
                        )
                        if (!newest.signed) Text("unsigned developer bundle", color = MaterialTheme.colorScheme.error)
                    } else {
                        StatusLine(Icons.Default.CloudDownload, "No firmware on the phone yet. Connect to the internet and pull to sync.", StatusColors.warn)
                    }
                    if (missingLatest && channelLatest != null) {
                        if (channelLatest.configSchema > BundleFormat.SUPPORTED_CONFIG_SCHEMA) {
                            StatusLine(Icons.Default.CloudDownload, "Release ${channelLatest.release} needs a newer version of this app. Update the app.", StatusColors.warn)
                        } else {
                            StatusLine(Icons.Default.CloudDownload, "Newer release available (${channelLatest.release}) — download before going on site", StatusColors.warn)
                        }
                    }
                    if (sync.notPublished) {
                        Text("Nothing is released in the ${sync.channelName} channel yet.", style = MaterialTheme.typography.bodySmall)
                    }
                    sync.downloading?.let {
                        Text("Downloading $it", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { sync.downloadProgress }, modifier = Modifier.fillMaxWidth())
                    }
                    val last = sync.lastSuccess?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
                    Text(
                        "Channel ${sync.channelName.ifEmpty { settings.channel }}" + (last?.let { " · synced $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scope.launch { graph.sync.sync() } }, enabled = !sync.running) { Text("Sync now") }
                        OutlinedButton(onClick = onBundles) { Text("Bundles") }
                    }
                }
            }

            sync.error?.let { MessageCard(it) }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Usb, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            devices.firstOrNull()?.label?.let { "Adapter: $it" } ?: "No USB adapter connected",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val active = flash != FlashState.Idle
                    Button(onClick = onFlash, enabled = active || usable.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FlashOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (active) "Continue the running flash" else "Flash a board")
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    }
}
