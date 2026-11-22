package com.progeo.luaflasher.ui.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.progeo.luaflasher.AppGraph
import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.flash.FlashState
import com.progeo.luaflasher.graph
import com.progeo.core.ui.MessageCard
import com.progeo.luaflasher.ui.shareText
import com.progeo.luaflasher.usb.AndroidSerialPort
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Serial monitor (PLAN.md section 4, step 7): 115200, or 74880 for the ROM boot text. */
class MonitorViewModel : ViewModel() {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines = _lines.asStateFlow()
    var baud by mutableStateOf(115200)
    var connected by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var label by mutableStateOf<String?>(null)

    private var port: AndroidSerialPort? = null
    private var reader: Job? = null
    private val partial = StringBuilder()
    private val all = StringBuilder()

    fun connect(graph: AppGraph) {
        error = null
        viewModelScope.launch {
            try {
                val device = graph.usb.firstDevice()
                graph.usb.requestPermission(device)
                val p = graph.usb.open(device, baud)
                port = p
                label = device.label
                connected = true
                reader = launch {
                    try {
                        while (true) {
                            val data = p.read(1_000)
                            if (data.isNotEmpty()) append(String(data, Charsets.ISO_8859_1))
                        }
                    } catch (e: Exception) {
                        error = "USB connection lost."
                        disconnect()
                    }
                }
            } catch (e: FlasherException) {
                error = e.userMessage
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun disconnect() {
        reader?.cancel()
        runCatching { port?.close() }
        port = null
        connected = false
    }

    fun changeBaud(value: Int) {
        baud = value
        val p = port ?: return
        append("\n--- $value baud ---\n")
        viewModelScope.launch { runCatching { p.setBaudRate(value) } }
    }

    fun send(text: String) {
        val p = port ?: return
        viewModelScope.launch { runCatching { p.write((text + "\n").toByteArray(Charsets.ISO_8859_1)) } }
    }

    /** Classic DTR/RTS reset; does nothing on boards without the circuit. */
    fun pulseReset() {
        val p = port ?: return
        viewModelScope.launch {
            p.setDtr(false)
            p.setRts(true)
            delay(100)
            p.setRts(false)
        }
    }

    fun clear() {
        partial.setLength(0)
        all.setLength(0)
        partialLine = ""
        _lines.value = emptyList()
    }

    fun fullText(): String = all.toString() + partial

    private fun append(text: String) {
        for (c in text) {
            when {
                c == '\n' -> {
                    _lines.value = (_lines.value + partial.toString()).takeLast(MAX_LINES)
                    all.append(partial).append('\n')
                    partial.setLength(0)
                }
                c == '\r' -> Unit
                c.code in 0x20..0x7E || c == '\t' -> partial.append(c)
                else -> partial.append('·')
            }
        }
        if (all.length > MAX_CHARS) all.delete(0, all.length - MAX_CHARS)
        // The unfinished line, e.g. the "> " prompt.
        partialLine = partial.toString()
    }

    var partialLine by mutableStateOf("")
        private set

    override fun onCleared() = disconnect()

    companion object {
        private const val MAX_LINES = 2_000
        private const val MAX_CHARS = 512 * 1024
    }
}

@Composable
fun MonitorScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val vm: MonitorViewModel = viewModel()
    val lines by vm.lines.collectAsState()
    val flash by graph.flash.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val busy = flash != FlashState.Idle

    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Serial monitor", style = MaterialTheme.typography.headlineMedium)
        if (busy) MessageCard("A flash job holds the USB port. Finish or cancel it first.", error = false)
        vm.error?.let { MessageCard(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.connected) {
                OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
            } else {
                Button(onClick = { vm.connect(graph) }, enabled = !busy) { Text("Connect") }
            }
            listOf(115200, 74880).forEach { b ->
                FilterChip(selected = vm.baud == b, onClick = { vm.changeBaud(b) }, label = { Text("$b") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.clear() }) { Text("Clear") }
            TextButton(onClick = { shareText(context, "serial-log.txt", vm.fullText()) }, enabled = lines.isNotEmpty()) { Text("Save / share") }
            TextButton(onClick = { vm.pulseReset() }, enabled = vm.connected) { Text("DTR/RTS reset") }
        }
        vm.label?.takeIf { vm.connected }?.let { Text("Connected: $it @ ${vm.baud}", style = MaterialTheme.typography.bodySmall) }
        Card(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
                itemsIndexed(lines) { _, line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                if (vm.partialLine.isNotEmpty()) {
                    item { Text(vm.partialLine, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text("Lua, e.g. print(node.heap())") },
            singleLine = true,
            enabled = vm.connected,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSend = {
                vm.send(input)
                input = ""
            }),
            trailingIcon = {
                IconButton(onClick = {
                    vm.send(input)
                    input = ""
                }, enabled = vm.connected) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
