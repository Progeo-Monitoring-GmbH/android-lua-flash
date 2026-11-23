package com.progeo.teltonika

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A simulated RutOS router for MockWebServer, built on the answer shapes we expect
 * (`{"success": …, "data": …}`). Replace with recorded answers from real devices after M0.
 */
class FakeRutos(
    var password: String = "Factory1",
    private val sessionSeconds: Int = 299,
) : Dispatcher() {
    val tokens = mutableSetOf<String>()
    var logins = 0
        private set
    val requests = CopyOnWriteArrayList<String>()
    val config = HashMap<String, JsonObject>()
    var failLogins = 0
    var uploadedBytes = 0L
        private set
    var upgradeKeepSettings: String? = null
        private set
    var lockedOut = false

    var device: JsonObject = buildJsonObject {
        put("static", buildJsonObject {
            put("fw_version", "RUT2M_R_00.07.06.1")
            put("device_name", "RUT241")
            put("hostname", "RUT241")
        })
        put("mnf_info", buildJsonObject {
            put("serial", "1234567890")
            put("mac", "001E42AABBCC")
            put("name", "RUT241010000")
            put("product_code", "RUT241010000")
            put("hw_version", "0101")
            put("batch", "0007")
        })
    }

    var modems: JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("id", "1-1")
            put("imei", "860000000000001")
            put("operator", "Telekom.de")
            put("conntype", "LTE")
            put("band", "LTE B3")
            put("rssi", "-67")
            put("rsrp", -92)
            put("rsrq", "-11.0 dB")
            put("sinr", "14")
            put("simstate", "Inserted")
            put("pinstate", "OK")
            put("iccid", "8949000000000000001")
            put("active_sim", 1)
        })
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        requests += "${request.method} $path"
        if (path == "/api/login") return login(request)
        val token = request.getHeader("Authorization")?.removePrefix("Bearer ")
        if (token == null || token !in tokens) return error(401, 120, "Unauthorized access")
        return when {
            path == "/api/system/device/status" -> ok(device)
            path == "/api/system/device/info" -> error(404, 122, "Endpoint not implemented")
            path == "/api/modems/status" -> ok(modems)
            path == "/api/users/config" -> ok(buildJsonArray { add(buildJsonObject { put("id", "cfg01"); put("username", "admin") }) })
            path == "/api/users/config/cfg01" && request.method == "PUT" -> {
                password = body(request)["data"]!!.jsonObject["password"]!!.jsonPrimitive.content
                ok(buildJsonObject { put("id", "cfg01") })
            }
            path == "/api/firmware/device/upload" -> {
                uploadedBytes = request.bodySize
                ok(buildJsonObject { put("size", request.bodySize) })
            }
            path == "/api/firmware/actions/upgrade" -> {
                upgradeKeepSettings = body(request)["data"]?.jsonObject?.get("keep_settings")?.jsonPrimitive?.content
                ok(buildJsonObject { })
            }
            path == "/api/backup/actions/generate" -> ok(buildJsonObject { })
            path == "/api/backup/actions/download" ->
                MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(BACKUP))
            path in config && request.method == "GET" -> ok(config.getValue(path))
            path in config && request.method == "PUT" -> {
                val data = body(request)["data"]!!.jsonObject
                config[path] = JsonObject(config.getValue(path) + data)
                ok(config.getValue(path))
            }
            else -> error(404, 122, "Endpoint not implemented")
        }
    }

    private fun login(request: RecordedRequest): MockResponse {
        if (lockedOut) return error(429, 121, "Too many failed attempts, login blocked")
        val body = body(request)
        if (failLogins > 0) {
            failLogins--
            return error(401, 121, "Authorization failed")
        }
        if (body["password"]?.jsonPrimitive?.content != password) return error(401, 121, "Authorization failed")
        logins++
        val token = "token-$logins"
        tokens += token
        return ok(buildJsonObject {
            put("username", body["username"]!!.jsonPrimitive.content)
            put("token", token)
            put("expires", sessionSeconds)
        })
    }

    private fun body(request: RecordedRequest): JsonObject =
        Json.parseToJsonElement(request.body.clone().readUtf8().ifEmpty { "{}" }).jsonObject

    private fun ok(data: JsonElement) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(buildJsonObject { put("success", true); put("data", data) }.toString())

    private fun error(http: Int, code: Int, text: String) = MockResponse()
        .setResponseCode(http)
        .setHeader("Content-Type", "application/json")
        .setBody(
            buildJsonObject {
                put("success", false)
                put("errors", buildJsonArray { add(buildJsonObject { put("code", code); put("error", text); put("source", "fake") }) })
            }.toString(),
        )

    companion object {
        val BACKUP = ByteArray(2048) { (it % 251).toByte() }

        fun section(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
    }
}
