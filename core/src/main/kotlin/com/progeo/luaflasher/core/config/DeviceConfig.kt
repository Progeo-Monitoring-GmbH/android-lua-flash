package com.progeo.luaflasher.core.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

    /**
     * The values the template sets, as a config tree: no placeholders, no commented-out
     * fields, no `firmware` table. Tables stay, even when empty.
     */
    fun defaults(): JsonObject = defaultsOf(root, top = true)

    /**
     * [config] with its keys in the template's order (placeholders and commented fields
     * included); keys the template does not know follow in their own order.
     */
    fun ordered(config: JsonObject): JsonObject = orderLike(root, config)

    private fun orderLike(table: LuaValue.Table, obj: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (name in table.fields.map { it.name }.distinct()) {
            val value = obj[name] ?: continue
            val sub = table.fields.firstOrNull { it.name == name && it.value is LuaValue.Table }?.value as? LuaValue.Table
            out[name] = if (value is JsonObject && sub != null) orderLike(sub, value) else value
        }
        for ((k, v) in obj) if (k !in out) out[k] = v
        return JsonObject(out)
    }

    private fun defaultsOf(table: LuaValue.Table, top: Boolean): JsonObject {
        val map = LinkedHashMap<String, JsonElement>()
        for (f in table.fields) {
            if (f.commented || f.isPlaceholder || f.value == LuaValue.Nil) continue
            if (top && f.name in SKIPPED_TABLES) continue
            map[f.name] = when (val v = f.value) {
                is LuaValue.Table -> if (v.fields.any { it.positional }) LuaJson.fromTable(v) else defaultsOf(v, top = false)
                else -> LuaJson.toJson(v)
            }
        }
        return JsonObject(map)
    }

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

/** Builds the `device_config.lua` content the app writes (PLAN.md section 7). */
object DeviceConfigBuilder {
    /**
     * Template defaults, overlaid by the profile (which wins, and may hold keys the template
     * does not know), then the device hash. Placeholders and commented template fields are
     * never part of the defaults; the template's `firmware` table is dropped.
     */
    fun build(template: ConfigTemplate, profile: JsonObject, deviceHash: String? = null): JsonObject {
        var config = LuaJson.merge(template.defaults(), profile)
        if (!deviceHash.isNullOrBlank()) config = LuaJson.with(config, DEVICE_HASH, JsonPrimitive(deviceHash.trim()))
        return template.ordered(config)
    }

    fun lua(template: ConfigTemplate, profile: JsonObject, deviceHash: String? = null, header: String? = null): String =
        LuaJson.write(build(template, profile, deviceHash), header)

    const val DEVICE_HASH = "device.device_hash"
}
enum class Severity { ERROR, WARNING }

data class ConfigIssue(val path: String, val severity: Severity, val message: String)

/** Checks from PLAN.md section 7 plus type checks against the template. */
object ConfigValidator {
    /** Checks a complete config tree (e.g. from [DeviceConfigBuilder.build]). */
    fun validate(template: ConfigTemplate, config: JsonObject): List<ConfigIssue> = validate(template, LuaJson.flatten(config))

    fun validate(template: ConfigTemplate, values: Map<String, String>): List<ConfigIssue> {
        val issues = ArrayList<ConfigIssue>()
        fun effective(path: String) = values[path]?.takeIf { it.isNotEmpty() } ?: template.field(path)?.default

        for (field in template.fields) {
            val value = values[field.path]?.takeIf { it.isNotEmpty() } ?: continue
            when (field.kind) {
                FieldKind.NUMBER -> if (!LuaJson.isNumber(value)) {
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
        // Configs from older boards may hold other values; they still flash, but get flagged.
        for (path in ConfigChoices.paths) {
            val value = values[path]?.takeIf { it.isNotEmpty() } ?: continue
            if (LuaJson.isNumber(value) && ConfigChoices.find(path, value) == null) {
                val allowed = ConfigChoices.of(path)!!.joinToString(", ") { it.label }
                issues += ConfigIssue(path, Severity.WARNING, "$value is not one of the supported values ($allowed)")
            }
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
