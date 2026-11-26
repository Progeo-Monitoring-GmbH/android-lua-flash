package com.progeo.luaflasher.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.progeo.core.ui.theme.StatusColors
import com.progeo.luaflasher.core.config.Choice
import com.progeo.luaflasher.core.config.ConfigChoices
import com.progeo.luaflasher.core.config.ConfigIssue
import com.progeo.luaflasher.core.config.ConfigTemplate
import com.progeo.luaflasher.core.config.FieldKind
import com.progeo.luaflasher.core.config.LuaJson
import com.progeo.luaflasher.core.config.LuaValue
import com.progeo.luaflasher.core.config.Severity
import com.progeo.luaflasher.router.RouterWifi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.roundToInt

/** The router section the user can add: credentials of the site's router. Not in the bundle's template. */
const val ROUTER = "router"

private val SECRET_NAMES = setOf("pwd", "password", "pass", "token", "secret", "key", "psk")

/** The template's top-level tables plus the config's own, in template order (e.g. for a profile). */
fun sectionsOf(template: ConfigTemplate, config: JsonObject): List<String> {
    val names = LinkedHashSet<String>()
    template.root.fields.forEach { f ->
        val v = f.value
        if (!f.positional && v is LuaValue.Table && v.fields.none { it.positional } && f.name !in ConfigTemplate.SKIPPED_TABLES) names += f.name
    }
    config.forEach { (key, value) -> if (value is JsonObject) names += key }
    return names.toList()
}

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
private fun fieldsOf(template: ConfigTemplate, section: String, values: JsonObject?, hidden: Set<String>): List<EditField> {
    val fields = LinkedHashMap<String, EditField>()
    if (section == ROUTER) ROUTER_FIELDS.forEach { fields[it.key] = it }
    for (f in template.fields) {
        if (f.section != section || f.name.contains('.') || f.path in hidden) continue
        fields[f.name] = EditField(f.name, f.kind, ConfigChoices.of(f.path), f.default?.let { "default: $it" } ?: f.hint?.let { "e.g. $it" }, f.description)
    }
    values?.forEach { (key, value) ->
        if (key !in fields && value is JsonPrimitive && "$section.$key" !in hidden) {
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
 * The sections of a device config as cards to swipe through. Each card shows the section's
 * values; the pencil opens a small dialog to change them, and a router section can be added.
 *
 * @param config what the cards show and the dialogs start from.
 * @param onSetSection a section was saved (null: removed).
 * @param hiddenPaths values edited elsewhere on the screen; [hiddenNote] says where.
 * @param routerWifis routers on the phone: the wifi card takes name and password from one of them.
 */
@Composable
fun ConfigSectionCards(
    template: ConfigTemplate,
    config: JsonObject,
    issues: List<ConfigIssue>,
    onSetSection: (String, JsonObject?) -> Unit,
    sections: List<String> = config.entries.filter { it.value is JsonObject }.map { it.key },
    hiddenPaths: Set<String> = emptySet(),
    hiddenNote: String? = null,
    routerWifis: List<RouterWifi> = emptyList(),
) {
    val pager = rememberPagerState { sections.size }
    var editing by remember { mutableStateOf<String?>(null) }
    var pickRouter by remember { mutableStateOf(false) }
    var scrollTo by remember { mutableStateOf<String?>(null) }
    // Measured height of each card, so the pager is as high as the card on screen.
    val heights = remember { mutableStateMapOf<String, Int>() }

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

    // While swiping, the height moves from the current card's to the next one's.
    val current = pager.currentPage
    val offset = pager.currentPageOffsetFraction
    val neighbor = if (offset > 0) current + 1 else current - 1
    val pagerHeight = heights[sections.getOrNull(current)]?.let { h ->
        val other = heights[sections.getOrNull(neighbor)]
        if (other == null) h else (h + (other - h) * abs(offset)).roundToInt()
    }
    val density = LocalDensity.current

    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(horizontal = 24.dp),
        pageSpacing = 12.dp,
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (pagerHeight != null) Modifier.height(with(density) { pagerHeight.toDp() }) else Modifier)
            .clipToBounds(),
    ) { page ->
        val name = sections[page]
        SectionCard(
            name = name,
            values = config[name] as? JsonObject ?: JsonObject(emptyMap()),
            issues = issues.filter { it.path.startsWith("$name.") },
            onEdit = { editing = name },
            onRemove = if (name == ROUTER) ({ onSetSection(ROUTER, null) }) else null,
            onFromRouter = if (name == RouterWifi.SECTION && routerWifis.isNotEmpty()) ({ pickRouter = true }) else null,
            modifier = Modifier
                // measured at its own height, not squeezed to the pager's
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .onSizeChanged { heights[name] = it.height },
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

    if (pickRouter) {
        RouterWifiDialog(
            routers = routerWifis,
            onDismiss = { pickRouter = false },
            onPick = { wifi ->
                pickRouter = false
                onSetSection(RouterWifi.SECTION, wifi.section(config[RouterWifi.SECTION] as? JsonObject))
            },
        )
    }

    editing?.let { name ->
        val values = config[name] as? JsonObject
        SectionDialog(
            name = name,
            values = values,
            isNew = name !in sections,
            fields = fieldsOf(template, name, values, hiddenPaths),
            note = hiddenNote?.takeIf { hiddenPaths.any { it.startsWith("$name.") } },
            onDismiss = { editing = null },
            onSave = { newValues ->
                onSetSection(name, newValues)
                editing = null
                scrollTo = name
            },
        )
    }
}

@Composable
private fun SectionCard(
    name: String,
    values: JsonObject,
    issues: List<ConfigIssue>,
    onEdit: () -> Unit,
    onRemove: (() -> Unit)?,
    onFromRouter: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Remove $name") }
                if (onFromRouter != null) IconButton(onClick = onFromRouter) { Icon(Icons.Default.Router, contentDescription = "Wi-Fi from a router") }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit $name") }
            }
            if (values.isEmpty()) {
                Text("Nothing set: the defaults apply.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** The routers on the phone with their Wi-Fi; one tap puts name and password into the wifi section. */
@Composable
private fun RouterWifiDialog(routers: List<RouterWifi>, onDismiss: () -> Unit, onPick: (RouterWifi) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wi-Fi from which router?") },
        text = {
            LazyColumn {
                items(routers, key = { it.serial }) { r ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(r) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(r.ssid, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            r.label + (if (r.factory) " · factory Wi-Fi" else "") + (if (r.key == null) " · open" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A minimal dialog with the inputs of one section; empty inputs leave the value out. */
@Composable
private fun SectionDialog(
    name: String,
    values: JsonObject?,
    isNew: Boolean,
    fields: List<EditField>,
    note: String?,
    onDismiss: () -> Unit,
    onSave: (JsonObject) -> Unit,
) {
    val texts = remember(name) {
        mutableStateMapOf<String, String>().apply {
            fields.forEach { f -> put(f.key, (values?.get(f.key) as? JsonPrimitive)?.content.orEmpty()) }
        }
    }
    val invalid = fields.filter { f ->
        val t = texts[f.key].orEmpty().trim()
        t.isNotEmpty() && f.choices == null && LuaJson.typed(t, f.kind) == null
    }.map { it.key }.toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add $name" else "Edit $name") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                fields.forEach { f ->
                    val error = if (f.key in invalid) "must be ${if (f.kind == FieldKind.NUMBER) "a number" else "true or false"}" else null
                    FieldInput(name, f, texts[f.key].orEmpty(), error) { texts[f.key] = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = invalid.isEmpty(),
                onClick = {
                    // Start from what the section has, so values the dialog does not show (tables, lists) stay.
                    val result = LinkedHashMap<String, JsonElement>(values.orEmpty())
                    fields.forEach { f ->
                        val raw = texts[f.key].orEmpty()
                        // Text is kept as typed (passwords may have spaces); numbers and booleans are trimmed.
                        val t = if (f.kind == FieldKind.STRING) raw else raw.trim()
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
                    placeholder = f.hint?.let { { Text(it) } },
                    supportingText = support?.let { { Text(it) } },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    if (text.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("(not set)") }, onClick = {
                            onChange("")
                            expanded = false
                        })
                    }
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
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
