package com.progeo.luaflasher.ui.profiles

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.progeo.luaflasher.core.config.ConfigField
import com.progeo.luaflasher.core.config.ConfigIssue
import com.progeo.luaflasher.core.config.FieldKind
import com.progeo.luaflasher.core.config.Severity

/** One device_config.lua setting; placeholder shows the template default or hint. */
@Composable
fun ConfigFieldInput(field: ConfigField, value: String, issue: ConfigIssue?, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val placeholder = field.default?.let { "default: $it" } ?: field.hint?.let { "e.g. $it" } ?: if (field.optional) "off" else ""
    val support = buildString {
        issue?.let { append(if (it.severity == Severity.ERROR) "⚠ " else "ⓘ ").append(it.message) }
        field.description?.let { if (isNotEmpty()) append(" · "); append(it) }
        if (field.optional && issue == null && field.description == null) append("optional")
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(field.name) },
        placeholder = { Text(placeholder) },
        supportingText = if (support.isNotEmpty()) ({ Text(support) }) else null,
        isError = issue?.severity == Severity.ERROR,
        singleLine = true,
        visualTransformation = if (field.isSecret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                field.isSecret -> KeyboardType.Password
                field.kind == FieldKind.NUMBER -> KeyboardType.Number
                field.path.endsWith("url") -> KeyboardType.Uri
                else -> KeyboardType.Text
            },
            autoCorrectEnabled = false,
        ),
        trailingIcon = if (field.isSecret) ({
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Show")
            }
        }) else null,
        modifier = Modifier.fillMaxWidth(),
    )
}
