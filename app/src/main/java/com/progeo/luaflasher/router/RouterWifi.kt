package com.progeo.luaflasher.router

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A router's Wi-Fi as a microBOX profile can use it (`wifi.ssid`, `wifi.pwd`). */
data class RouterWifi(
    val serial: String,
    /** Model and serial, to tell the routers apart. */
    val label: String,
    val ssid: String,
    /** Null: an open network. */
    val key: String?,
    /** Still the Wi-Fi from the router's label: the setup has not changed it yet. */
    val factory: Boolean,
) {
    /** The `wifi` section with this network; its other values stay. An open network has no `pwd`. */
    fun section(current: JsonObject?): JsonObject {
        val values = LinkedHashMap(current.orEmpty())
        values["ssid"] = JsonPrimitive(ssid)
        if (key.isNullOrEmpty()) values.remove("pwd") else values["pwd"] = JsonPrimitive(key)
        return JsonObject(values)
    }

    override fun toString() = "RouterWifi($serial, $ssid, ${if (key == null) "-" else "***"}, factory=$factory)"

    companion object {
        const val SECTION = "wifi"

        /** The routers on the phone whose Wi-Fi is known, last seen first. */
        fun of(routers: List<RouterRecord>): List<RouterWifi> = routers
            .sortedByDescending { it.lastSeen.orEmpty() }
            .mapNotNull { r -> of(r) }

        fun of(r: RouterRecord): RouterWifi? {
            val access = r.access ?: r.factoryAccess
            val ssid = access?.ssid?.takeIf { it.isNotEmpty() } ?: return null
            return RouterWifi(
                serial = r.serial,
                label = listOfNotNull(r.model, r.serial).joinToString(" · "),
                ssid = ssid,
                key = access.wifiKey,
                factory = r.access == null,
            )
        }
    }
}
