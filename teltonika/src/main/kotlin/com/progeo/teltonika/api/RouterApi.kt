package com.progeo.teltonika.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Endpoint paths of the RutOS API.
 *
 * Only `/api/login` and `/api/firmware/actions/upgrade` (with `keep_settings`) are confirmed in
 * public sources. Everything else is **[unverified]** (DRAFT.md, milestone M0): check it against
 * the API reference of our approved firmware version (developers.teltonika-networks.com, pick
 * model and version) and correct it here. Where versions are known to differ, several candidates
 * are tried in order.
 */
object RutosPaths {
    const val LOGOUT = "/api/logout"
    val DEVICE_INFO = listOf("/api/system/device/status", "/api/system/device/info")
    const val MODEMS = "/api/modems/status"
    const val INTERFACES = "/api/interfaces/status"
    const val DHCP_LEASES = "/api/dhcp/leases/ipv4/status"
    const val REBOOT = "/api/system/actions/reboot"
    const val USERS = "/api/users/config"
    const val FIRMWARE_UPLOAD = "/api/firmware/device/upload"
    const val FIRMWARE_UPLOAD_FIELD = "file"
    const val FIRMWARE_UPGRADE = "/api/firmware/actions/upgrade"
    const val BACKUP_GENERATE = "/api/backup/actions/generate"
    const val BACKUP_DOWNLOAD = "/api/backup/actions/download"
}

/** Typed calls on top of [RutosClient]. */
class RouterApi(val client: RutosClient) {

    /** Merges the answers of all device-info endpoints this firmware offers. */
    suspend fun device(): DeviceInfo {
        val parts = ArrayList<JsonElement>()
        var lastError: RouterApiException? = null
        for (path in RutosPaths.DEVICE_INFO) {
            try {
                parts += client.get(path)
            } catch (e: RouterApiException) {
                lastError = e
            }
        }
        if (parts.isEmpty()) throw lastError ?: RouterException("The router did not report its identity.")
        val merged = parts.filterIsInstance<JsonObject>().fold(JsonObject(emptyMap())) { acc, o -> JsonObject(o + acc) }
        return DeviceInfo.from(if (merged.isEmpty()) parts.first() else merged)
    }

    suspend fun modems(): List<ModemStatus> = ModemStatus.list(client.get(RutosPaths.MODEMS))

    suspend fun interfaces(): List<InterfaceStatus> = InterfaceStatus.list(client.get(RutosPaths.INTERFACES))

    suspend fun dhcpLeases(): List<DhcpLease> = DhcpLease.list(client.get(RutosPaths.DHCP_LEASES))

    suspend fun reboot() {
        client.post(RutosPaths.REBOOT)
    }

    /**
     * Sets a new password for [username] [unverified]: finds the user's section in the user
     * config and writes `password` and `password_confirm`. The client keeps working with it.
     */
    suspend fun changePassword(username: String, newPassword: String) {
        val users = client.get(RutosPaths.USERS).objects()
        val user = users.firstOrNull { it.find("username", "name") == username }
            ?: throw RouterException("The router has no user '$username'.")
        val id = user.find("id", ".name", "section") ?: throw RouterException("The router did not report the user's ID.")
        client.put(
            "${RutosPaths.USERS}/$id",
            buildJsonObject {
                put("password", newPassword)
                put("password_confirm", newPassword)
            },
        )
        client.rememberPassword(newPassword)
    }

    /** Uploads a firmware image; the router checks it before it can be installed. */
    suspend fun uploadFirmware(image: File, onProgress: (Long, Long) -> Unit): JsonElement =
        client.upload(RutosPaths.FIRMWARE_UPLOAD, image, RutosPaths.FIRMWARE_UPLOAD_FIELD, onProgress = onProgress)

    /** Installs the uploaded image; the router reboots. */
    suspend fun upgradeFirmware(keepSettings: Boolean) {
        client.post(RutosPaths.FIRMWARE_UPGRADE, buildJsonObject { put("keep_settings", if (keepSettings) "1" else "0") })
    }

    /** A config backup file of the router (for rollback, not as a template). */
    suspend fun backup(): ByteArray {
        runCatching { client.post(RutosPaths.BACKUP_GENERATE) }
        return client.download(RutosPaths.BACKUP_DOWNLOAD, post = true)
    }
}
