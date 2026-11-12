package com.progeo.luaflasher.core.config

import com.progeo.luaflasher.core.Fixtures
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfigCodeTest {

    private val template = ConfigTemplate.parse(Fixtures.text("device_config.lua.template"))

    private fun valid(text: String) = assertIs<ConfigCode.Result.Valid>(ConfigCode.parse(text, template))
    private fun invalid(text: String) = assertIs<ConfigCode.Result.Invalid>(ConfigCode.parse(text, template))

    @Test
    fun `whole device_config lua`() {
        val r = valid(
            """
            -- written by the setup tool
            return {
              wifi = { ssid = "Site", pwd = "secret" },
              device = { model = "smartex mx", project_id = "4711", device_hash = "4711-01" },
              measurement = { interval_sec = 1800 },
            }
            """,
        )
        assertEquals(listOf("wifi.ssid", "wifi.pwd", "device.model", "device.project_id", "device.device_hash", "measurement.interval_sec"), r.paths)
    }

    @Test
    fun `partial config as bare lua table or json`() {
        assertEquals(listOf("wifi.ssid", "wifi.pwd"), valid("""{ wifi = { ssid = "Site", pwd = "p" } }""").paths)
        assertEquals(listOf("control.poll_sec"), valid("""{"control": {"poll_sec": 3600}}""").paths)
    }

    @Test
    fun `json values get the template's types`() {
        val r = valid("""{"device": {"project_id": 4711}, "measurement": {"interval_sec": "900"}}""")
        assertEquals(JsonPrimitive("4711"), LuaJson.get(r.config, "device.project_id"))
        assertEquals(JsonPrimitive(900), LuaJson.get(r.config, "measurement.interval_sec"))
    }

    @Test
    fun `other codes and broken configs are refused`() {
        invalid("")
        invalid("4711-01")
        invalid("https://dashboard.progeo.com/login/link/Xk3v9J2mQp7sLr0aBcDeFgHiJkLmNoPqRsTuVwXyZ01")
        invalid("""{"name": "not a device config"}""")
        invalid("return { wifi = { ssid = ")
        invalid("""{"wifi": {}}""")
        val tooOften = invalid("""{ measurement = { interval_sec = 10 } }""")
        assertTrue("interval_sec" in tooOften.reason)
        assertTrue("number" in invalid("""{"measurement": {"interval_sec": "soon"}}""").reason)
    }
}
