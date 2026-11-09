package com.progeo.luaflasher.ui.profiles

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.progeo.luaflasher.AppGraph
import com.progeo.luaflasher.core.config.ConfigIssue
import com.progeo.luaflasher.core.config.ConfigTemplate
import com.progeo.luaflasher.core.config.ConfigValidator
import com.progeo.luaflasher.core.config.DeviceConfigBuilder
import com.progeo.luaflasher.core.config.FieldKind
import com.progeo.luaflasher.core.config.LuaJson
import com.progeo.luaflasher.core.config.LuaValue
import com.progeo.luaflasher.core.config.Severity
import com.progeo.luaflasher.data.Profile
import com.progeo.luaflasher.data.RemoteSource
import com.progeo.luaflasher.graph
import com.progeo.luaflasher.ui.MessageCard
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

    /** What the user typed, per path; kept while it is not a valid value yet (e.g. "18." or "abc" for a number). */
    val drafts = mutableStateMapOf<String, String>()
    val invalid = mutableStateMapOf<String, String>()

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

    fun profile() = Profile(
        id = profileId ?: UUID.randomUUID().toString(),
        name = name.trim().ifEmpty { (LuaJson.get(config, DeviceConfigBuilder.DEVICE_HASH) as? JsonPrimitive)?.content ?: "profile" },
        config = config,
        source = source,
        boardReadAt = boardReadAt,
    )
}

private val prettyJson = Json { prettyPrint = true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(id: String?, onDone: () -> Unit, onFlash: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val vm: ProfileEditorViewModel = viewModel(key = "profile-$id")
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) { vm.load(graph, id) }
    val template = vm.template
    var tab by remember { mutableIntStateOf(0) }

    val built = template?.let { runCatching { DeviceConfigBuilder.build(it, vm.config) }.getOrNull() }
    val issues = if (template != null && built != null) ConfigValidator.validate(template, built) else emptyList()
    val canSave = vm.loaded && vm.invalid.isEmpty() && issues.none { it.severity == Severity.ERROR && it.path != DeviceConfigBuilder.DEVICE_HASH }

    fun save(then: (Profile) -> Unit) = scope.launch {
        val p = vm.profile()
        graph.profiles.save(p)
        then(p)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (id == null) "New profile" else vm.name.ifEmpty { "Profile" }) },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        TabRow(selectedTabIndex = tab) {
            listOf("Values", "Lua", "JSON").forEachIndexed { i, title -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) }) }
        }
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                0 -> {
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
                1 -> CodeCard(template?.let { runCatching { DeviceConfigBuilder.lua(it, vm.config) }.getOrElse { e -> "-- ${e.message}" } }.orEmpty())
                else -> CodeCard(prettyJson.encodeToString(JsonObject.serializer(), vm.config))
            }

            issues.filter { it.path !in vm.invalid }.forEach {
                Text(
                    "${if (it.severity == Severity.ERROR) "⚠" else "ⓘ"} ${it.path}: ${it.message}",
                    color = if (it.severity == Severity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (vm.invalid.isNotEmpty()) MessageCard("Fix the marked fields before saving.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save { onDone() } }, enabled = canSave, modifier = Modifier.weight(1f)) { Text("Save") }
                OutlinedButton(onClick = { save { onFlash(it.id) } }, enabled = canSave, modifier = Modifier.weight(1f)) { Text("Save & flash") }
            }
        }
    }
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
                val kind = primitive?.let(LuaJson::kindOf) ?: field?.kind ?: FieldKind.STRING
                val hint = field?.default?.let { "default: $it" } ?: field?.hint?.let { "e.g. $it" }
                Leaf(vm, path, name, primitive, kind, hint, field?.description, issues)
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
