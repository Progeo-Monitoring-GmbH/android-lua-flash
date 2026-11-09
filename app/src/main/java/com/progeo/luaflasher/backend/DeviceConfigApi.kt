package com.progeo.luaflasher.backend

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A controller of a location, from `GET /v1/device/?location=<id>`. */
data class RemoteDevice(val id: Long, val hash: String?, val label: String)

/** What the server knows about a controller's `device_config.lua` (API2.md, step 4). */
data class RemoteConfig(
    val deviceId: Long,
    val rawHash: String?,
    val projectId: Long?,
    val model: String?,
    /** Newest file the controller uploaded, or null if there is none (yet / any more). */
    val content: String?,
    val path: String?,
    val fetchedAt: String?,
    val commandId: Long?,
    /** Status of the open request: "pending" (not picked up yet), "sent", or null if none is open. */
    val requestStatus: String?,
    val lastCommandPoll: String?,
    val resultTtlDays: Int?,
)

/**
 * Fetching a controller's config (API2.md). The controller uploads it the next time it polls
 * for commands (every 4 h, 15 s in maintenance mode), so a request takes a while.
 * Every call carries `?location=`, because device ids are only unique within a project.
 */
class DeviceConfigApi(private val backend: BackendClient) {

    suspend fun devices(locationId: String): List<RemoteDevice> {
        val json = backend.get("v1/device/", mapOf("location" to locationId))
        return deviceList(json)
    }

    /** Asks the controller for its config. Asking again while a request is open does not queue another. */
    suspend fun requestConfig(deviceId: Long, locationId: String) {
        backend.post("v1/device/$deviceId/config/request/", mapOf("location" to locationId))
    }

    suspend fun config(deviceId: Long, locationId: String): RemoteConfig =
        parseConfig(deviceId, backend.get("v1/device/$deviceId/config/", mapOf("location" to locationId)))

    companion object {
        /**
         * The device list. API2.md does not show its shape, so this accepts a plain array or an
         * object with the list under `results`, `devices` or `data`, and takes the hash from
         * the first of `raw_hash`, `hash`, `device_hash`.
         */
        fun deviceList(json: JsonElement): List<RemoteDevice> {
            val array = when (json) {
                is JsonArray -> json
                is JsonObject -> listOf("results", "devices", "data", "items").firstNotNullOfOrNull { json[it] as? JsonArray } ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            return array.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val hash = listOf("raw_hash", "hash", "device_hash").firstNotNullOfOrNull { o.str(it) }
                val name = listOf("name", "title", "label", "description").firstNotNullOfOrNull { o.str(it) }
                val label = listOfNotNull(hash, name?.takeIf { it != hash }).joinToString(" · ").ifEmpty { "Device $id" }
                RemoteDevice(id, hash, label)
            }.sortedBy { it.hash ?: it.label }
        }

        fun parseConfig(deviceId: Long, json: JsonElement): RemoteConfig {
            val o = json as? JsonObject ?: JsonObject(emptyMap())
            val device = o["device"] as? JsonObject
            val config = o["config"] as? JsonObject
            val requested = o["requested"] as? JsonObject
            return RemoteConfig(
                deviceId = (device?.get("id") as? JsonPrimitive)?.longOrNull ?: deviceId,
                rawHash = device.str("raw_hash"),
                projectId = (device?.get("project_id") as? JsonPrimitive)?.longOrNull,
                model = device.str("model"),
                content = config.str("content"),
                path = config.str("path"),
                fetchedAt = config.str("fetched_at"),
                commandId = (config?.get("command_id") as? JsonPrimitive)?.longOrNull,
                requestStatus = requested?.let { it.str("status") ?: "pending" },
                lastCommandPoll = o.str("last_command_poll"),
                resultTtlDays = o["result_ttl_days"]?.jsonPrimitive?.intOrNull,
            )
        }
    }
}
