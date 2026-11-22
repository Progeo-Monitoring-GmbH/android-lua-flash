package com.progeo.luaflasher.core.config

import com.progeo.luaflasher.core.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigChoicesTest {

    private val template = ConfigTemplate.parse(Fixtures.text("device_config.lua.template"))

    @Test
    fun `values are found however the number is written`() {
        assertEquals("30min", ConfigChoices.find("measurement.interval_sec", "1800")?.label)
        assertEquals("30min", ConfigChoices.find("measurement.interval_sec", "1800.0")?.label)
        assertEquals("Never", ConfigChoices.find("control.poll_sec", "0")?.label)
        assertEquals("100k (default)", ConfigChoices.find("measurement.pulldown_resistance", " 136 ")?.label)
        assertNull(ConfigChoices.find("measurement.interval_sec", "1200"))
        assertNull(ConfigChoices.find("measurement.interval_sec", null))
        assertNull(ConfigChoices.find("wifi.ssid", "1800"))
    }

    @Test
    fun `the template's defaults are supported values`() {
        for (path in ConfigChoices.paths) {
            val default = template.field(path)?.default
            assertTrue(ConfigChoices.find(path, default) != null, "$path default $default is not a choice")
        }
    }

    @Test
    fun `other values are flagged but do not block flashing`() {
        val config = DeviceConfigBuilder.build(
            template,
            LuaJson.parse("return { device = { device_hash = \"1-01\" }, measurement = { interval_sec = 1200, pulldown_resistance = 136 }, control = { poll_sec = 0 } }"),
        )
        val issues = ConfigValidator.validate(template, config).filter { it.path in ConfigChoices.paths }
        assertEquals(listOf("measurement.interval_sec"), issues.map { it.path })
        assertEquals(Severity.WARNING, issues.single().severity)
    }
}
