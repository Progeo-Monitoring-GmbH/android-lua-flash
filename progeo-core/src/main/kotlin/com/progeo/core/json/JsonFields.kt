package com.progeo.core.json

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The field [name] of a JSON object as text (numbers too); null if missing or null. */
fun JsonElement?.str(name: String): String? =
    ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

/** The boolean field [name] of a JSON object; false if missing. */
fun JsonElement?.flag(name: String): Boolean =
    ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.booleanOrNull ?: false
