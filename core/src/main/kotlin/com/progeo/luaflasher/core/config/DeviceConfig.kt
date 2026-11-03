package com.progeo.luaflasher.core.config

enum class FieldKind { STRING, NUMBER, BOOLEAN }

/**
 * A setting of `device_config.lua`, as described by the bundle's template.
 * [default] is what is written when the profile leaves the field empty (null: omit it);
 * [hint] is an example shown to the user (placeholders and commented-out values).
 */
data class ConfigField(
    val path: String,
    val kind: FieldKind,
    val default: String?,
    val hint: String?,
    val optional: Boolean,
    val description: String?,
) {
    val section: String get() = path.substringBefore('.')
    val name: String get() = path.substringAfter('.')
    val isSecret: Boolean get() = path in SECRET_PATHS

    companion object {
        val SECRET_PATHS = setOf("wifi.pwd", "control.token", "portal.pwd")
    }
}

/** `config/device_config.lua.template` of a bundle (PLAN.md section 7). */
class ConfigTemplate(val root: LuaValue.Table) {

    val fields: List<ConfigField> = buildList { collect(root, "", this) }

    fun field(path: String): ConfigField? = fields.firstOrNull { it.path == path }

    private fun collect(table: LuaValue.Table, prefix: String, out: MutableList<ConfigField>) {
        for (f in table.fields) {
            val path = prefix + f.name
            if (prefix.isEmpty() && f.name in SKIPPED_TABLES) continue
            if (f.commented && table[f.name] != null) continue
            when (val v = f.value) {
                is LuaValue.Table -> collect(v, "$path.", out)
                is LuaValue.Nil -> Unit
                else -> {
                    val literal = when (v) {
                        is LuaValue.Str -> v.value
                        is LuaValue.Num -> v.literal
                        is LuaValue.Bool -> v.value.toString()
                        else -> null
                    }
                    val usable = !f.commented && !f.isPlaceholder
                    out += ConfigField(
                        path = path,
                        kind = kindOf(v),
                        default = if (usable) literal else null,
                        hint = if (usable) null else literal,
                        optional = f.commented,
                        description = f.description,
                    )
                }
            }
        }
    }

    companion object {
        /** Tables only the PC scripts use; never written by the app. */
        val SKIPPED_TABLES = setOf("firmware")

        fun parse(source: String) = ConfigTemplate(LuaTableParser.parse(source))

        private fun kindOf(v: LuaValue) = when (v) {
            is LuaValue.Num -> FieldKind.NUMBER
            is LuaValue.Bool -> FieldKind.BOOLEAN
            else -> FieldKind.STRING
        }
    }
}

/** Writes `config/device_config.lua` from a template and the profile + per-device values. */
object LuaConfigWriter {
    /**
     * [values] maps dotted paths (e.g. `device.device_hash`) to text; empty values count as
     * unset. Unset fields get the template default or are omitted; placeholders are never
     * written; the `firmware` table is dropped; tables keep the template's order.
     */
    fun write(template: ConfigTemplate, values: Map<String, String>, header: String? = null): String {
        val sb = StringBuilder()
        header?.lineSequence()?.forEach { sb.append("-- ").append(it).append('\n') }
        sb.append("return {\n")
        writeTable(template.root, "", values, sb, 1)
        sb.append("}\n")
        return sb.toString()
    }

    private fun writeTable(table: LuaValue.Table, prefix: String, values: Map<String, String>, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        val seen = HashSet<String>()
        for (f in table.fields) {
            if (prefix.isEmpty() && f.name in ConfigTemplate.SKIPPED_TABLES) continue
            if (f.commented && table[f.name] != null) continue
            if (!seen.add(f.name)) continue
            val path = prefix + f.name
            val v = f.value
            if (v is LuaValue.Table) {
                val inner = StringBuilder()
                writeTable(v, "$path.", values, inner, depth + 1)
                if (inner.isEmpty()) {
                    sb.append(indent).append(f.name).append(" = {},\n")
                } else {
                    sb.append(indent).append(f.name).append(" = {\n").append(inner).append(indent).append("},\n")
                }
                continue
            }
            val literal = literalFor(f, values[path]?.takeIf { it.isNotEmpty() }) ?: continue
            sb.append(indent).append(f.name).append(" = ").append(literal).append(",\n")
        }
    }

    private fun literalFor(field: LuaField, value: String?): String? {
        val v = field.value
        if (value != null) {
            return when (v) {
                is LuaValue.Num -> value.trim().also { require(isNumber(it)) { "${field.name}: not a number: $value" } }
                is LuaValue.Bool -> value.trim().lowercase().also { require(it == "true" || it == "false") { "${field.name}: not true/false: $value" } }
                else -> LuaString.quote(value)
            }
        }
        if (field.commented || field.isPlaceholder) return null
        return when (v) {
            is LuaValue.Str -> LuaString.quote(v.value)
            is LuaValue.Num -> v.literal
            is LuaValue.Bool -> v.value.toString()
            else -> null
        }
    }

    private val NUMBER = Regex("-?(?:0[xX][0-9a-fA-F]+|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)")

    fun isNumber(text: String) = NUMBER.matches(text)
}

enum class Severity { ERROR, WARNING }

data class ConfigIssue(val path: String, val severity: Severity, val message: String)

/** Checks from PLAN.md section 7 plus type checks against the template. */
object ConfigValidator {
    fun validate(template: ConfigTemplate, values: Map<String, String>): List<ConfigIssue> {
        val issues = ArrayList<ConfigIssue>()
        fun effective(path: String) = values[path]?.takeIf { it.isNotEmpty() } ?: template.field(path)?.default

        for (field in template.fields) {
            val value = values[field.path]?.takeIf { it.isNotEmpty() } ?: continue
            when (field.kind) {
                FieldKind.NUMBER -> if (!LuaConfigWriter.isNumber(value.trim())) {
                    issues += ConfigIssue(field.path, Severity.ERROR, "must be a number")
                }
                FieldKind.BOOLEAN -> if (value.trim().lowercase() !in setOf("true", "false")) {
                    issues += ConfigIssue(field.path, Severity.ERROR, "must be true or false")
                }
                FieldKind.STRING -> Unit
            }
        }

        if (effective("device.device_hash").isNullOrBlank()) {
            issues += ConfigIssue("device.device_hash", Severity.ERROR, "the device hash is required")
        }
        effective("measurement.interval_sec")?.toDoubleOrNull()?.let {
            if (it < 60) issues += ConfigIssue("measurement.interval_sec", Severity.ERROR, "must be at least 60 seconds")
        }
        val portalPwd = effective("portal.pwd").orEmpty()
        if (portalPwd.isNotEmpty() && portalPwd.length < 8) {
            issues += ConfigIssue("portal.pwd", Severity.ERROR, "must be empty or at least 8 characters")
        }
        val token = effective("control.token").orEmpty()
        val url = effective("control.url").orEmpty()
        if (token.isNotEmpty() && !url.startsWith("https://")) {
            issues += ConfigIssue("control.url", Severity.WARNING, "the token is sent unencrypted; use an https:// URL")
        }
        val wifiPwd = effective("wifi.pwd").orEmpty()
        if (wifiPwd.isNotEmpty() && effective("wifi.ssid").isNullOrEmpty()) {
            issues += ConfigIssue("wifi.ssid", Severity.WARNING, "a Wi-Fi password without SSID is ignored")
        }
        return issues
    }
}
