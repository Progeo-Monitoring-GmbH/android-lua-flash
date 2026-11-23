package com.progeo.teltonika.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Lenient reading of RutOS answers. Field names and nesting differ between firmware versions,
 * so values are looked up by a list of candidate names, breadth first: a field at the top wins
 * over one further down.
 */

/** The text of a primitive field (numbers and booleans as text), null for missing/null/empty. */
fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotEmpty() }

/** The first of [keys] (case-insensitive) found anywhere in this element, breadth first. */
fun JsonElement?.find(vararg keys: String): String? {
    val wanted = keys.map { it.lowercase() }
    val queue = ArrayDeque<JsonElement>()
    this?.let(queue::addLast)
    while (queue.isNotEmpty()) {
        when (val e = queue.removeFirst()) {
            is JsonObject -> {
                for (k in wanted) {
                    val hit = e.entries.firstOrNull { it.key.lowercase() == k }?.value
                    val text = (hit as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
                    if (text != null) return text
                }
                e.values.forEach { if (it is JsonObject || it is JsonArray) queue.addLast(it) }
            }
            is JsonArray -> e.forEach { if (it is JsonObject || it is JsonArray) queue.addLast(it) }
            else -> Unit
        }
    }
    return null
}

/** The objects of a list answer: `[…]`, or the first array found in an object (`{"modems": […]}`). */
fun JsonElement?.objects(): List<JsonObject> = when (this) {
    is JsonArray -> filterIsInstance<JsonObject>()
    is JsonObject -> (values.firstOrNull { it is JsonArray } as? JsonArray)?.filterIsInstance<JsonObject>() ?: listOf(this)
    else -> emptyList()
}

private val NUMBER = Regex("-?\\d+(\\.\\d+)?")

/** The first number in a text such as `-87`, `-87 dBm` or `12.5`. */
fun String?.number(): Double? = this?.let { NUMBER.find(it)?.value?.toDoubleOrNull() }

/** `1`, `true`, `yes`, `up`, `on`, `connected` → true; `0`, `false`, `no`, `down`, `off` → false. */
fun String?.flag(): Boolean? = when (this?.trim()?.lowercase()) {
    null, "" -> null
    "1", "true", "yes", "up", "on", "connected", "active", "enabled" -> true
    "0", "false", "no", "down", "off", "disconnected", "inactive", "disabled" -> false
    else -> null
}
