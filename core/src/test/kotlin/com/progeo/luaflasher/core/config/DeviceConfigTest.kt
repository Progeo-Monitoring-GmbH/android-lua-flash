package com.progeo.luaflasher.core.config

import com.progeo.luaflasher.core.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceConfigTest {

    private val template = ConfigTemplate.parse(Fixtures.text("device_config.lua.template"))

    @Test
    fun `template fields from the real bundle template`() {
        val byPath = template.fields.associateBy { it.path }
        assertFalse(byPath.keys.any { it.startsWith("firmware.") }, "firmware table must be skipped")

        val ssid = byPath.getValue("wifi.ssid")
        assertNull(ssid.default)
        assertEquals("<wifi-ssid>", ssid.hint)

        val portalAfter = byPath.getValue("wifi.portal_after_sec")
        assertTrue(portalAfter.optional)
        assertEquals(FieldKind.NUMBER, portalAfter.kind)
        assertEquals("180", portalAfter.hint)
        assertEquals("open the setup portal without an IP this long after boot; 0 disables", portalAfter.description)

        assertEquals("smartex mx", byPath.getValue("device.model").default)
        assertEquals("1800", byPath.getValue("measurement.interval_sec").default)
        assertEquals(FieldKind.NUMBER, byPath.getValue("measurement.interval_sec").kind)
        assertEquals("https://api.progeo.com/v1/device/{project_id}/sample/", byPath.getValue("control.url").default)
        assertEquals("{project_id}: device.project_id", byPath.getValue("control.url").description)
        assertNull(byPath.getValue("control.token").default)

        val portalSsid = byPath.getValue("portal.ssid")
        assertTrue(portalSsid.optional)
        assertEquals("Setup-<device-hash>", portalSsid.hint)
        assertEquals("name of the setup network", portalSsid.description)
        assertEquals("600", byPath.getValue("portal.timeout_sec").hint)

        assertEquals(
            listOf(
                "wifi.ssid", "wifi.pwd", "wifi.portal_after_sec",
                "device.model", "device.version", "device.project_id", "device.device_hash",
                "measurement.pulldown_resistance", "measurement.interval_sec", "measurement.sensors", "measurement.protective_earth",
                "control.url", "control.token", "control.poll_sec",
                "portal.ssid", "portal.pwd", "portal.timeout_sec",
            ),
            template.fields.map { it.path },
        )
    }

    private fun profile(vararg values: Pair<String, Any>): JsonObject {
        var config = JsonObject(emptyMap())
        for ((path, v) in values) {
            val json = when (v) {
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> error("unsupported")
            }
            config = LuaJson.with(config, path, json)
        }
        return config
    }

    @Test
    fun `generated config never contains placeholders or the firmware table and keeps template order`() {
        val profile = profile(
            "control.token" to "t\"o\\k\nen",
            "device.device_hash" to "abc123",
            "device.project_id" to "P-17",
            "device.version" to "2026.11",
        )
        val lua = DeviceConfigBuilder.lua(template, profile)
        assertEquals(
            """
            return {
              wifi = {},
              device = {
                model = "smartex mx",
                version = "2026.11",
                project_id = "P-17",
                device_hash = "abc123",
              },
              measurement = {
                pulldown_resistance = 136,
                interval_sec = 1800,
                sensors = 16,
                protective_earth = 0,
              },
              control = {
                url = "https://api.progeo.com/v1/device/{project_id}/sample/",
                token = "t\"o\\k\nen",
                poll_sec = 14400,
              },
              portal = {},
            }

            """.trimIndent(),
            lua,
        )
        assertFalse('<' in lua)
    }

    @Test
    fun `optional fields are written only when set and the device hash overrides the profile`() {
        val profile = profile(
            "device.device_hash" to "old",
            "portal.timeout_sec" to 900,
            "portal.pwd" to "secret12",
            "wifi.portal_after_sec" to 0,
        )
        val parsed = LuaJson.parse(DeviceConfigBuilder.lua(template, profile, deviceHash = "new"))
        assertEquals("900", LuaJson.get(parsed, "portal.timeout_sec").toString())
        assertEquals("\"secret12\"", LuaJson.get(parsed, "portal.pwd").toString())
        assertEquals("0", LuaJson.get(parsed, "wifi.portal_after_sec").toString())
        assertEquals("\"new\"", LuaJson.get(parsed, "device.device_hash").toString())
        assertNull(LuaJson.get(parsed, "portal.ssid"))
    }

    @Test
    fun `a controller's config keeps keys the template does not know`() {
        val fetched = LuaJson.parse(
            """
            return {
              wifi = { ssid = "Site", pwd = "p", portal_after_sec = 120 },
              device = { model = "smartex mx", device_hash = "4711-01", extra = true },
              sensors_map = { 3, 5, 8 },
              ["odd key"] = 1.5,
              firmware = { version = "v1.1.0" },
            }
            """.trimIndent(),
        )
        val config = DeviceConfigBuilder.build(template, fetched)
        assertEquals("true", LuaJson.get(config, "device.extra").toString())
        assertEquals("[3,5,8]", LuaJson.get(config, "sensors_map").toString())
        assertEquals("1.5", LuaJson.get(config, "odd key").toString())
        assertEquals("120", LuaJson.get(config, "wifi.portal_after_sec").toString())
        // template defaults fill what the controller did not have
        assertEquals("1800", LuaJson.get(config, "measurement.interval_sec").toString())
        // the profile's own firmware table stays; the template's never comes in
        assertEquals("\"v1.1.0\"", LuaJson.get(config, "firmware.version").toString())
        // and it survives a round trip through Lua unchanged
        assertEquals(config, LuaJson.parse(LuaJson.write(config)))
        assertTrue("[\"odd key\"] = 1.5" in LuaJson.write(config))
    }

    @Test
    fun `json conversion of numbers booleans lists and nested tables`() {
        val json = LuaJson.parse("return { a = 0x10, b = -3, c = 2.5, d = false, e = { { x = 1 }, { x = 2 } }, f = nil }")
        assertEquals("""{"a":16,"b":-3,"c":2.5,"d":false,"e":[{"x":1},{"x":2}]}""", json.toString())
        assertEquals(mapOf("a" to "16", "b" to "-3", "c" to "2.5", "d" to "false", "e.1.x" to "1", "e.2.x" to "2"), LuaJson.flatten(json))
        val changed = LuaJson.with(json, "e.2.x", JsonPrimitive(7))
        assertEquals("7", LuaJson.get(changed, "e.2.x").toString())
        assertNull(LuaJson.get(LuaJson.with(changed, "d", null), "d"))
        assertEquals(JsonPrimitive(60L), LuaJson.typed("60", FieldKind.NUMBER))
        assertNull(LuaJson.typed("1800; os.exit()", FieldKind.NUMBER))
        assertNull(LuaJson.typed("yes", FieldKind.BOOLEAN))
    }

    @Test
    fun `schema 2 template adds the optional rpi flag`() {
        val v2 = ConfigTemplate.parse(Fixtures.text("device_config.v2.lua.template"))
        val rpi = v2.field("control.rpi")!!
        assertTrue(rpi.optional)
        assertEquals(FieldKind.BOOLEAN, rpi.kind)
        assertNull(rpi.default)
        assertEquals(template.fields.map { it.path } + "control.rpi", v2.fields.map { it.path }.let { p -> p.filter { it != "control.rpi" } + "control.rpi" })
        // unset stays out of the file; set is written as a boolean in template order
        assertNull(LuaJson.get(DeviceConfigBuilder.build(v2, JsonObject(emptyMap()), "h"), "control.rpi"))
        val lua = DeviceConfigBuilder.lua(v2, profile("control.rpi" to false), "h")
        assertTrue("    poll_sec = 14400,\n    rpi = false,\n" in lua, lua)
    }

    @Test
    fun `template defaults`() {
        val defaults = template.defaults()
        assertEquals(listOf("wifi", "device", "measurement", "control", "portal"), defaults.keys.toList())
        assertNull(LuaJson.get(defaults, "wifi.ssid"))
        assertEquals("\"smartex mx\"", LuaJson.get(defaults, "device.model").toString())
    }

    @Test
    fun `escaping round trip through the parser`() {
        val nasty = listOf(
            "plain",
            "quote \" backslash \\ newline \n tab \t cr \r",
            "\u0001" + "2 control followed by a digit",
            "del \u007F nul \u0000 end",
            "unicode äöü € 😀",
            "]] [[ -- not a comment",
        )
        for (s in nasty) {
            val literal = LuaString.quote(s)
            assertFalse('\n' in literal, literal)
            val parsed = LuaTableParser.parse("return { v = $literal }")
            assertEquals(LuaValue.Str(s), parsed["v"]!!.value, literal)
        }
    }

    /** Runs only with -PluaInterpreter=<path to lua5.3>. */
    @Test
    fun `escaping round trip through real Lua`() {
        val lua = System.getProperty("luaInterpreter") ?: return
        val token = "a\"b\\c\nd\u0001" + "9\u007Fé"
        val file = File.createTempFile("device_config", ".lua")
        try {
            file.writeText(DeviceConfigBuilder.lua(template, profile("control.token" to token), "h"), Charsets.UTF_8)
            val script = "local t=dofile(arg[1]) io.write((t.control.token:gsub('.', function(c) return string.format('%02x', c:byte()) end)))"
            val process = ProcessBuilder(lua, "-e", script.replace("arg[1]", "'" + file.absolutePath.replace("\\", "/") + "'"))
                .redirectErrorStream(true).start()
            val out = process.inputStream.readBytes().decodeToString()
            process.waitFor(10, TimeUnit.SECONDS)
            val expected = token.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
            assertEquals(expected, out.trim())
        } finally {
            file.delete()
        }
    }
    @Test
    fun `validation rules`() {
        fun issues(vararg v: Pair<String, String>) = ConfigValidator.validate(template, mapOf(*v)).map { it.path to it.severity }

        assertEquals(listOf("device.device_hash" to Severity.ERROR), issues())
        assertEquals(emptyList(), issues("device.device_hash" to "x"))
        assertTrue(("measurement.interval_sec" to Severity.ERROR) in issues("device.device_hash" to "x", "measurement.interval_sec" to "59"))
        assertTrue(("portal.pwd" to Severity.ERROR) in issues("device.device_hash" to "x", "portal.pwd" to "short"))
        assertTrue(issues("device.device_hash" to "x", "portal.pwd" to "longenough").isEmpty())
        assertTrue(
            ("control.url" to Severity.WARNING) in issues("device.device_hash" to "x", "control.token" to "t", "control.url" to "http://plain"),
        )
        assertTrue(("measurement.sensors" to Severity.ERROR) in issues("device.device_hash" to "x", "measurement.sensors" to "many"))
    }

    @Test
    fun `parser rejects code that is not a plain table`() {
        for (bad in listOf("os.exit()", "return { a = f() }", "return { a = 1 } x = 2", "return { a = 1 + 2 }", "return { a = \"open }", "return { [x] = 1 }")) {
            assertFailsWith<LuaParseException>(bad) { LuaTableParser.parse(bad) }
        }
    }
}
