package com.progeo.teltonika.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Endpoint paths of the RutOS API.
 *
 * Checked on 2026-11-25 against Teltonika's OpenAPI specs (developers.teltonika-networks.com/docs/
 * `<version>.json`, listed in `available-docs.json`) for RutOS 7.10 (API v1.2), 7.19 (v1.11) and
 * 7.24.8 (v1.16.7): every path below exists in all three with the method used here, and the field
 * names follow the spec. Not yet tried on a real router: answers may still hold surprises.
 */
object RutosPaths {
    const val LOGOUT = "/api/logout"
    val DEVICE_INFO = listOf("/api/system/device/status")
    const val MODEMS = "/api/modems/status"
    const val INTERFACES = "/api/interfaces/status"
    const val DHCP_LEASES = "/api/dhcp/leases/ipv4/status"
    const val REBOOT = "/api/system/actions/reboot"
    const val USERS = "/api/users/config"
    // Firmware: checked against Teltonika's API spec for RutOS 7.24.8 (API v1.16.7), 2026-11-25.
    const val FIRMWARE_UPLOAD = "/api/firmware/actions/upload_device_firmware"
    const val FIRMWARE_UPLOAD_FIELD = "file"
    const val FIRMWARE_VERIFY = "/api/firmware/actions/verify"
    const val FIRMWARE_DELETE = "/api/firmware/actions/delete_device_firmware"
    const val FIRMWARE_UPGRADE = "/api/firmware/actions/upgrade"
    const val BACKUP_GENERATE = "/api/backup/actions/generate"
    const val BACKUP_DOWNLOAD = "/api/backup/actions/download"

    /** The Wi-Fi networks (UCI `wifi-iface`), e.g. `/default_radio0`; fields `ssid`, `key`, `encryption`, `mode`. */
    const val WIFI_INTERFACES = "/api/wireless/interfaces/config"
}

/**
 * The router's answer about an uploaded firmware image (`fw_upload` in the API spec). The flags
 * come as `"1"`/`"0"`; null when the router left them out.
 */
data class UploadedFirmware(
    val valid: Boolean?,
    val hardwareSupported: Boolean?,
    val authorized: Boolean?,
    val version: String?,
    val sha256: String?,
    val messageCode: String?,
) {
    /** Why the router will not install it, or null. */
    val problem: String?
        get() = when {
            valid == false -> "The router says the firmware file is not valid" + (messageCode?.let { " (code $it)" } ?: "") + "."
            hardwareSupported == false -> "The router says this firmware does not support its hardware."
            authorized == false -> "The router says this firmware is not signed by Teltonika."
            else -> null
        }

    companion object {
        fun from(json: JsonElement) = UploadedFirmware(
            valid = json.find("valid").flag(),
            hardwareSupported = json.find("hw_support").flag(),
            authorized = json.find("authorized").flag(),
            version = json.find("fw_version"),
            sha256 = json.find("sha256"),
            messageCode = json.find("message_code"),
        )
    }
}

/** What a Wi-Fi name and WPA2 password must look like. */
object WifiRules {
    /** Why [ssid] / [key] cannot be used, or null. */
    fun problem(ssid: String, key: String): String? = when {
        ssid.isBlank() -> "Enter a Wi-Fi name."
        ssid != ssid.trim() -> "The Wi-Fi name must not start or end with a space."
        ssid.toByteArray().size > 32 -> "The Wi-Fi name is too long (at most 32 characters)."
        key.length !in 8..63 -> "The Wi-Fi password needs 8 to 63 characters."
        key.any { it.code !in 0x20..0x7E } -> "The Wi-Fi password may only use letters, digits and ordinary symbols (no umlauts)."
        else -> null
    }
}

/** One Wi-Fi network of the router. */
data class WifiInterface(val id: String, val ssid: String?, val mode: String?, val encryption: String?, val enabled: Boolean?) {
    /** A network phones join (not a client link to another Wi-Fi). */
    val isAccessPoint: Boolean get() = mode == null || mode.equals("ap", ignoreCase = true)

    companion object {
        fun from(o: JsonObject): WifiInterface? {
            val id = o.find("id", ".name", "section") ?: return null
            return WifiInterface(
                id = id,
                ssid = o.str("ssid"),
                mode = o.str("mode"),
                encryption = o.str("encryption"),
                enabled = o.str("enabled")?.flag() ?: o.str("disabled")?.flag()?.not(),
            )
        }
    }
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
     * Sets a new password for [username]: finds the user's section in the user config and writes
     * `current_password`, `password` and `password_confirm`. The client keeps working with it.
     */
    suspend fun changePassword(username: String, newPassword: String) {
        val users = client.get(RutosPaths.USERS).objects()
        val user = users.firstOrNull { it.find("username", "name") == username }
            ?: throw RouterException("The router has no user '$username'.")
        val id = user.find("id", ".name", "section") ?: throw RouterException("The router did not report the user's ID.")
        client.put(
            "${RutosPaths.USERS}/$id",
            buildJsonObject {
                client.password?.let { put("current_password", it) }
                put("password", newPassword)
                put("password_confirm", newPassword)
            },
        )
        client.rememberPassword(newPassword)
    }

    /** Uploads a firmware image; the router checks it before it can be installed. */
    suspend fun uploadFirmware(image: File, onProgress: (Long, Long) -> Unit): UploadedFirmware =
        UploadedFirmware.from(client.upload(RutosPaths.FIRMWARE_UPLOAD, image, RutosPaths.FIRMWARE_UPLOAD_FIELD, onProgress = onProgress))

    /** The router's check of the uploaded image (fit for this hardware, signed by Teltonika). */
    suspend fun verifyFirmware(): UploadedFirmware = UploadedFirmware.from(client.post(RutosPaths.FIRMWARE_VERIFY))

    /** Removes an uploaded image that will not be installed (frees the router's RAM). */
    suspend fun deleteUploadedFirmware() {
        client.post(RutosPaths.FIRMWARE_DELETE)
    }

    /** Installs the uploaded image; the router reboots. */
    suspend fun upgradeFirmware(keepSettings: Boolean) {
        client.post(RutosPaths.FIRMWARE_UPGRADE, buildJsonObject { put("keep_settings", if (keepSettings) "1" else "0") })
    }

    suspend fun wifiInterfaces(): List<WifiInterface> =
        client.get(RutosPaths.WIFI_INTERFACES).objects().mapNotNull(WifiInterface::from)

    /**
     * Gives every access-point network the name [ssid] and the WPA key [key] (an open network
     * gets WPA2). Returns the changed networks. The router reloads its Wi-Fi afterwards: phones
     * on it lose the connection.
     */
    suspend fun setWifi(ssid: String, key: String): List<WifiInterface> {
        WifiRules.problem(ssid, key)?.let { throw RouterException(it) }
        val aps = wifiInterfaces().filter { it.isAccessPoint }
        if (aps.isEmpty()) throw RouterException("The router reports no Wi-Fi network.")
        for (ap in aps) {
            client.put(
                "${RutosPaths.WIFI_INTERFACES}/${ap.id}",
                buildJsonObject {
                    put("ssid", ssid)
                    put("key", key)
                    // Values per the API spec: none|sae|sae-mixed|wpa|wpa2|owe|wpa3-mixed|wpa3. A key needs WPA.
                    if (ap.encryption == null || ap.encryption.lowercase() in setOf("none", "owe")) put("encryption", "wpa2")
                },
            )
        }
        return aps
    }

    /** A config backup file of the router (for rollback, not as a template). */
    suspend fun backup(): ByteArray {
        // `sysupgrade`: the usual settings archive (.tar.gz); the other type, `api`, is a JSON export.
        client.post(RutosPaths.BACKUP_GENERATE, buildJsonObject { put("type", "sysupgrade"); put("encrypt", "0") })
        return client.download(RutosPaths.BACKUP_DOWNLOAD, post = true)
    }
}
