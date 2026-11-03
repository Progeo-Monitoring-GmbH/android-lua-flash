package com.progeo.luaflasher.ui.flash

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.config.Severity
import com.progeo.luaflasher.core.nodemcu.InstallMode
import com.progeo.luaflasher.core.nodemcu.InstallStep
import com.progeo.luaflasher.data.AppSettings
import com.progeo.luaflasher.flash.FlashState
import com.progeo.luaflasher.graph
import com.progeo.luaflasher.ui.MessageCard
import com.progeo.luaflasher.ui.Section
import com.progeo.luaflasher.ui.shareText
import com.progeo.luaflasher.ui.theme.StatusColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val state by graph.flash.state.collectAsState()
    val setup: FlashSetupViewModel = viewModel()

    // Keep the screen on while a job is open (PLAN.md section 4).
    val activity = context as? Activity
    DisposableEffect(state != FlashState.Idle) {
        if (state != FlashState.Idle) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler(enabled = state.isWorking) { /* never leave a running write by accident */ }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (state == FlashState.Idle) "Flash a board" else "Flashing") },
            navigationIcon = {
                IconButton(onClick = onClose, enabled = !state.isWorking) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        if (state == FlashState.Idle) {
            SetupContent(setup)
        } else {
            WizardContent(state, setup, onClose)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupContent(vm: FlashSetupViewModel) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val bundles by graph.bundles.bundles.collectAsState()
    val profiles by graph.profiles.profiles.collectAsState()
    val settings by graph.settings.settings.collectAsState(AppSettings())
    val devices by graph.usb.devices.collectAsState()

    // Only complete, verified bundles; unsigned only in developer mode.
    val usable = bundles.filter { it.signed || settings.developerMode }
    LaunchedEffect(usable) {
        if (vm.bundleId == null || usable.none { it.id == vm.bundleId }) vm.bundleId = usable.maxByOrNull { it.created ?: "" }?.id
    }
    LaunchedEffect(profiles) {
        if (vm.profileId == null || profiles.none { it.id == vm.profileId }) vm.profileId = profiles.firstOrNull()?.id
    }
    LaunchedEffect(vm.bundleId) { vm.loadTemplate(graph) }

    val issues = vm.issues(graph)
    val errors = issues.filter { it.severity == Severity.ERROR }
    val selectedBundle = usable.firstOrNull { it.id == vm.bundleId }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (usable.isEmpty()) {
            MessageCard("No verified bundle on the phone. Sync while online first.")
            return@Column
        }

        Section("Bundle") {
            Dropdown(
                label = "Release",
                options = usable.map { it.id to "${it.release} · fw ${it.firmware}${if (it.signed) "" else " · UNSIGNED"}" },
                selected = vm.bundleId,
                onSelect = { vm.bundleId = it },
            )
            if (selectedBundle?.signed == false) Text("Unsigned developer bundle", color = MaterialTheme.colorScheme.error)
        }

        Section("Device") {
            Dropdown(
                label = "Profile",
                options = listOf<Pair<String?, String>>(null to "(no profile: template defaults only)") + profiles.map { it.id to it.name },
                selected = vm.profileId,
                onSelect = { vm.profileId = it },
            )
            OutlinedTextField(
                value = vm.deviceHash,
                onValueChange = { vm.deviceHash = it.trim() },
                label = { Text("Device hash") },
                supportingText = { Text("From the label on the board; scan its QR code or type it.") },
                singleLine = true,
                isError = vm.deviceHash.isBlank(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = {
                        val options = GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_CODE_128)
                            .build()
                        GmsBarcodeScanning.getClient(context, options).startScan()
                            .addOnSuccessListener { code -> code.rawValue?.let { vm.deviceHash = extractHash(it) } }
                            .addOnFailureListener { vm.error = "Scanner not available: ${it.message}" }
                    }) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR code") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Section("Mode") {
            ModeOption(InstallMode.FULL, vm, "Full install", "Firmware + files, filesystem formatted. Needs the boot jumper.")
            ModeOption(InstallMode.UPDATE, vm, "Update", "Files only, if the board already runs firmware ${selectedBundle?.firmware ?: ""}.")
            ModeOption(InstallMode.CONFIG_ONLY, vm, "Config only", "Only config/device_config.lua, e.g. a new device hash or token.")
            CheckRow(vm.keepWifi, { vm.keepWifi = it }, "Keep Wi-Fi credentials from the setup portal")
            if (vm.mode == InstallMode.UPDATE) {
                CheckRow(vm.ignoreFirmwareMismatch, { vm.ignoreFirmwareMismatch = it }, "Expert: update even if fw.version differs")
            }
        }

        Section("Device config") {
            var tab by remember { mutableStateOf(if (vm.expertEdit) 1 else 0) }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Preview") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Expert") })
            }
            val generated = vm.generatedLua(graph)
            if (tab == 0) {
                issues.forEach { Text("${if (it.severity == Severity.ERROR) "⚠" else "ⓘ"} ${it.path}: ${it.message}", color = if (it.severity == Severity.ERROR) MaterialTheme.colorScheme.error else StatusColors.warn) }
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        if (vm.expertEdit) vm.configText else generated,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else {
                CheckRow(vm.expertEdit, { on ->
                    vm.expertEdit = on
                    if (on && vm.configText.isEmpty()) vm.configText = generated
                }, "Write my own text instead of the generated config")
                if (vm.expertEdit) {
                    OutlinedTextField(
                        value = vm.configText,
                        onValueChange = { vm.configText = it },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp),
                    )
                    TextButton(onClick = { vm.configText = generated }) { Text("Reset to generated") }
                }
            }
        }

        Text(
            devices.firstOrNull()?.let { "Adapter: ${it.label}" } ?: "No USB serial adapter found. Use an OTG adapter and a data cable.",
            color = if (devices.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        vm.error?.let { MessageCard(it) }

        Button(
            onClick = {
                vm.error = null
                vm.starting = true
                scope.launch {
                    try {
                        graph.flash.start(vm.job(graph), graph.usb.firstDevice())
                    } catch (e: FlasherException) {
                        vm.error = e.userMessage
                    } catch (e: Exception) {
                        vm.error = e.message ?: e.javaClass.simpleName
                    } finally {
                        vm.starting = false
                    }
                }
            },
            enabled = !vm.starting && vm.bundleId != null && vm.template != null &&
                (vm.expertEdit || errors.isEmpty()) && vm.deviceHash.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start") }
    }
}

/** Labels often carry a URL or "key=value"; take the last path or value part. */
private fun extractHash(raw: String): String {
    val trimmed = raw.trim()
    Regex("(?:device_hash|hash)=([A-Za-z0-9_-]+)").find(trimmed)?.let { return it.groupValues[1] }
    return if (trimmed.contains('/')) trimmed.trimEnd('/').substringAfterLast('/') else trimmed
}

@Composable
private fun ModeOption(mode: InstallMode, vm: FlashSetupViewModel, title: String, detail: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = vm.mode == mode, onClick = { vm.mode = mode }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = vm.mode == mode, onClick = { vm.mode = mode })
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    onSelect(value)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun WizardContent(state: FlashState, setup: FlashSetupViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val log by graph.flash.log.collectAsState()
    var showLog by remember { mutableStateOf(false) }
    val session = graph.flash

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state) {
            FlashState.AwaitBootMode -> {
                StepHeader("Step 1 · Bootloader mode")
                BoardIllustration(jumperSet = true)
                Text("1. Set the boot jumper (GPIO0 to GND).\n2. Press RST.\n3. Tap Continue right away.", style = MaterialTheme.typography.bodyLarge)
                Text("The board needs its own power supply; the phone does not power it.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { session.proceed() }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                OutlinedButton(onClick = { session.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            FlashState.Syncing -> {
                StepHeader("Step 1 · Connecting")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Talking to the ESP8266 bootloader…")
                }
            }
            is FlashState.Writing -> {
                StepHeader("Step 2 · Writing firmware")
                Text(state.chip, style = MaterialTheme.typography.titleMedium)
                val p = state.progress
                LinearProgressIndicator(progress = { p?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (p == null) "Erasing…" else "${(p.fraction * 100).toInt()} % · ${p.segment}" + (p.etaSeconds?.let { " · about ${it}s left" } ?: ""),
                )
                Text("Do not unplug the cable. Takes about a minute.", style = MaterialTheme.typography.bodySmall)
            }
            is FlashState.AwaitNormalMode -> {
                val full = setup.mode == InstallMode.FULL
                StepHeader(if (full) "Step 3 · Normal mode" else "Board in normal mode")
                state.chip?.let { Text("Firmware written to $it", color = StatusColors.ok) }
                BoardIllustration(jumperSet = false)
                Text(
                    if (full) "1. Remove the boot jumper.\n2. Press RST.\n3. Tap Continue." else "Make sure the boot jumper is removed and the board is powered and running, then tap Continue.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = { session.proceed() }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                OutlinedButton(onClick = { session.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            is FlashState.Installing -> {
                StepHeader("Step 4 · Installing files")
                val current = state.progress.step
                if (current == InstallStep.CHECK) {
                    Text("Waiting for NodeMCU. The first boot after a firmware write formats the filesystem; this can take up to a minute.")
                }
                InstallStep.entries.forEach { step ->
                    val done = step.ordinal < current.ordinal
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            done -> Icon(Icons.Default.CheckCircle, null, tint = StatusColors.ok)
                            step == current -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else -> Icon(Icons.Default.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(step.label + if (step == current && state.progress.detail.isNotEmpty()) " (${state.progress.detail})" else "")
                    }
                    if (step == current && state.progress.fraction > 0f) {
                        LinearProgressIndicator(progress = { state.progress.fraction }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            is FlashState.Done -> {
                val ok = state.result.success
                StepHeader("Result")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Error, null, tint = if (ok) StatusColors.ok else MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when {
                            ok -> "The board booted the new code."
                            state.result.bootFailed != null -> "Boot failed:${state.result.bootFailed}"
                            else -> "No 'BOOT heap:' within 20 s. Check the log."
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text("Boot log (first 20 s)", style = MaterialTheme.typography.titleSmall)
                Card(Modifier.fillMaxWidth()) {
                    Text(state.result.bootLog.filter { it == '\n' || it.code in 0x20..0x7E }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
                }
                Button(onClick = {
                    session.cancel()
                    setup.nextBoard()
                }, modifier = Modifier.fillMaxWidth()) { Text("Flash the next board") }
                OutlinedButton(onClick = { shareText(context, "flash-log.txt", log) }, modifier = Modifier.fillMaxWidth()) { Text("Share log") }
                OutlinedButton(onClick = {
                    session.cancel()
                    onClose()
                }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
            is FlashState.Failed -> {
                StepHeader("Something went wrong")
                MessageCard(state.message)
                state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                Button(onClick = { session.retry() }, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
                OutlinedButton(onClick = { shareText(context, "flash-log.txt", log) }, modifier = Modifier.fillMaxWidth()) { Text("Share log") }
                OutlinedButton(onClick = { session.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            FlashState.Idle -> Unit
        }

        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
        if (showLog) {
            Card(Modifier.fillMaxWidth()) {
                Text(log.takeLast(8_000), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun StepHeader(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
}
