package com.progeo.luaflasher.core.config

import com.progeo.luaflasher.core.Fixtures
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

    @Test
    fun `generated config never contains placeholders or the firmware table`() {
        val values = mapOf(
            "device.project_id" to "P-17",
            "device.version" to "2026.11",
            "device.device_hash" to "abc123",
            "control.token" to "t\"o\\k\nen",
            "wifi.ssid" to "",
        )
        val lua = LuaConfigWriter.write(template, values)
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
    fun `optional fields are written only when set`() {
        val lua = LuaConfigWriter.write(
            template,
            mapOf("device.device_hash" to "h", "portal.timeout_sec" to "900", "portal.pwd" to "secret12", "wifi.portal_after_sec" to "0"),
        )
        val parsed = LuaTableParser.parse(lua)
        assertEquals(LuaValue.Num("900"), (parsed["portal"]!!.value as LuaValue.Table)["timeout_sec"]!!.value)
        assertEquals(LuaValue.Str("secret12"), (parsed["portal"]!!.value as LuaValue.Table)["pwd"]!!.value)
        assertEquals(LuaValue.Num("0"), (parsed["wifi"]!!.value as LuaValue.Table)["portal_after_sec"]!!.value)
        assertNull((parsed["portal"]!!.value as LuaValue.Table)["ssid"])
    }

    @Test
    fun `numbers are checked`() {
        assertFailsWith<IllegalArgumentException> {
            LuaConfigWriter.write(template, mapOf("measurement.interval_sec" to "1800; os.exit()"))
        }
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
        val values = mapOf("device.device_hash" to "h", "control.token" to "a\"b\\c\nd\u0001" + "9\u007Fé")
        val file = File.createTempFile("device_config", ".lua")
        try {
            file.writeText(LuaConfigWriter.write(template, values), Charsets.UTF_8)
            val script = "local t=dofile(arg[1]) io.write((t.control.token:gsub('.', function(c) return string.format('%02x', c:byte()) end)))"
            val process = ProcessBuilder(lua, "-e", script.replace("arg[1]", "'" + file.absolutePath.replace("\\", "/") + "'"))
                .redirectErrorStream(true).start()
            val out = process.inputStream.readBytes().decodeToString()
            process.waitFor(10, TimeUnit.SECONDS)
            val expected = values.getValue("control.token").toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
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
        for (bad in listOf("os.exit()", "return { a = f() }", "return { a = 1 } x = 2", "return { [1] = 2 }", "return { a = \"open }")) {
            assertFailsWith<LuaParseException>(bad) { LuaTableParser.parse(bad) }
        }
    }
}
