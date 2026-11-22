package com.progeo.luaflasher.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.progeo.core.qr.CodeScanDialog
import com.progeo.core.ui.MessageCard
import com.progeo.core.ui.copyToClipboard
import com.progeo.luaflasher.AppGraph
import com.progeo.luaflasher.core.config.ConfigCode
import com.progeo.luaflasher.core.config.ConfigTemplate
import com.progeo.luaflasher.core.config.ConfigValidator
import com.progeo.luaflasher.core.config.DeviceConfigBuilder
import com.progeo.luaflasher.core.config.LuaJson
import com.progeo.luaflasher.core.config.Severity
import com.progeo.luaflasher.data.Profile
import com.progeo.luaflasher.ui.config.ConfigSectionCards
import com.progeo.luaflasher.ui.config.sectionsOf
import com.progeo.luaflasher.graph
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Editing a profile. Profiles come from a config QR code (a new profile opens the scanner) or
 * are read from a board over USB (Read from controller); here every value can be changed.
 */
class ProfileEditorViewModel : ViewModel() {
    var loaded by mutableStateOf(false)
    var template by mutableStateOf<ConfigTemplate?>(null)
    var name by mutableStateOf("")
    var config by mutableStateOf(JsonObject(emptyMap()))
    var boardReadAt: String? = null
    var profileId: String? = null
    var saving by mutableStateOf(false)

    /** The JSON tab's text while it differs from [config] (being edited); null shows [config]. */
    var jsonText by mutableStateOf<String?>(null)
        private set
    var jsonError by mutableStateOf<String?>(null)
        private set

    suspend fun load(graph: AppGraph, id: String?) {
        if (loaded) return
        val t = graph.currentTemplate()
        template = t
        val profile = graph.profiles.get(id)
        if (profile != null) {
            profileId = profile.id
            name = profile.name
            config = profile.config
            boardReadAt = profile.boardReadAt
        } else {
            // A new profile: the template's defaults, completed by the scanned config code.
            config = t.defaults()
        }
        loaded = true
    }

    /** A section was saved in its dialog on the Values tab (null removes it). */
    fun setSection(name: String, values: JsonObject?) {
        replaceConfig(LuaJson.with(config, name, values))
    }

    fun jsonShown(): String = jsonText ?: prettyJson.encodeToString(JsonObject.serializer(), config)

    /** Free editing in the JSON tab: every valid JSON object becomes the config right away. */
    fun setJson(text: String) {
        jsonText = text
        val parsed = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            jsonError = e.message?.lineSequence()?.firstOrNull()?.take(200) ?: "not valid JSON"
            return
        }
        if (parsed !is JsonObject) {
            jsonError = "must be a JSON object: { … }"
            return
        }
        replaceConfig(parsed)
        jsonText = text
    }

    /** Leaving the JSON tab: valid text is shown formatted next time; broken text stays for fixing. */
    fun leaveJson() {
        if (jsonError == null) jsonText = null
    }

    /** The file as it is flashed (read-only Lua tab). */
    fun lua(): String = template?.let { runCatching { DeviceConfigBuilder.lua(it, config) }.getOrElse { e -> "-- ${e.message}" } }.orEmpty()

    // ---- new profile: the config from a QR code

    var scanning by mutableStateOf(false)

    /** A valid config code was scanned; a new profile can only be saved after that. */
    var hasScan by mutableStateOf(false)
        private set

    /** What the last scan did: message and whether it is an error. */
    var scanResult by mutableStateOf<Pair<String, Boolean>?>(null)
        private set

    /** A valid config in the code is added to the profile: its values replace those it sets. */
    fun scanned(text: String) {
        scanning = false
        val t = template ?: return
        scanResult = when (val r = ConfigCode.parse(text, t)) {
            is ConfigCode.Result.Invalid -> r.reason to true
            is ConfigCode.Result.Valid -> {
                replaceConfig(LuaJson.merge(config, r.config))
                hasScan = true
                if (name.isBlank()) name = (LuaJson.get(config, DeviceConfigBuilder.DEVICE_HASH) as? JsonPrimitive)?.content.orEmpty()
                val shown = r.paths.take(8).joinToString() + if (r.paths.size > 8) ", …" else ""
                "Added ${r.paths.size} value${if (r.paths.size == 1) "" else "s"} from the QR code: $shown. Check them and save." to false
            }
        }
    }

    /** A whole new config: unfinished JSON text is dropped. */
    private fun replaceConfig(newConfig: JsonObject) {
        config = newConfig
        jsonText = null
        jsonError = null
    }

    fun profile() = Profile(
        id = profileId ?: UUID.randomUUID().toString(),
        name = name.trim().ifEmpty { (LuaJson.get(config, DeviceConfigBuilder.DEVICE_HASH) as? JsonPrimitive)?.content ?: "profile" },
        config = config,
        boardReadAt = boardReadAt,
    )
}

private val prettyJson = Json { prettyPrint = true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(id: String?, onDone: () -> Unit, onFlash: (String) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val vm: ProfileEditorViewModel = viewModel(key = "profile-$id")
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) { vm.load(graph, id) }
    val template = vm.template
    val isNew = id == null
    var tab by remember { mutableIntStateOf(0) }

    // A new profile starts with the scanner; without a scanned config there is nothing to save.
    LaunchedEffect(vm.loaded) { if (isNew && vm.loaded && !vm.hasScan && vm.scanResult == null) vm.scanning = true }

    val built = template?.let { runCatching { DeviceConfigBuilder.build(it, vm.config) }.getOrNull() }
    val issues = if (template != null && built != null) ConfigValidator.validate(template, built) else emptyList()
    val canSave = vm.loaded && !vm.saving && (!isNew || vm.hasScan) && vm.jsonError == null &&
        issues.none { it.severity == Severity.ERROR && it.path != DeviceConfigBuilder.DEVICE_HASH }

    fun save(then: (Profile) -> Unit) {
        if (!canSave) return
        vm.saving = true
        scope.launch {
            try {
                val p = vm.profile()
                graph.profiles.save(p)
                then(p)
            } finally {
                vm.saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (isNew) "New profile" else vm.name.ifEmpty { "Profile" }) },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = {
                if (isNew) {
                    IconButton(onClick = { vm.scanning = true }, enabled = vm.loaded) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan a config QR code")
                    }
                }
            },
        )
        TabRow(selectedTabIndex = tab) {
            listOf("Values", "Lua", "JSON").forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = {
                    if (tab == 2 && i != 2) vm.leaveJson()
                    tab = i
                }, text = { Text(title) })
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // room below the last field for the floating buttons
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 152.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    0 -> {
                        if (isNew) vm.scanResult?.let { (text, error) -> MessageCard(text, error) }
                        vm.jsonError?.let { MessageCard("JSON tab: $it. These values are its last valid state.") }
                        OutlinedTextField(
                            value = vm.name,
                            onValueChange = { vm.name = it },
                            label = { Text("Profile name") },
                            placeholder = { Text("the device hash") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        vm.boardReadAt?.let {
                            Text("Read from the board over USB, ${it.replace('T', ' ')}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Swipe through the sections of device_config.lua; the pencil edits one. Empty values are left out; when flashing, the bundle's template fills in its defaults.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (template != null) {
                            ConfigSectionCards(
                                template = template,
                                config = vm.config,
                                issues = issues,
                                onSetSection = vm::setSection,
                                sections = sectionsOf(template, vm.config),
                            )
                        }
                    }
                    1 -> {
                        val lua = vm.lua()
                        TextTools("The file as it is flashed: config/device_config.lua.", onCopy = { copyToClipboard(context, "device_config.lua", lua) })
                        CodeCard(lua)
                    }
                    else -> {
                        TextTools(
                            "Edit freely. As soon as it is a valid JSON object, the other tabs show it.",
                            onCopy = { copyToClipboard(context, "device_config.json", vm.jsonShown()) },
                        )
                        CodeField(vm.jsonShown(), vm::setJson, vm.jsonError)
                    }
                }

                // The Values tab shows the problems on the cards.
                if (tab != 0) issues.forEach {
                    Text(
                        "${if (it.severity == Severity.ERROR) "⚠" else "ⓘ"} ${it.path}: ${it.message}",
                        color = if (it.severity == Severity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val colors = MaterialTheme.colorScheme
                SmallFloatingActionButton(
                    onClick = { save { onFlash(it.id) } },
                    containerColor = if (canSave) colors.secondaryContainer else disabledFab(),
                    contentColor = if (canSave) colors.onSecondaryContainer else disabledFabContent(),
                ) { Icon(Icons.Default.FlashOn, contentDescription = "Save and flash") }
                FloatingActionButton(
                    onClick = { save { onDone() } },
                    containerColor = if (canSave) colors.primaryContainer else disabledFab(),
                    contentColor = if (canSave) colors.onPrimaryContainer else disabledFabContent(),
                ) { Icon(Icons.Default.Save, contentDescription = "Save") }
            }
        }
    }

    if (vm.scanning) {
        CodeScanDialog(
            onDismiss = {
                vm.scanning = false
                // Closed without a config: a new profile has nothing to show, back to the list.
                if (isNew && !vm.hasScan) onDone()
            },
            onCode = {
                vm.scanned(it)
                tab = 0 // the result shows on the Values tab
            },
            title = "Scan a config QR code",
            hint = "Hold the QR code with the device config (Lua or JSON) inside the frame.",
        )
    }
}

// FABs have no disabled state; greyed-out colors show that saving is not possible yet.
@Composable
private fun disabledFab(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

@Composable
private fun disabledFabContent(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

@Composable
private fun TextTools(text: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy to the clipboard") }
    }
}

/** A whole file as an editable monospace field (Lua or JSON tab). */
@Composable
private fun CodeField(text: String, onChange: (String) -> Unit, error: String?) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CodeCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    }
}
