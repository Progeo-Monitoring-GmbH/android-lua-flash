package com.progeo.luaflasher.router

import com.progeo.core.auth.BackendClient
import com.progeo.core.auth.BackendNotFoundException
import com.progeo.teltonika.api.str
import com.progeo.teltonika.config.RouterTemplate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * The router endpoints of the ProGeo backend. **Proposed contract, not built yet** (DRAFT.md
 * section 9): agree on it with the backend team before relying on it.
 *
 * - `GET  api/routers/<serial>/`              record, with `credentials` if the account may read them; 404 if unknown
 * - `POST api/routers/<serial>/`              create/update the record (no secrets)
 * - `POST api/routers/<serial>/credentials/`  store the secrets (needs `module_routers_credentials`)
 * - `POST api/routers/<serial>/backups/`      `{created, file_name, content_base64}`
 * - `POST api/routers/<serial>/reports/`      installation report
 * - `GET  api/routers/templates/?model=…`     `{"templates": [RouterTemplate, …]}`
 *
 * Every write is audited by the server (user from the token).
 */
class RouterBackend(private val backend: BackendClient) {

    /** The record, or null when the backend does not know the router. */
    suspend fun get(serial: String): RouterRecord? = try {
        val json = backend.get("api/routers/$serial/") as? JsonObject ?: return null
        val record = RouterStore.json.decodeFromJsonElement<RouterRecord>(JsonObject(json - "credentials"))
        val c = json["credentials"] as? JsonObject
        if (c == null) {
            record
        } else {
            record.copy(
                username = c.str("username") ?: record.username,
                password = c.str("password"),
                pendingPassword = c.str("pending_password"),
                factoryPassword = c.str("factory_password"),
                templateWifiKey = c.str("template_wifi_key"),
                access = record.access?.copy(wifiKey = c.str("wifi_key")),
                pendingAccess = record.pendingAccess?.copy(wifiKey = c.str("pending_wifi_key")),
                factoryAccess = record.factoryAccess?.copy(wifiKey = c.str("factory_wifi_key")),
            )
        }
    } catch (e: BackendNotFoundException) {
        null
    }

    suspend fun saveRecord(record: RouterRecord) {
        val public = record.copy(
            password = null, pendingPassword = null, factoryPassword = null, templateWifiKey = null,
            access = record.access?.copy(wifiKey = null),
            pendingAccess = record.pendingAccess?.copy(wifiKey = null),
            factoryAccess = record.factoryAccess?.copy(wifiKey = null),
        )
        backend.post("api/routers/${record.serial}/", body = RouterStore.json.encodeToJsonElement(public) as JsonObject)
    }

    suspend fun saveCredentials(record: RouterRecord) {
        val body = buildJsonObject {
            put("username", record.username)
            put("password", record.password)
            put("pending_password", record.pendingPassword)
            put("factory_password", record.factoryPassword)
            put("template_wifi_key", record.templateWifiKey)
            put("wifi_key", record.access?.wifiKey)
            put("pending_wifi_key", record.pendingAccess?.wifiKey)
            put("factory_wifi_key", record.factoryAccess?.wifiKey)
        }
        backend.post("api/routers/${record.serial}/credentials/", body = body)
    }

    suspend fun uploadBackup(serial: String, created: String, fileName: String, content: ByteArray) {
        backend.post(
            "api/routers/$serial/backups/",
            body = buildJsonObject {
                put("created", created)
                put("file_name", fileName)
                put("content_base64", Base64.getEncoder().encodeToString(content))
            },
        )
    }

    suspend fun sendReport(serial: String, report: JsonObject) {
        backend.post("api/routers/$serial/reports/", body = report)
    }

    suspend fun templates(model: String?): List<RouterTemplate> {
        val json: JsonElement = backend.get("api/routers/templates/", model?.let { mapOf("model" to it) } ?: emptyMap())
        val list = (json as? JsonObject)?.get("templates") as? JsonArray ?: json as? JsonArray ?: return emptyList()
        return list.mapNotNull { runCatching { RouterTemplate.json.decodeFromJsonElement<RouterTemplate>(it) }.getOrNull() }
    }
}
