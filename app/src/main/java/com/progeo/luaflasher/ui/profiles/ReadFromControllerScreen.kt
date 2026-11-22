package com.progeo.luaflasher.ui.profiles

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.progeo.core.ui.MessageCard
import com.progeo.core.ui.Section
import com.progeo.luaflasher.AppGraph
import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.graph
import kotlinx.coroutines.launch

class ReadFromControllerViewModel : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Reads config/device_config.lua from the connected board and stores it as a profile. */
    fun read(graph: AppGraph, onSaved: (String) -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val config = graph.boardReader.read { status = it }
                onSaved(graph.profiles.saveFromBoard(config).id)
            } catch (e: FlasherException) {
                error = e.userMessage
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                status = null
                busy = false
            }
        }
    }
}

/** Reads the config of the board on the USB adapter and saves it as a profile (named after its device hash). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadFromControllerScreen(onBack: () -> Unit, onSaved: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val vm: ReadFromControllerViewModel = viewModel()
    val devices by graph.usb.devices.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Read from controller") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
                    onClick = { vm.read(graph, onSaved) },
                    enabled = !vm.busy && devices.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Read config from board") }
                vm.status?.let { status ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(status)
                    }
                }
                vm.error?.let { MessageCard(it) }
            }
        }
    }
}
