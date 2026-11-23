package com.progeo.teltonika.label

/** User name and password for the router's WebUI and API. */
data class Credentials(val username: String, val password: String) {
    override fun toString() = "Credentials($username, ***)"
}

/** Wi-Fi access point of the router. */
data class WifiAccess(val ssid: String, val passphrase: String?, val security: String = "WPA") {
    override fun toString() = "WifiAccess($ssid, ${if (passphrase == null) "open" else "***"}, $security)"
}

/**
 * What a scanned code tells about a router. Teltonika labels (RUT241 from batch 006, RUT986, RUT901):
 *
 * - device label: `WIFI:T:WPA;S:<ssid>;P:<wifi key>;SN:<serial>;I:<imei>;M:<mac>;U:<user>;PW:<password>;B:<batch>;`
 * - box label: `SN:<serial>;I:<imei>;M:<mac>;B:<batch>;`
 * - serial barcode (Code 128): the 10 digits of the serial number
 */
data class RouterLabel(
    val serial: String,
    val source: Source,
    val imei: String? = null,
    /** LAN MAC as `AA:BB:CC:DD:EE:FF`. */
    val mac: String? = null,
    val batch: String? = null,
    val wifi: WifiAccess? = null,
    /** The factory login printed on the device label. */
    val login: Credentials? = null,
) {
    enum class Source {
        /** The full code on the device: identity, Wi-Fi and factory login. */
        DEVICE_LABEL,

        /** The code on the box: identity only. */
        BOX_LABEL,

        /** Serial number only (barcode or typed). */
        SERIAL,
    }

    /** Enough to set up a router the backend does not know yet. */
    val hasAccess: Boolean get() = wifi != null && login != null
}

object RouterLabels {
    private val SERIAL = Regex("\\d{10}")
    private val HEX12 = Regex("[0-9A-Fa-f]{12}")

    /** Parses a scanned or typed code; null when it is no Teltonika router label. */
    fun parse(text: String): RouterLabel? {
        val code = text.trim()
        if (SERIAL.matches(code)) return RouterLabel(serial = code, source = RouterLabel.Source.SERIAL)
        val isDevice = code.startsWith("WIFI:", ignoreCase = true)
        val fields = fields(if (isDevice) code.substring(5) else code)
        val serial = fields["SN"]?.trim()?.takeIf { SERIAL.matches(it) } ?: return null
        val mac = fields["M"]?.let(::normalizeMac)
        val wifi = fields["S"]?.takeIf { it.isNotEmpty() }?.let { ssid ->
            WifiAccess(ssid = ssid, passphrase = fields["P"]?.takeIf { it.isNotEmpty() }, security = fields["T"]?.ifBlank { null } ?: "WPA")
        }
        val password = fields["PW"]?.takeIf { it.isNotEmpty() }
        val login = password?.let { Credentials(fields["U"]?.ifBlank { null } ?: "admin", it) }
        return RouterLabel(
            serial = serial,
            source = if (isDevice) RouterLabel.Source.DEVICE_LABEL else RouterLabel.Source.BOX_LABEL,
            imei = fields["I"]?.trim()?.ifEmpty { null },
            mac = mac,
            batch = fields["B"]?.trim()?.ifEmpty { null },
            wifi = wifi,
            login = login,
        )
    }

    /** `AABBCCDDEEFF`, `aa:bb:…` or `AA-BB-…` → `AA:BB:CC:DD:EE:FF`; null if it is no MAC. */
    fun normalizeMac(text: String): String? {
        val hex = text.replace(":", "").replace("-", "").trim()
        if (!HEX12.matches(hex)) return null
        return hex.uppercase().chunked(2).joinToString(":")
    }

    /**
     * `KEY:value;KEY:value;` with the escapes of the Wi-Fi QR format: `\;`, `\:`, `\,`, `\"`
     * and `\\` stand for the character itself. Keys are upper-cased; the first one wins.
     */
    internal fun fields(body: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inValue = false
        var i = 0
        fun flush() {
            if (key.isNotEmpty()) result.putIfAbsent(key.toString().trim().uppercase(), value.toString())
            key.clear()
            value.clear()
            inValue = false
        }
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> {
                    (if (inValue) value else key).append(body[i + 1])
                    i++
                }
                c == ';' -> flush()
                c == ':' && !inValue -> inValue = true
                else -> (if (inValue) value else key).append(c)
            }
            i++
        }
        flush()
        return result
    }
}
