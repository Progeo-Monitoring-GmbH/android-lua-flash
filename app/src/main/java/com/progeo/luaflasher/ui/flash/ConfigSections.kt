package com.progeo.luaflasher.ui.flash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.progeo.luaflasher.AppGraph
import com.progeo.luaflasher.core.config.Choice
import com.progeo.luaflasher.core.config.ConfigChoices
import com.progeo.luaflasher.core.config.ConfigIssue
import com.progeo.luaflasher.core.config.ConfigTemplate
import com.progeo.luaflasher.core.config.DeviceConfigBuilder
import com.progeo.luaflasher.core.config.FieldKind
import com.progeo.luaflasher.core.config.LuaJson
import com.progeo.luaflasher.core.config.Severity
import com.progeo.luaflasher.ui.theme.StatusColors
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The router section the user can add: credentials of the site's router. Not in the bundle's template. */
const val ROUTER = "router"

private val SECRET_NAMES = setOf("pwd", "password", "pass", "token", "secret", "key", "psk")

/** One input of the edit dialog. */
private data class EditField(
    val key: String,
    val kind: FieldKind,
    val choices: List<Choice>?,
    val hint: String?,
    val description: String?,
) {
    val secret: Boolean get() = key.lowercase() in SECRET_NAMES
}

/** The router section: user and password. */
private val ROUTER_FIELDS = listOf(
    EditField("user", FieldKind.STRING, null, null, "user name of the router"),
    EditField("pwd", FieldKind.STRING, null, null, "password of the router"),
)

/** The fields of a section: the template's, plus values the config has that the template does not know. */
private fun fieldsOf(template: ConfigTemplate, section: String, values: JsonObject?): List<EditField> {
    val fields = LinkedHashMap<String, EditField>()
    if (section == ROUTER) ROUTER_FIELDS.forEach { fields[it.key] = it }
    for (f in template.fields) {
        if (f.section != section || f.name.contains('.') || f.path == DeviceConfigBuilder.DEVICE_HASH) continue
        fields[f.name] = EditField(f.name, f.kind, ConfigChoices.of(f.path), f.default?.let { "default: $it" } ?: f.hint?.let { "e.g. $it" }, f.description)
    }
    values?.forEach { (key, value) ->
        if (key !in fields && value is JsonPrimitive && "$section.$key" != DeviceConfigBuilder.DEVICE_HASH) {
            fields[key] = EditField(key, LuaJson.kindOf(value), ConfigChoices.of("$section.$key"), null, null)
        }
    }
    return fields.values.toList()
}

private fun shown(path: String, key: String, value: JsonElement): String = when {
    value !is JsonPrimitive -> value.toString()
    key.lowercase() in SECRET_NAMES && value.content.isNotEmpty() -> "••••••"
    else -> ConfigChoices.find(path, value.content)?.label ?: value.content
}

/**
 * The sections of the device config as cards to swipe through (flash setup). Each card shows
 * what will be flashed and opens a small dialog to change it; a router section can be added.
 * Changes apply to this flash only.
 */
@Composable
fun ConfigSections(vm: FlashSetupViewModel, graph: AppGraph) {
    val template = vm.template
    val config = vm.config(graph)
    if (template == null || config == null) {
        Text("Loading the bundle's config template…", style = MaterialTheme.typography.bodySmall)
        return
    }
    val issues = vm.issues(graph)
    val sections = config.entries.filter { it.value is JsonObject }.map { it.key }
    val pager = rememberPagerState { sections.size }
    var editing by remember { mutableStateOf<String?>(null) }
    var scrollTo by remember { mutableStateOf<String?>(null) }

    // After adding a section, show its card.
    LaunchedEffect(sections, scrollTo) {
        val target = scrollTo ?: return@LaunchedEffect
        val index = sections.indexOf(target)
        if (index >= 0) {
            pager.animateScrollToPage(index)
            scrollTo = null
        }
    }

    val withErrors = sections.filter { s -> issues.any { it.severity == Severity.ERROR && it.path.startsWith("$s.") } }
    if (withErrors.isNotEmpty()) {
        Text("⚠ To fix: ${withErrors.joinToString()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(horizontal = 24.dp),
        pageSpacing = 12.dp,
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) { page ->
        val name = sections[page]
        SectionCard(
            name = name,
            values = config[name] as JsonObject,
            issues = issues.filter { it.path.startsWith("$name.") },
            onEdit = { editing = name },
            onRemove = if (name == ROUTER) ({ vm.setSection(ROUTER, null) }) else null,
        )
    }

    // Page dots: where in the sections the shown card is.
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        sections.forEachIndexed { i, s ->
            val color = when {
                s in withErrors -> MaterialTheme.colorScheme.error
                i == pager.currentPage -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Box(Modifier.size(if (i == pager.currentPage) 10.dp else 8.dp).background(color, CircleShape))
        }
    }

    if (ROUTER !in sections) {
        OutlinedButton(onClick = { editing = ROUTER }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Router, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add router")
        }
    }

    editing?.let { name ->
        SectionDialog(
            name = name,
            values = config[name] as? JsonObject,
            fields = fieldsOf(template, name, config[name] as? JsonObject),
            onDismiss = { editing = null },
            onSave = { values ->
                vm.setSection(name, values)
                editing = null
                scrollTo = name
            },
        )
    }
}

@Composable
private fun SectionCard(name: String, values: JsonObject, issues: List<ConfigIssue>, onEdit: () -> Unit, onRemove: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Remove $name") }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit $name") }
            }
            if (values.isEmpty()) {
                Text("Nothing set: the firmware's defaults apply.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            values.forEach { (key, value) ->
                Row {
                    Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
                    Text(
                        shown("$name.$key", key, value),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.58f),
                    )
                }
            }
            issues.forEach {
                Text(
                    "${if (it.severity == Severity.ERROR) "⚠" else "ⓘ"} ${it.path.substringAfter('.')}: ${it.message}",
                    color = if (it.severity == Severity.ERROR) MaterialTheme.colorScheme.error else StatusColors.warn,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** A minimal dialog with the inputs of one section; empty inputs leave the value out. */
@Composable
private fun SectionDialog(
    name: String,
    values: JsonObject?,
    fields: List<EditField>,
    onDismiss: () -> Unit,
    onSave: (JsonObject) -> Unit,
) {
    val texts = remember(name) {
        mutableStateMapOf<String, String>().apply {
            fields.forEach { f -> put(f.key, (values?.get(f.key) as? JsonPrimitive)?.content.orEmpty()) }
        }
    }
    val invalid = fields.filter { f ->
        val t = texts[f.key].orEmpty()
        t.isNotEmpty() && f.choices == null && LuaJson.typed(t, f.kind) == null
    }.map { it.key }.toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (values == null) "Add $name" else "Edit $name") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (name == "device") {
                    Text("The device hash is set above.", style = MaterialTheme.typography.bodySmall)
                }
                fields.forEach { f ->
                    FieldInput(name, f, texts[f.key].orEmpty(), if (f.key in invalid) "must be ${if (f.kind == FieldKind.NUMBER) "a number" else "true or false"}" else null) {
                        texts[f.key] = it
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = invalid.isEmpty(),
                onClick = {
                    // Start from what the section has, so values the dialog does not show (tables) stay.
                    val result = LinkedHashMap<String, JsonElement>(values.orEmpty())
                    fields.forEach { f ->
                        val t = texts[f.key].orEmpty().trim().let { if (f.secret || f.kind == FieldKind.STRING) texts[f.key].orEmpty() else it }
                        val value = if (t.isEmpty()) null else LuaJson.typed(t, f.kind)
                        if (value == null) result.remove(f.key) else result[f.key] = value
                    }
                    onSave(JsonObject(result))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldInput(section: String, f: EditField, text: String, error: String?, onChange: (String) -> Unit) {
    val support = listOfNotNull(error, f.description).joinToString(" · ").takeIf { it.isNotEmpty() }
    when {
        f.kind == FieldKind.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(f.key)
                Text(listOfNotNull(text.ifEmpty { "not set" }, f.description).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            // "not set" is a value of its own (e.g. control.rpi: unset = autodetect).
            if (text.isNotEmpty()) TextButton(onClick = { onChange("") }) { Text("Unset") }
            Switch(checked = text == "true", onCheckedChange = { onChange(it.toString()) })
        }
        f.choices != null -> {
            var expanded by remember { mutableStateOf(false) }
            val current = ConfigChoices.find("$section.${f.key}", text)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = current?.label ?: text,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(f.key) },
                    supportingText = support?.let { { Text(it) } },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    f.choices.forEach { choice ->
                        DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                            onChange(choice.value.toString())
                            expanded = false
                        })
                    }
                }
            }
        }
        else -> {
            var visible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = text,
                onValueChange = onChange,
                label = { Text(f.key) },
                placeholder = f.hint?.let { { Text(it) } },
                supportingText = support?.let { { Text(it) } },
                isError = error != null,
                singleLine = true,
                visualTransformation = if (f.secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when {
                        f.secret -> KeyboardType.Password
                        f.kind == FieldKind.NUMBER -> KeyboardType.Decimal
                        f.key.contains("url") -> KeyboardType.Uri
                        else -> KeyboardType.Text
                    },
                    autoCorrectEnabled = false,
                ),
                trailingIcon = if (f.secret) ({
                    IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Show")
                    }
                }) else null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            )
        }
    }
}
