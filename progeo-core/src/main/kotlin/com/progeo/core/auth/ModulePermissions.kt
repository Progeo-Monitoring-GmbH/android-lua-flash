package com.progeo.core.auth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The portal modules an account may use, e.g. `module_devices_enabled` or
 * `module_devices_commands`. For the UI only: the server checks every call itself.
 */
data class ModulePermissions(val enabled: Set<String>) {
    fun has(permission: String) = permission in enabled

    /** Which of [required] the account lacks. */
    fun missing(required: Collection<String>): List<String> = required.filterNot(::has)

    companion object {
        /** `{"enabled": [...]}`, or the `permissions` map `{code: true|false}` when `enabled` is missing. */
        fun parse(json: JsonElement): ModulePermissions {
            val o = json as? JsonObject ?: return ModulePermissions(emptySet())
            (o["enabled"] as? JsonArray)?.let { list ->
                return ModulePermissions(list.mapNotNull { (it as? JsonPrimitive)?.content }.toSet())
            }
            val map = o["permissions"] as? JsonObject ?: return ModulePermissions(emptySet())
            return ModulePermissions(map.filterValues { (it as? JsonPrimitive)?.booleanOrNull == true }.keys)
        }
    }
}
