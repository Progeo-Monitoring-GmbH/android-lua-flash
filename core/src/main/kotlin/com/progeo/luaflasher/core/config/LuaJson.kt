package com.progeo.luaflasher.core.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `device_config.lua` as JSON: tables become objects, lists become arrays, strings, numbers
 * and booleans stay what they are. Profiles keep a config in this form so every value can be
 * edited, and [write] turns it back into Lua for the device.
 */
object LuaJson {

    /** Parses `return { ... }`. Comment-only fields are dropped; `nil` values are left out. */
    fun parse(source: String): JsonObject = fromTable(LuaTableParser.parse(source)) as? JsonObject
        ?: throw LuaParseException("the config is a list, not a table", 1)

    fun fromTable(table: LuaValue.Table): JsonElement {
        val fields = table.fields.filter { !it.commented && it.value != LuaValue.Nil }
        if (fields.isNotEmpty() && fields.all { it.positional }) return JsonArray(fields.map { toJson(it.value) })
        val map = LinkedHashMap<String, JsonElement>()
        for (f in fields) map[f.name] = toJson(f.value)
        return JsonObject(map)
    }

    internal fun toJson(v: LuaValue): JsonElement = when (v) {
        is LuaValue.Str -> JsonPrimitive(v.value)
        is LuaValue.Num -> number(v.literal)
        is LuaValue.Bool -> JsonPrimitive(v.value)
        LuaValue.Nil -> JsonNull
        is LuaValue.Table -> fromTable(v)
    }

    /** A Lua number literal as a JSON number (hex becomes decimal). */
    fun number(literal: String): JsonPrimitive {
        val text = literal.trim()
        val negative = text.startsWith("-")
        val body = text.removePrefix("-")
        val sign = if (negative) -1 else 1
        if (body.startsWith("0x") || body.startsWith("0X")) return JsonPrimitive(sign * body.substring(2).toLong(16))
        body.toLongOrNull()?.let { return JsonPrimitive(sign * it) }
        return JsonPrimitive(text.toDouble())
    }

    private val NUMBER = Regex("-?(?:0[xX][0-9a-fA-F]+|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)")

    fun isNumber(text: String) = NUMBER.matches(text.trim())

    /** `return { ... }` with two-space indentation, keys in the object's order. */
    fun write(config: JsonObject, header: String? = null): String {
        val sb = StringBuilder()
        header?.lineSequence()?.forEach { sb.append("-- ").append(it).append('\n') }
        sb.append("return ")
        writeValue(config, sb, 0)
        sb.append('\n')
        return sb.toString()
    }

    private fun writeValue(e: JsonElement, sb: StringBuilder, depth: Int) {
        when (e) {
            is JsonObject -> writeEntries(e.entries.map { (k, v) -> key(k) + " = " to v }, sb, depth)
            is JsonArray -> writeEntries(e.map { "" to it }, sb, depth)
            JsonNull -> sb.append("nil")
            is JsonPrimitive -> sb.append(
                when {
                    e.isString -> LuaString.quote(e.content)
                    e.content == "true" || e.content == "false" -> e.content
                    isNumber(e.content) -> e.content
                    else -> throw IllegalArgumentException("not a Lua value: ${e.content}")
                },
            )
        }
    }

    private fun writeEntries(entries: List<Pair<String, JsonElement>>, sb: StringBuilder, depth: Int) {
        if (entries.isEmpty()) {
            sb.append("{}")
            return
        }
        val indent = "  ".repeat(depth + 1)
        sb.append("{\n")
        for ((prefix, value) in entries) {
            if (value == JsonNull) continue
            sb.append(indent).append(prefix)
            writeValue(value, sb, depth + 1)
            sb.append(",\n")
        }
        sb.append("  ".repeat(depth)).append('}')
    }

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val KEYWORDS = setOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
        "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
    )

    private fun key(name: String) = if (IDENTIFIER.matches(name) && name !in KEYWORDS) name else "[" + LuaString.quote(name) + "]"

    /**
     * [overlay] on top of [base]: nested objects are merged, everything else in [overlay] wins.
     * Keys keep [base]'s order; keys only in [overlay] come after them.
     */
    fun merge(base: JsonObject, overlay: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in base) {
            val o = overlay[k]
            out[k] = if (v is JsonObject && o is JsonObject) merge(v, o) else o ?: v
        }
        for ((k, v) in overlay) if (k !in out) out[k] = v
        return JsonObject(out)
    }

    /** Leaves as dotted paths (`wifi.ssid`); list entries use 1-based indexes. */
    fun flatten(config: JsonElement, prefix: String = ""): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        fun walk(e: JsonElement, path: String) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> walk(v, if (path.isEmpty()) k else "$path.$k") }
                is JsonArray -> e.forEachIndexed { i, v -> walk(v, "$path.${i + 1}") }
                JsonNull -> Unit
                is JsonPrimitive -> out[path] = e.content
            }
        }
        walk(config, prefix)
        return out
    }

    fun get(config: JsonObject, path: String): JsonElement? {
        var e: JsonElement = config
        for (part in path.split('.')) {
            e = when (e) {
                is JsonObject -> e[part] ?: return null
                is JsonArray -> part.toIntOrNull()?.let { e.getOrNull(it - 1) } ?: return null
                else -> return null
            }
        }
        return e
    }

    /** Sets (or with null removes) the value at a dotted path, creating objects on the way. */
    fun with(config: JsonObject, path: String, value: JsonElement?): JsonObject = withParts(config, path.split('.'), value) as JsonObject

    private fun withParts(e: JsonElement?, parts: List<String>, value: JsonElement?): JsonElement? {
        val head = parts.first()
        val rest = parts.drop(1)
        if (e is JsonArray) {
            val index = head.toIntOrNull()?.minus(1) ?: throw IllegalArgumentException("list index expected: $head")
            val items = e.toMutableList()
            val child = if (rest.isEmpty()) value else withParts(items.getOrNull(index), rest, value)
            when {
                index < items.size && child != null -> items[index] = child
                index < items.size -> items.removeAt(index)
                index == items.size && child != null -> items += child
                child != null -> throw IllegalArgumentException("list index out of range: $head")
            }
            return JsonArray(items)
        }
        val map = LinkedHashMap((e as? JsonObject).orEmpty())
        val child = if (rest.isEmpty()) value else withParts(map[head], rest, value)
        if (child == null) map.remove(head) else map[head] = child
        return JsonObject(map)
    }

    /**
     * Text from an editor as the JSON value of the given kind; null if it does not fit
     * (e.g. "abc" for a number).
     */
    fun typed(text: String, kind: FieldKind): JsonPrimitive? = when (kind) {
        FieldKind.STRING -> JsonPrimitive(text)
        FieldKind.NUMBER -> if (isNumber(text)) number(text) else null
        FieldKind.BOOLEAN -> when (text.trim().lowercase()) {
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            else -> null
        }
    }

    fun kindOf(e: JsonPrimitive): FieldKind = when {
        e.isString -> FieldKind.STRING
        e.content == "true" || e.content == "false" -> FieldKind.BOOLEAN
        else -> FieldKind.NUMBER
    }
}
