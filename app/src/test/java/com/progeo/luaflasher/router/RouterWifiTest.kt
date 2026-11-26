package com.progeo.luaflasher.router

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterWifiTest {

    @Test
    fun `confirmed wifi wins over the label, last seen first, unknown wifi left out`() {
        val routers = listOf(
            RouterRecord("A1", model = "RUT241", lastSeen = "2026-11-20T10:00+01:00", factoryAccess = RouterAccess("RUT241_A1", "label-key")),
            RouterRecord(
                "B2", model = "RUTX11", lastSeen = "2026-11-26T09:00+01:00",
                factoryAccess = RouterAccess("RUTX11_B2", "label-key"), access = RouterAccess("site-4711", "new-key"),
            ),
            RouterRecord("C3", lastSeen = "2026-11-26T11:00+01:00"),
        )
        val wifis = RouterWifi.of(routers)
        assertEquals(listOf("B2", "A1"), wifis.map { it.serial })
        assertEquals("site-4711", wifis[0].ssid)
        assertEquals("new-key", wifis[0].key)
        assertFalse(wifis[0].factory)
        assertTrue(wifis[1].factory)
        assertEquals("RUTX11 · B2", wifis[0].label)
    }

    @Test
    fun `section keeps the other wifi values`() {
        val wifi = RouterWifi("B2", "B2", "site-4711", "new-key", factory = false)
        val section = wifi.section(JsonObject(mapOf("ssid" to JsonPrimitive("old"), "pwd" to JsonPrimitive("old-key"), "timeout_sec" to JsonPrimitive(30))))
        assertEquals(JsonPrimitive("site-4711"), section["ssid"])
        assertEquals(JsonPrimitive("new-key"), section["pwd"])
        assertEquals(JsonPrimitive(30), section["timeout_sec"])
    }

    @Test
    fun `open network has no password`() {
        val section = RouterWifi("A1", "A1", "open-net", null, factory = true).section(JsonObject(mapOf("pwd" to JsonPrimitive("old-key"))))
        assertEquals(JsonPrimitive("open-net"), section["ssid"])
        assertNull(section["pwd"])
    }

    @Test
    fun `key is not in toString`() {
        assertFalse("new-key" in RouterWifi("B2", "B2", "site", "new-key", factory = false).toString())
    }
}
