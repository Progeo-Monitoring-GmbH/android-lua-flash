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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.progeo.core.ui.MessageCard
import com.progeo.core.ui.Section
import com.progeo.core.ui.copyToClipboard
import com.progeo.core.ui.theme.StatusColors
import com.progeo.luaflasher.graph
import com.progeo.luaflasher.router.CheckItem
import com.progeo.luaflasher.router.MobileChecks
import com.progeo.luaflasher.router.Phase
import com.progeo.luaflasher.router.RouterRecord
import com.progeo.luaflasher.router.RouterUi
import com.progeo.luaflasher.router.SetupStep
import com.progeo.luaflasher.router.StepLog
import com.progeo.luaflasher.router.Via
import com.progeo.luaflasher.router.firmwareShort
import com.progeo.luaflasher.router.mask
import com.progeo.teltonika.api.SignalQuality
import com.progeo.teltonika.config.SectionResult
import com.progeo.teltonika.firmware.FirmwareCheck
import kotlinx.serialization.json.JsonPrimitive

/** Portal permission to read router passwords in the app. */
private const val PERMISSION_CREDENTIALS = "module_routers_credentials"

/** One router: connecting, the setup checklist and its status. */
@Composable
fun RouterScreen(onBack: () -> Unit, onAntenna: () -> Unit) {
    val graph = LocalContext.current.graph
    val controller = graph.router
    val s by controller.state.collectAsState()
    val routers by graph.routerStore.routers.collectAsState()
    val record = routers.firstOrNull { it.serial == s.serial }

    var confirmClose by remember { mutableStateOf(false) }

    LaunchedEffect(s.phase) { if (s.phase == Phase.IDLE) onBack() }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Stop \"${s.busy}\"?") },
            text = { Text("Closing now stops the running step. During a firmware installation, leave the router powered and reconnect later.") },
            confirmButton = { TextButton(onClick = { confirmClose = false; controller.close() }) { Text("Close anyway") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep open") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text(s.device?.modelName ?: record?.model ?: "Router", style = MaterialTheme.typography.titleMedium)
                Text(s.serial ?: "", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { if (s.busy != null) confirmClose = true else controller.close() }) {
                Icon(Icons.Default.Close, contentDescription = "Close session")
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            s.busy?.let { busy ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(busy, style = MaterialTheme.typography.bodyMedium)
                        s.progress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
                            ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            s.error?.let { item { MessageCard(it) } }
            s.message?.let { item { MessageCard(it, error = false, modifier = Modifier.clickable { controller.dismissMessage() }) } }

            if (s.phase == Phase.CONNECTING || s.phase == Phase.FAILED) {
                item { ConnectionLog(s.log) }
            }
            if (s.phase == Phase.FAILED) {
                item { FailedActions(s) }
            }
            if (s.phase == Phase.CONNECTED) {
                item { SetupChecklist(s, record) }
                item { SignalSection(s, onAntenna) }
                item { SystemSection(s, record) }
                item { MobileSection(s) }
                item { NetworkSection(s, record) }
                item { MoreSection(s) }
            }
        }
    }
}

@Composable
private fun ConnectionLog(log: List<StepLog>) {
    Section("Connection") {
        log.forEach { step ->
            Row(verticalAlignment = Alignment.Top) {
                when (step.ok) {
                    null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    true -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusColors.ok)
                    false -> Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(step.text, style = MaterialTheme.typography.bodyMedium)
                    step.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun FailedActions(s: RouterUi) {
    val controller = LocalContext.current.graph.router
    var password by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.needsPassword) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Router password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { controller.submitPassword(password) }, enabled = password.isNotEmpty() && s.busy == null) { Text("Log in") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.retry(Via.WIFI) }, enabled = s.busy == null) { Text("Retry via Wi-Fi") }
            OutlinedButton(onClick = { controller.retry(Via.CABLE) }, enabled = s.busy == null) { Text("Retry via cable") }
        }
    }
}

@Composable
private fun SetupChecklist(s: RouterUi, record: RouterRecord?) {
    val graph = LocalContext.current.graph
    val controller = graph.router
    val idle = s.busy == null
    var confirmPassword by remember { mutableStateOf(false) }
    var confirmFirmware by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }

    Section("Setup") {
        StepRow(SetupStep.BACKUP, s, "Saves the router's configuration before any change.") {
            TextButton(onClick = { controller.backup() }, enabled = idle) { Text("Save") }
        }
        StepRow(
            SetupStep.FIRMWARE, s,
            when (val c = s.firmwareCheck) {
                null -> "Installed: ${s.device?.firmware.firmwareShort() ?: "unknown"}"
                FirmwareCheck.AlreadyInstalled -> "The approved version is installed (${s.device?.firmware.firmwareShort()})."
                is FirmwareCheck.Ok -> "${s.device?.firmware.firmwareShort()} → ${s.firmwareImage?.version.firmwareShort()}" + if (c.downgrade) " (older version!)" else ""
                is FirmwareCheck.Refused -> c.reason
            },
        ) {
            if (s.firmwareCheck is FirmwareCheck.Ok) {
                TextButton(onClick = { confirmFirmware = true }, enabled = idle) { Text("Install") }
            } else {
                TextButton(onClick = { controller.checkFirmware() }, enabled = idle) { Text("Check") }
            }
        }
        StepRow(
            SetupStep.TEMPLATE, s,
            record?.templateId?.let { "Last applied: $it v${record.templateVersion}" } ?: "Our settings for this region and model.",
        ) {
            TextButton(onClick = { controller.loadTemplates() }, enabled = idle) { Text("Choose") }
        }
        TemplatePanel(s, idle, onApply = { confirmApply = true })
        StepRow(
            SetupStep.PASSWORD, s,
            when {
                record?.pendingPassword != null -> "A password change was not confirmed. Set it again."
                record?.onFactoryPassword != false -> "The router uses its factory password (printed on its label)."
                else -> "The router has its own password."
            },
        ) {
            TextButton(onClick = { confirmPassword = true }, enabled = idle) { Text(if (record?.onFactoryPassword == false) "Renew" else "Set") }
        }
        StepRow(SetupStep.MOBILE, s, "SIM, network, signal and mobile data.") {
            TextButton(onClick = { controller.mobileCheck() }, enabled = idle) { Text("Check") }
        }
        if (SetupStep.MOBILE in s.done || s.modems.isNotEmpty()) {
            MobileChecks.of(s.modems, s.interfaces).forEach { CheckLine(it) }
        }
        StepRow(SetupStep.REPORT, s, "Installation report for the server.") {
            TextButton(onClick = { reportOpen = true }, enabled = idle) { Text("Send") }
        }
    }

    if (confirmPassword) {
        AlertDialog(
            onDismissRequest = { confirmPassword = false },
            title = { Text("Set a new password?") },
            text = {
                Text(
                    "The app creates a random password, stores it on the phone and the server first, then sets it on the router " +
                        "and logs in with it. Afterwards the label's password no longer works; colleagues get the new one from the server.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmPassword = false; controller.rotatePassword() }) { Text("Set password") } },
            dismissButton = { TextButton(onClick = { confirmPassword = false }) { Text("Cancel") } },
        )
    }
    if (confirmFirmware) {
        val downgrade = (s.firmwareCheck as? FirmwareCheck.Ok)?.downgrade == true
        AlertDialog(
            onDismissRequest = { confirmFirmware = false },
            title = { Text("Install ${s.firmwareImage?.version.firmwareShort()}?") },
            text = {
                Text(
                    (if (downgrade) "This is OLDER than the installed version. " else "") +
                        "The router keeps its settings, installs the firmware and restarts. This takes several minutes: " +
                        "keep it powered and stay close. A backup is saved first.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmFirmware = false; controller.installFirmware() }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { confirmFirmware = false }) { Text("Cancel") } },
        )
    }
    if (confirmApply) {
        val plan = s.plan
        AlertDialog(
            onDismissRequest = { confirmApply = false },
            title = { Text("Apply ${plan?.changeCount ?: 0} changes?") },
            text = {
                Text(
                    "A backup is saved first." +
                        if (plan?.affectsConnection == true) {
                            " Some changes affect the connection (Wi-Fi or address): they are applied last and the app reconnects with the new values."
                        } else {
                            ""
                        },
                )
            },
            confirmButton = { TextButton(onClick = { confirmApply = false; controller.applyPlan() }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("Cancel") } },
        )
    }
    if (reportOpen) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reportOpen = false },
            title = { Text("Installation report") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Router data, firmware, configuration and signal values are added automatically.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (site, mounting, antenna)") }, minLines = 3)
                }
            },
            confirmButton = { TextButton(onClick = { reportOpen = false; controller.sendReport(note) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { reportOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StepRow(step: SetupStep, s: RouterUi, detail: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (step in s.done) {
            Icon(Icons.Default.CheckCircle, contentDescription = "done", tint = StatusColors.ok)
        } else {
            Icon(Icons.Default.RadioButtonUnchecked, contentDescription = "open", tint = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(step.title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        action()
    }
}

@Composable
private fun TemplatePanel(s: RouterUi, idle: Boolean, onApply: () -> Unit) {
    val controller = LocalContext.current.graph.router
    val plan = s.plan
    if (s.templates.isEmpty() && plan == null) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.templates.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = idle) { controller.planTemplate(t) }) {
                    RadioButton(selected = plan?.template?.id == t.id, onClick = { controller.planTemplate(t) }, enabled = idle)
                    Text("${t.title} (v${t.version}${t.region?.let { ", $it" } ?: ""})")
                }
            }
            if (plan != null) {
                HorizontalDivider()
                when {
                    plan.inSync -> Text("The router matches ${plan.template.title}.", color = StatusColors.ok)
                    else -> Text("${plan.changeCount} differences", style = MaterialTheme.typography.titleSmall)
                }
                plan.sections.forEach { sp ->
                    sp.error?.let { Text("${sp.section.title}: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    sp.changes.forEach { c ->
                        fun show(e: kotlinx.serialization.json.JsonElement?) =
                            if (c.secret) mask((e as? JsonPrimitive)?.content) else ((e as? JsonPrimitive)?.content ?: e?.toString() ?: "–").ifEmpty { "–" }
                        Text(
                            "${sp.section.title} · ${c.key}: ${show(c.current)} → ${show(c.desired)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                s.applyResult?.results?.filterNot { it is SectionResult.Applied || it is SectionResult.AppliedUnverified }?.forEach { r ->
                    val text = when (r) {
                        is SectionResult.Failed -> "${r.section.title}: ${r.message}"
                        is SectionResult.Mismatch -> "${r.section.title}: the router kept other values for ${r.keys.joinToString()}"
                        else -> ""
                    }
                    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (!plan.inSync && plan.changeCount > 0) {
                    Button(onClick = onApply, enabled = idle) { Text("Apply ${plan.changeCount} changes") }
                }
            }
        }
    }
}

@Composable
private fun CheckLine(item: CheckItem) {
    Row(Modifier.padding(start = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        when (item.ok) {
            true -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusColors.ok, modifier = Modifier.size(18.dp))
            false -> Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            null -> Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(item.title + (item.detail?.let { ": $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
    }
}

fun qualityColor(q: SignalQuality, fallback: Color): Color = when (q) {
    SignalQuality.EXCELLENT, SignalQuality.GOOD -> StatusColors.ok
    SignalQuality.FAIR -> StatusColors.warn
    SignalQuality.POOR, SignalQuality.NONE -> fallback
}

@Composable
private fun SignalSection(s: RouterUi, onAntenna: () -> Unit) {
    val modem = s.modems.firstOrNull()
    Section("Signal") {
        if (modem == null) {
            Text("No modem reported.", style = MaterialTheme.typography.bodySmall)
        } else {
            val q = modem.signal.quality
            Text(q.label, style = MaterialTheme.typography.headlineSmall, color = qualityColor(q, MaterialTheme.colorScheme.error))
            Text(
                listOfNotNull(
                    modem.signal.rsrp?.let { "RSRP $it dBm" },
                    modem.signal.sinr?.let { "SINR %.0f dB".format(it) },
                    modem.signal.rsrq?.let { "RSRQ %.0f dB".format(it) },
                    modem.signal.rssi?.let { "RSSI $it dBm" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(listOfNotNull(modem.operator, modem.technology, modem.band).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onAntenna) {
            Icon(Icons.Default.SettingsInputAntenna, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Antenna mode")
        }
    }
}

@Composable
private fun Field(name: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row {
        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(130.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SystemSection(s: RouterUi, record: RouterRecord?) {
    val context = LocalContext.current
    val graph = context.graph
    var canReveal by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        canReveal = runCatching { graph.backend.modulePermissions().has(PERMISSION_CREDENTIALS) }.getOrDefault(false)
    }
    val d = s.device
    Section("Router") {
        Field("Model", d?.modelName ?: record?.model)
        Field("Product code", d?.productCode ?: record?.productCode)
        Field("Serial number", d?.serial ?: record?.serial)
        Field("MAC", d?.mac ?: record?.mac)
        Field("IMEI", s.modems.firstOrNull()?.imei ?: record?.imei)
        Field("Firmware", d?.firmware ?: record?.firmware)
        Field("Hardware", d?.hardwareRevision)
        Field("Uptime", d?.uptimeSeconds?.let { "%dd %02dh %02dm".format(it / 86400, it / 3600 % 24, it / 60 % 60) })
        Field("Logged in with", s.loginKind?.label)
        Field("Certificate", record?.certFingerprint?.take(23)?.let { "$it…" })
        val password = record?.password ?: record?.factoryPassword
        if (password != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Password", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(130.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (reveal) password else mask(password), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                if (canReveal) {
                    TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
                    TextButton(onClick = { copyToClipboard(context, "Router password", password, sensitive = true) }) { Text("Copy") }
                }
            }
        }
    }
}

@Composable
private fun MobileSection(s: RouterUi) {
    if (s.modems.isEmpty()) return
    Section("SIM and mobile") {
        s.modems.forEach { m ->
            Field("Modem", listOfNotNull(m.name, m.id).joinToString(" "))
            Field("Active SIM", m.activeSim)
            Field("SIM state", m.simState)
            Field("PIN", m.pinState)
            Field("ICCID", m.iccid)
            Field("IMSI", m.imsi)
            Field("Operator", m.operator)
            Field("Network", listOfNotNull(m.technology, m.band, m.registration).joinToString(" · "))
            Field("Cell", m.cellId)
            Field("Temperature", m.temperature?.let { "%.0f °C".format(it) })
        }
    }
}

@Composable
private fun NetworkSection(s: RouterUi, record: RouterRecord?) {
    val graph = LocalContext.current.graph
    val profiles by graph.profiles.profiles.collectAsState()
    var chooseProfile by remember { mutableStateOf(false) }
    Section("Network") {
        s.interfaces.forEach { i ->
            val state = when (i.up) {
                true -> "up"
                false -> "down"
                null -> "?"
            }
            Field(i.name, listOfNotNull(state, i.ipv4, i.protocol, i.rxBytes?.let { "rx ${it / 1024} KB" }, i.txBytes?.let { "tx ${it / 1024} KB" }).joinToString(" · "))
        }
        Text("Connected devices (${s.leases.size})", style = MaterialTheme.typography.bodyMedium)
        s.leases.forEach { l -> Field(l.hostname ?: "device", listOfNotNull(l.ip, l.mac).joinToString(" · ")) }
        val wifi = record?.access ?: record?.factoryAccess
        if (wifi?.ssid != null) {
            OutlinedButton(onClick = { chooseProfile = true }, enabled = s.busy == null) { Text("Use this Wi-Fi in a microBOX profile") }
        }
    }
    if (chooseProfile) {
        AlertDialog(
            onDismissRequest = { chooseProfile = false },
            title = { Text("Which microBOX profile?") },
            text = {
                if (profiles.isEmpty()) {
                    Text("No profiles on the phone. Create one in Profiles first.")
                } else {
                    LazyColumn {
                        items(profiles, key = { it.id }) { p ->
                            Text(
                                p.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        chooseProfile = false
                                        graph.router.copyWifiToProfile(p.id)
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooseProfile = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MoreSection(s: RouterUi) {
    val controller = LocalContext.current.graph.router
    var confirmReboot by remember { mutableStateOf(false) }
    Section("More") {
        s.statusErrors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { controller.refresh() }, enabled = s.busy == null) { Text("Refresh") }
            OutlinedButton(onClick = { confirmReboot = true }, enabled = s.busy == null) { Text("Restart router") }
        }
    }
    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            title = { Text("Restart the router?") },
            text = { Text("The connection drops for one to two minutes; the app reconnects.") },
            confirmButton = { TextButton(onClick = { confirmReboot = false; controller.reboot() }) { Text("Restart") } },
            dismissButton = { TextButton(onClick = { confirmReboot = false }) { Text("Cancel") } },
        )
    }
}
