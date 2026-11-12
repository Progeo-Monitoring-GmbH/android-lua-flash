package com.progeo.luaflasher.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A device config from a scanned QR code: a whole or partial `device_config.lua`
 * (`return { … }` or just `{ … }`) or the same as JSON, e.g. `{"wifi": {"ssid": "Site"}}`.
 */
object ConfigCode {
    sealed interface Result {
        /** [paths]: the values the code sets, as dotted paths. */
        data class Valid(val config: JsonObject, val paths: List<String>) : Result
        data class Invalid(val reason: String) : Result
    }

    /** Reads [text] and checks it against [template]: known sections, right types, sane values. */
    fun parse(text: String, template: ConfigTemplate): Result {
        val s = text.trim()
        if (s.isEmpty()) return Result.Invalid("The code is empty.")
        val config = read(s)?.let { normalize(it, template) } ?: return Result.Invalid("The code holds no device config.")

        val known = template.root.fields.map { it.name }.toSet() - ConfigTemplate.SKIPPED_TABLES
        if (config.keys.none { it in known }) {
            return Result.Invalid("The code holds no device config (none of ${known.joinToString()}).")
        }
        val values = LuaJson.flatten(config)
        if (values.isEmpty()) return Result.Invalid("The code holds no values.")

        // Only what the code itself gets wrong; a missing device hash is fine for a partial config.
        val errors = ConfigValidator.validate(template, values)
            .filter { it.severity == Severity.ERROR && it.path != DeviceConfigBuilder.DEVICE_HASH }
        if (errors.isNotEmpty()) {
            return Result.Invalid("The config in the code is invalid: " + errors.joinToString("; ") { "${it.path} ${it.message}" })
        }
        return Result.Valid(config, values.keys.toList())
    }

    /**
     * Each value in the template's type, as the board expects it: JSON written by other tools
     * may send `"interval_sec": "1800"` or `"project_id": 4711`. Values that do not fit stay
     * as they are and fail the validation.
     */
    private fun normalize(config: JsonObject, template: ConfigTemplate): JsonObject {
        var out = config
        for ((path, text) in LuaJson.flatten(config)) {
            val kind = template.field(path)?.kind ?: continue
            val current = LuaJson.get(config, path) as? JsonPrimitive ?: continue
            val fixed = when {
                kind == FieldKind.STRING && !current.isString -> JsonPrimitive(text)
                kind != FieldKind.STRING && current.isString -> LuaJson.typed(text, kind)
                else -> null
            }
            if (fixed != null) out = LuaJson.with(out, path, fixed)
        }
        return out
    }

    private fun read(s: String): JsonObject? {
        if (s.startsWith("{")) {
            runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull()?.let { return it }
        }
        val lua = if (s.startsWith("{")) "return $s" else s
        return runCatching { LuaJson.parse(lua) }.getOrNull()
    }
}
