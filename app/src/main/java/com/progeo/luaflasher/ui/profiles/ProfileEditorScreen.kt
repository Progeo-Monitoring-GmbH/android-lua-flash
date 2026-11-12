package com.progeo.luaflasher.ui.profiles

import android.widget.Toast
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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.progeo.luaflasher.AppGraph
import com.progeo.luaflasher.backend.RemoteDevice
import com.progeo.luaflasher.backend.RemoteLocation
import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.config.Choice
import com.progeo.luaflasher.core.config.ConfigChoices
import com.progeo.luaflasher.core.config.ConfigIssue
import com.progeo.luaflasher.core.config.ConfigTemplate
import com.progeo.luaflasher.core.config.ConfigValidator
import com.progeo.luaflasher.core.config.DeviceConfigBuilder
import com.progeo.luaflasher.core.config.FieldKind
import com.progeo.luaflasher.core.config.LuaJson
import com.progeo.luaflasher.core.config.LuaParseException
import com.progeo.luaflasher.core.config.LuaValue
import com.progeo.luaflasher.core.config.Severity
import com.progeo.luaflasher.data.Profile
import com.progeo.luaflasher.data.RemoteSource
import com.progeo.luaflasher.graph
import com.progeo.luaflasher.ui.LoginFormState
import com.progeo.luaflasher.ui.MessageCard
import com.progeo.luaflasher.ui.clipboardText
import com.progeo.luaflasher.ui.copyToClipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

class ProfileEditorViewModel : ViewModel() {
    var loaded by mutableStateOf(false)
    var template by mutableStateOf<ConfigTemplate?>(null)
    var name by mutableStateOf("")
    var config by mutableStateOf(JsonObject(emptyMap()))
    var source: RemoteSource? = null
    var boardReadAt: String? = null
    var profileId: String? = null
    var saving by mutableStateOf(false)

    /** What the user typed, per path; kept while it is not a valid value yet (e.g. "18." or "abc" for a number). */
    val drafts = mutableStateMapOf<String, String>()
    val invalid = mutableStateMapOf<String, String>()

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
            source = profile.source
            boardReadAt = profile.boardReadAt
        } else {
            // A new profile starts with the template's defaults, all editable.
            config = t.defaults()
        }
        loaded = true
    }

    fun setText(path: String, kind: FieldKind, text: String) {
        drafts[path] = text
        if (text.isEmpty()) {
            invalid.remove(path)
            config = LuaJson.with(config, path, null)
            return
        }
        val value = LuaJson.typed(text, kind)
        if (value == null) {
            invalid[path] = if (kind == FieldKind.NUMBER) "must be a number" else "must be true or false"
        } else {
            invalid.remove(path)
            config = LuaJson.with(config, path, value)
        }
    }

    fun setBoolean(path: String, value: Boolean) {
        config = LuaJson.with(config, path, JsonPrimitive(value))
    }

    fun setChoice(path: String, choice: Choice) {
        drafts.remove(path)
        invalid.remove(path)
        config = LuaJson.with(config, path, JsonPrimitive(choice.value))
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

    /** The Lua tab's text while it differs from [config] (being edited); null shows the generated file. */
    var luaText by mutableStateOf<String?>(null)
        private set
    var luaError by mutableStateOf<String?>(null)
        private set

    fun luaShown(): String = luaText
        ?: template?.let { runCatching { DeviceConfigBuilder.lua(it, config) }.getOrElse { e -> "-- ${e.message}" } }.orEmpty()

    /** Editing (or pasting) a whole device_config.lua: every file that parses becomes the config right away. */
    fun setLua(text: String) {
        luaText = text
        val parsed = try {
            LuaJson.parse(text)
        } catch (e: LuaParseException) {
            luaError = e.message ?: "not a valid config file"
            return
        } catch (e: Exception) {
            luaError = "not a valid config file: ${e.message ?: e.javaClass.simpleName}"
            return
        }
        replaceConfig(parsed)
        luaText = text
    }

    /** Leaving the Lua tab: a valid file is shown as generated next time; broken text stays for fixing. */
    fun leaveLua() {
        if (luaError == null) luaText = null
    }

    /** The error of the tab that holds unparsed text, if any; the config is its last valid state. */
    val textError: String?
        get() = luaError?.let { "Lua tab: $it" } ?: jsonError?.let { "JSON tab: $it" }

    /** A whole new config: every unfinished edit (field drafts, broken Lua/JSON text) is dropped. */
    private fun replaceConfig(newConfig: JsonObject) {
        config = newConfig
        drafts.clear()
        invalid.clear()
        jsonText = null
        jsonError = null
        luaText = null
        luaError = null
    }

    fun profile() = Profile(
        id = profileId ?: UUID.randomUUID().toString(),
        name = name.trim().ifEmpty { (LuaJson.get(config, DeviceConfigBuilder.DEVICE_HASH) as? JsonPrimitive)?.content ?: "profile" },
        config = config,
        source = source,
        boardReadAt = boardReadAt,
    )

    // ---- new profile: pick the location, then load its controller's config from the server

    val loginForm = LoginFormState()
    var locations by mutableStateOf<List<RemoteLocation>?>(null)
        private set
    var locationQuery by mutableStateOf("")
    var location by mutableStateOf<RemoteLocation?>(null)
        private set
    var devices by mutableStateOf<List<RemoteDevice>?>(null)
        private set
    var device by mutableStateOf<RemoteDevice?>(null)
        private set
    var requestStatus by mutableStateOf<String?>(null)
        private set
    var locationStep by mutableStateOf(true)
    var locationProgress by mutableStateOf<String?>(null)
        private set
    var locationError by mutableStateOf<String?>(null)
        private set
    var locationInfo by mutableStateOf<String?>(null)
        private set
    private var locationJob: Job? = null
    private var locationGeneration = 0

    /** Runs [block], cancelling the previous step (a new location or controller replaces it). */
    private fun locationTask(progress: String, block: suspend () -> Unit) {
        locationJob?.cancel()
        val generation = ++locationGeneration
        locationError = null
        locationProgress = progress
        locationJob = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: FlasherException) {
                if (generation == locationGeneration) locationError = e.userMessage
            } catch (e: Exception) {
                if (generation == locationGeneration) locationError = e.message ?: e.javaClass.simpleName
            } finally {
                // A cancelled step finishes after its successor started: leave the successor's progress alone.
                if (generation == locationGeneration) locationProgress = null
            }
        }
    }

    fun login(graph: AppGraph) {
        viewModelScope.launch { if (loginForm.submit(graph)) loadLocations(graph) }
    }

    fun loadLocations(graph: AppGraph, force: Boolean = false) {
        if (locations != null && !force) return
        locationTask("Loading locations…") { locations = graph.locations.locations() }
    }

    /** The template's defaults with the project id of the chosen location (and the controller's hash). */
    private fun startConfig(projectId: Long?, deviceHash: String?): JsonObject {
        val t = template ?: return config
        var c = t.defaults()
        fun put(path: String, text: String) {
            LuaJson.typed(text, t.field(path)?.kind ?: FieldKind.STRING)?.let { c = LuaJson.with(c, path, it) }
        }
        projectId?.let { put("device.project_id", it.toString()) }
        deviceHash?.let { put(DeviceConfigBuilder.DEVICE_HASH, it) }
        return c
    }

    fun selectLocation(graph: AppGraph, loc: RemoteLocation) {
        location = loc
        device = null
        devices = null
        source = null
        requestStatus = null
        locationInfo = null
        replaceConfig(startConfig(loc.projectId, null))
        locationTask("Looking for controllers at ${loc.title}…") {
            val found = graph.deviceConfigs.devices(loc.id.toString())
            devices = found
            when (found.size) {
                0 -> locationInfo = "No controller is registered at this location yet. The profile starts from the template defaults" +
                    (loc.projectId?.let { " with project id $it." } ?: ".")
                1 -> fetchConfig(graph, loc, found.single())
                else -> locationInfo = "${found.size} controllers at this location. Choose the one to load its config."
            }
        }
    }

    fun selectDevice(graph: AppGraph, d: RemoteDevice) {
        val loc = location ?: return
        locationTask("Asking the server for the config of ${d.hash ?: d.label}…") { fetchConfig(graph, loc, d) }
    }

    private suspend fun fetchConfig(graph: AppGraph, loc: RemoteLocation, d: RemoteDevice) {
        device = d
        source = null
        locationInfo = null
        locationProgress = "Asking the server for the config of ${d.hash ?: d.label}…"
        replaceConfig(startConfig(loc.projectId, d.hash))
        val remote = graph.deviceConfigs.config(d.id, loc.id.toString())
        requestStatus = remote.requestStatus
        val content = remote.content
        if (content == null) {
            locationInfo = "The server has no config of ${d.hash ?: d.label} yet. The profile starts from the template defaults " +
                "with its project id and device hash."
            return
        }
        val fetched = try {
            LuaJson.parse(content)
        } catch (e: LuaParseException) {
            locationError = "The controller's config could not be read: ${e.message}"
            return
        }
        replaceConfig(fetched)
        val hash = remote.rawHash ?: d.hash
        source = RemoteSource(loc.id.toString(), remote.deviceId, hash, remote.fetchedAt, remote.commandId)
        if (name.isBlank()) name = (LuaJson.get(fetched, DeviceConfigBuilder.DEVICE_HASH) as? JsonPrimitive)?.content ?: hash.orEmpty()
        locationInfo = "Loaded the config ${hash ?: "the controller"} sent" + (remote.fetchedAt?.let { " on ${it.take(16).replace('T', ' ')}" } ?: "") + "."
    }

    fun requestConfig(graph: AppGraph) {
        val loc = location ?: return
        val d = device ?: return
        locationTask("Requesting the config…") {
            graph.deviceConfigs.requestConfig(d.id, loc.id.toString())
            requestStatus = graph.deviceConfigs.config(d.id, loc.id.toString()).requestStatus ?: "pending"
            locationInfo = "Requested. The controller sends its config the next time it checks for commands; " +
                "then read it under Profiles → Read from controller."
        }
    }

    fun changeLocation() {
        locationJob?.cancel()
        locationGeneration++
        locationProgress = null
        location = null
        devices = null
        device = null
        requestStatus = null
        locationInfo = null
        locationError = null
    }
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
    var tab by remember { mutableIntStateOf(0) }

    val built = template?.let { runCatching { DeviceConfigBuilder.build(it, vm.config) }.getOrNull() }
    val issues = if (template != null && built != null) ConfigValidator.validate(template, built) else emptyList()
    val canSave = vm.loaded && !vm.saving && vm.invalid.isEmpty() && vm.textError == null &&
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
            title = { Text(if (id == null) "New profile" else vm.name.ifEmpty { "Profile" }) },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        TabRow(selectedTabIndex = tab) {
            listOf("Values", "Lua", "JSON").forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = {
                    if (tab == 1 && i != 1) vm.leaveLua()
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
                        if (id == null && vm.loaded) NewProfileLocation(vm, graph)
                        vm.textError?.let { MessageCard("$it. These values are its last valid state.") }
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
                        vm.source?.let { s ->
                            Text(
                                "Fetched from controller ${s.rawHash ?: s.deviceId} (location ${s.locationId})" + (s.fetchedAt?.let { ", $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "Every value of device_config.lua. Empty fields are left out; when flashing, the bundle's template fills in its defaults.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (template != null) ObjectEditor(vm, "", vm.config, template.root, issues, depth = 0)
                    }
                    1 -> if (id == null) {
                        // A new profile can start from a device_config.lua pasted from elsewhere.
                        TextTools(
                            "Paste or edit a whole device_config.lua. As soon as it parses, the other tabs show it.",
                            onCopy = { copyToClipboard(context, "device_config.lua", vm.luaShown()) },
                            onPaste = {
                                val text = clipboardText(context)
                                if (text == null) Toast.makeText(context, "The clipboard is empty", Toast.LENGTH_SHORT).show() else vm.setLua(text)
                            },
                        )
                        CodeField(vm.luaShown(), vm::setLua, vm.luaError)
                    } else {
                        val lua = vm.luaShown()
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

                issues.filter { it.path !in vm.invalid }.forEach {
                    Text(
                        "${if (it.severity == Severity.ERROR) "⚠" else "ⓘ"} ${it.path}: ${it.message}",
                        color = if (it.severity == Severity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (vm.invalid.isNotEmpty()) MessageCard("Fix the marked fields before saving.")
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
}

// FABs have no disabled state; greyed-out colors show that saving is not possible yet.
@Composable
private fun disabledFab(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

@Composable
private fun disabledFabContent(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

@Composable
private fun TextTools(text: String, onCopy: () -> Unit, onPaste: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (onPaste != null) {
            IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, contentDescription = "Replace with the clipboard") }
        }
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

/**
 * The fields of one table: everything the profile has, plus what the template knows but the
 * profile does not set yet ("not set", with the template's default or example as hint).
 */
@Composable
private fun ObjectEditor(
    vm: ProfileEditorViewModel,
    prefix: String,
    obj: JsonObject?,
    templateTable: LuaValue.Table?,
    issues: List<ConfigIssue>,
    depth: Int,
) {
    val names = LinkedHashSet<String>()
    templateTable?.fields?.forEach { f ->
        if (!(depth == 0 && f.name in ConfigTemplate.SKIPPED_TABLES && obj?.containsKey(f.name) != true) && !f.positional) names += f.name
    }
    obj?.keys?.let(names::addAll)

    for (name in names) {
        val path = if (prefix.isEmpty()) name else "$prefix.$name"
        val value: JsonElement? = obj?.get(name)
        val templateField = templateTable?.fields?.firstOrNull { it.name == name && !it.commented } ?: templateTable?.fields?.firstOrNull { it.name == name }
        val templateValue = templateField?.value
        when {
            value is JsonObject || (value == null && templateValue is LuaValue.Table && templateValue.fields.none { it.positional }) -> {
                SectionTitle(path, depth)
                ObjectEditor(vm, path, value as? JsonObject, templateValue as? LuaValue.Table, issues, depth + 1)
            }
            value is JsonArray -> {
                SectionTitle("$path (list)", depth)
                value.forEachIndexed { i, item ->
                    val itemPath = "$path.${i + 1}"
                    if (item is JsonObject) {
                        SectionTitle(itemPath, depth + 1)
                        ObjectEditor(vm, itemPath, item, null, issues, depth + 2)
                    } else if (item is JsonPrimitive) {
                        Leaf(vm, itemPath, "${i + 1}", item, LuaJson.kindOf(item), null, null, issues)
                    }
                }
            }
            else -> {
                val primitive = value as? JsonPrimitive
                val field = vm.template?.field(path)
                val choices = ConfigChoices.of(path)
                if (choices != null && (primitive == null || LuaJson.kindOf(primitive) != FieldKind.BOOLEAN)) {
                    ChoiceLeaf(vm, path, name, primitive, choices, field?.default, field?.description, issues)
                } else {
                    val kind = primitive?.let(LuaJson::kindOf) ?: field?.kind ?: FieldKind.STRING
                    val hint = field?.default?.let { "default: $it" } ?: field?.hint?.let { "e.g. $it" }
                    Leaf(vm, path, name, primitive, kind, hint, field?.description, issues)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, depth: Int) {
    Text(
        text,
        style = if (depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = if (depth == 0) 8.dp else 0.dp),
    )
}

/** A setting that only takes a fixed set of values ([ConfigChoices]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceLeaf(
    vm: ProfileEditorViewModel,
    path: String,
    label: String,
    value: JsonPrimitive?,
    choices: List<Choice>,
    default: String?,
    description: String?,
    issues: List<ConfigIssue>,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = ConfigChoices.find(path, value?.content)
    val unsupported = value != null && current == null
    val shown = when {
        current != null -> current.label
        value != null -> "${value.content} (not supported)"
        else -> ""
    }
    val defaultLabel = default?.let { ConfigChoices.find(path, it)?.label ?: it }
    val support = listOfNotNull(
        if (unsupported) "pick one of the supported values" else null,
        description,
        if (value == null) "not set" + (defaultLabel?.let { ", default: $it" } ?: "") else null,
    ).joinToString(" · ")
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            supportingText = if (support.isNotEmpty()) ({ Text(support) }) else null,
            isError = unsupported || issues.any { it.path == path && it.severity == Severity.ERROR },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        vm.setChoice(path, choice)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

private val SECRET_NAMES = setOf("pwd", "password", "pass", "token", "secret", "key", "psk")

@Composable
private fun Leaf(
    vm: ProfileEditorViewModel,
    path: String,
    label: String,
    value: JsonPrimitive?,
    kind: FieldKind,
    hint: String?,
    description: String?,
    issues: List<ConfigIssue>,
) {
    if (kind == FieldKind.BOOLEAN) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(label)
                Text(
                    listOfNotNull(value?.content ?: "not set", description).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // "not set" is a value of its own (e.g. control.rpi: unset = autodetect).
            if (value != null) TextButton(onClick = { vm.setText(path, kind, "") }) { Text("Unset") }
            Switch(checked = value?.content == "true", onCheckedChange = { vm.setBoolean(path, it) })
        }
        return
    }
    val secret = label.lowercase() in SECRET_NAMES
    var visible by remember { mutableStateOf(false) }
    val error = vm.invalid[path] ?: issues.firstOrNull { it.path == path && it.severity == Severity.ERROR }?.message
    val support = listOfNotNull(error, description, if (value == null) "not set" else null).joinToString(" · ")
    OutlinedTextField(
        value = vm.drafts[path] ?: value?.content.orEmpty(),
        onValueChange = { vm.setText(path, kind, it) },
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        supportingText = if (support.isNotEmpty()) ({ Text(support) }) else null,
        isError = error != null,
        singleLine = true,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                secret -> KeyboardType.Password
                kind == FieldKind.NUMBER -> KeyboardType.Decimal
                label.contains("url") -> KeyboardType.Uri
                else -> KeyboardType.Text
            },
            autoCorrectEnabled = false,
        ),
        trailingIcon = if (secret) ({
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Show")
            }
        }) else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CodeCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    }
}
