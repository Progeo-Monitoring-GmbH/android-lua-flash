package com.progeo.luaflasher.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.progeo.luaflasher.AppGraph
import com.progeo.core.UserFacingException
import com.progeo.core.auth.AuthState
import com.progeo.luaflasher.backend.RemoteConfig
import com.progeo.luaflasher.backend.RemoteDevice
import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.config.LuaJson
import com.progeo.luaflasher.core.config.LuaParseException
import com.progeo.luaflasher.data.RemoteSource
import com.progeo.luaflasher.graph
import com.progeo.core.ui.MessageCard
import com.progeo.core.ui.Section
import kotlinx.coroutines.launch

class FetchConfigViewModel : ViewModel() {
    // devices
    var locationId by mutableStateOf("")
    var devices by mutableStateOf<List<RemoteDevice>?>(null)
    var filter by mutableStateOf("")
    var selected by mutableStateOf<RemoteDevice?>(null)
    var remote by mutableStateOf<RemoteConfig?>(null)

    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    private var initialized = false

    // reading from the board over USB
    var boardStatus by mutableStateOf<String?>(null)
    var boardError by mutableStateOf<String?>(null)
    var showServer by mutableStateOf(false)

    /** Reads config/device_config.lua from the connected board and stores it as a profile. */
    fun readFromBoard(graph: AppGraph, onSaved: (String) -> Unit) {
        if (busy) return
        busy = true
        boardError = null
        viewModelScope.launch {
            try {
                val config = graph.boardReader.read { boardStatus = it }
                val profile = graph.profiles.saveFromBoard(config)
                onSaved(profile.id)
            } catch (e: FlasherException) {
                boardError = e.userMessage
            } catch (e: Exception) {
                boardError = e.message ?: e.javaClass.simpleName
            } finally {
                boardStatus = null
                busy = false
            }
        }
    }

    fun init(graph: AppGraph) {
        if (initialized) return
        initialized = true
        viewModelScope.launch { locationId = graph.settings.current().locationId }
    }

    private fun task(block: suspend () -> Unit) {
        busy = true
        error = null
        info = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: FlasherException) {
                error = e.userMessage
            } catch (e: UserFacingException) {
                error = e.userMessage
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    fun loadDevices(graph: AppGraph) = task { loadDevicesNow(graph) }

    private suspend fun loadDevicesNow(graph: AppGraph) {
        val id = locationId.trim()
        if (id.isEmpty()) return
        graph.settings.setLocationId(id)
        selected = null
        remote = null
        devices = graph.deviceConfigs.devices(id)
    }

    fun select(graph: AppGraph, device: RemoteDevice) {
        selected = device
        remote = null
        refresh(graph)
    }

    fun refresh(graph: AppGraph) = task {
        val device = selected ?: return@task
        remote = graph.deviceConfigs.config(device.id, locationId.trim())
    }

    fun request(graph: AppGraph) = task {
        val device = selected ?: return@task
        graph.deviceConfigs.requestConfig(device.id, locationId.trim())
        remote = graph.deviceConfigs.config(device.id, locationId.trim())
        info = "Requested. The controller sends its config the next time it checks for commands."
    }

    /** Stores the fetched file as a profile named after its device hash; returns the profile id. */
    fun save(graph: AppGraph, onSaved: (String) -> Unit) = task {
        val r = remote ?: return@task
        val content = r.content ?: return@task
        val config = try {
            LuaJson.parse(content)
        } catch (e: LuaParseException) {
            error = "The controller's config could not be read: ${e.message}"
            return@task
        }
        val profile = graph.profiles.saveFetched(
            config,
            RemoteSource(locationId.trim(), r.deviceId, r.rawHash ?: selected?.hash, r.fetchedAt, r.commandId),
        )
        onSaved(profile.id)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FetchConfigScreen(onBack: () -> Unit, onSaved: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val vm: FetchConfigViewModel = viewModel()
    val auth by graph.backend.state.collectAsState()
    LaunchedEffect(Unit) { vm.init(graph) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Read from controller") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BoardSection(vm, graph, onSaved)

            TextButton(onClick = { vm.showServer = !vm.showServer }) {
                Text(if (vm.showServer) "Hide the server route" else "Board not at hand? Fetch via the ProGeo server…")
            }
            // The app is behind the login (AuthGate), so the server route needs no login form.
            if (vm.showServer) {
                if ((auth as? AuthState.LoggedIn)?.claims?.isDemo == true) {
                    MessageCard("Demo account: read-only, requesting configs is not possible.", error = false)
                }
                DevicePicker(vm, graph)
                vm.selected?.let { DeviceConfigCard(vm, graph, it, onSaved) }
            }
            vm.error?.let { MessageCard(it) }
            vm.info?.let { MessageCard(it, error = false) }
        }
    }
}

@Composable
private fun BoardSection(vm: FetchConfigViewModel, graph: AppGraph, onSaved: (String) -> Unit) {
    val devices by graph.usb.devices.collectAsState()
    Section("Connected board (USB)") {
        Text(
            "Plug the board in with the OTG adapter (boot jumper removed, own power supply). " +
                "The app reads its config/device_config.lua and saves it as a profile named after the device hash.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            devices.firstOrNull()?.let { "Adapter: ${it.label}" } ?: "No USB serial adapter found. Use an OTG adapter and a data cable.",
            color = if (devices.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Button(
            onClick = { vm.readFromBoard(graph, onSaved) },
            enabled = !vm.busy && devices.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Read config from board") }
        vm.boardStatus?.let { status ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(status)
            }
        }
        vm.boardError?.let { MessageCard(it) }
    }
}

@Composable
private fun DevicePicker(vm: FetchConfigViewModel, graph: AppGraph) {
    Section("Controller") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = vm.locationId,
                onValueChange = { vm.locationId = it.filter(Char::isDigit) },
                label = { Text("Location ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.loadDevices(graph) }, enabled = !vm.busy && vm.locationId.isNotBlank()) { Text("Load") }
        }
        val devices = vm.devices
        if (devices != null) {
            if (devices.isEmpty()) {
                Text("No controllers at this location.")
            } else {
                if (devices.size > 8) {
                    OutlinedTextField(
                        value = vm.filter,
                        onValueChange = { vm.filter = it },
                        label = { Text("Filter") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                devices.filter { vm.filter.isBlank() || it.label.contains(vm.filter, ignoreCase = true) }.forEach { d ->
                    val chosen = vm.selected?.id == d.id
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !vm.busy) { vm.select(graph, d) },
                        colors = if (chosen) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
                    ) {
                        Text(d.label, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceConfigCard(vm: FetchConfigViewModel, graph: AppGraph, device: RemoteDevice, onSaved: (String) -> Unit) {
    Section("Config of ${device.hash ?: device.label}") {
        val r = vm.remote
        when {
            r == null && vm.busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Asking the server…")
            }
            r == null -> Unit
            else -> {
                if (r.content != null) {
                    Text("Received ${r.fetchedAt ?: ""}", style = MaterialTheme.typography.bodyMedium)
                    r.resultTtlDays?.let { Text("The server keeps it for $it days.", style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { vm.save(graph, onSaved) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Save as profile and edit")
                    }
                } else {
                    Text("The server has no config of this controller yet.")
                }
                when (r.requestStatus) {
                    "pending" -> Text("Requested; the controller has not picked the request up yet. It checks every 4 h (every 15 s in maintenance mode).")
                    "sent" -> Text("The controller has the request and is sending its config.")
                    null -> OutlinedButton(onClick = { vm.request(graph) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (r.content == null) "Request the config" else "Request a new copy")
                    }
                    else -> Text("Request status: ${r.requestStatus}")
                }
                r.lastCommandPoll?.let { Text("Last check by the controller: $it", style = MaterialTheme.typography.bodySmall) }
                if (r.requestStatus != null) {
                    OutlinedButton(onClick = { vm.refresh(graph) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
                }
            }
        }
    }
}
