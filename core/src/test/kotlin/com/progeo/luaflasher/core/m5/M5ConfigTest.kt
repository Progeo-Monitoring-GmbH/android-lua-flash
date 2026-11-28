package com.progeo.luaflasher.core.m5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class M5ConfigTest {

    /** The backend's QR payload (detection-docker-setup render_m5_qr_payload), fake token. */
    private val qr = """
        # progeo:m5-config:1
        PLATFORM_PROJECT_ID = "940001"
        PLATFORM_DEVICE_HASH = "940001-01"
        PLATFORM_HOST = "api.progeo.com"
        PLATFORM_PATH = "/v1/device/940001/sample/"
        PLATFORM_TOKEN = "XXXXXXXXXXXXXXXX"
        ALARM_NUMBER = ""
        APN = "iot.1nce.net"
        PLATFORM_MODEL = "M5 AIN4"
        PLATFORM_DEVICE_VER = "2026.11"
        PLATFORM_PULLDOWN = 136
        PLATFORM_INTERVAL_SEC = 300
        PLATFORM_POLL_SEC = 300
        PLATFORM_THRESHOLD_MV = 891
        PLATFORM_SENSORS = 4
        PLATFORM_PE = 0
        PLATFORM_RS485_ENABLED = 1
        PLATFORM_RS485_HOST = PLATFORM_HOST
        PLATFORM_RS485_PATH = "/v1/device/lorawan/940002/"
        PLATFORM_RS485_HASH = PLATFORM_DEVICE_HASH + "-RS"
        PLATFORM_RS485_TOKEN = PLATFORM_TOKEN
        PLATFORM_RS485_INTERVAL_SEC = PLATFORM_INTERVAL_SEC
        SERIAL_CLI = True
        MODEM_DEBUG = False
    """.trimIndent() + "\n"

    @Test
    fun `parses the backend QR payload and resolves names`() {
        val c = M5Config.parseValid(qr)
        assertEquals("940001-01", c.deviceHash)
        assertEquals("940001", c.projectId)
        assertEquals("M5 AIN4", c.model)
        assertTrue(c.rs485Enabled)
        assertEquals(M5Value.Str("api.progeo.com"), c["PLATFORM_RS485_HOST"])
        assertEquals(M5Value.Str("940001-01-RS"), c["PLATFORM_RS485_HASH"])
        assertEquals(M5Value.Str("XXXXXXXXXXXXXXXX"), c["PLATFORM_RS485_TOKEN"])
        assertEquals(M5Value.Int(300), c["PLATFORM_RS485_INTERVAL_SEC"])
        assertEquals(M5Value.Bool(true), c["SERIAL_CLI"])
        assertEquals(c.values.keys.toList().first(), "PLATFORM_PROJECT_ID")
        assertEquals(23, c.values.size)
    }

    @Test
    fun `reads a full config_py with comments, padding and single quotes`() {
        val text = """
            # Geraete-Konfiguration -- vom Backend
            PLATFORM_PROJECT_ID = "0000"
            PLATFORM_DEVICE_HASH = '0000-01'                # Geraet
            PLATFORM_HOST = "api.progeo.com"
            PLATFORM_PATH = "/v1/device/0000/sample/"     # SMS-Pfad: "sample/" -> "sms/"
            PLATFORM_TOKEN = "abc\"d\\e"

            PLATFORM_MODEL = "smartex dm"
            PLATFORM_DEVICE_VER = "2026.11"
            PLATFORM_PULLDOWN = 136
            PLATFORM_INTERVAL_SEC = 60
            PLATFORM_POLL_SEC = 300
            PLATFORM_THRESHOLD_MV = None
            PLATFORM_SENSORS = 8
            PLATFORM_PE = 0
            WATCHDOG_SEC = 120
        """.trimIndent()
        val c = M5Config.parseValid(text)
        assertEquals("0000-01", c.deviceHash)
        assertEquals("abc\"d\\e", c.string("PLATFORM_TOKEN"))
        assertEquals(M5Value.None, c["PLATFORM_THRESHOLD_MV"])
        assertFalse(c.rs485Enabled)
    }

    @Test
    fun `render round-trips and starts with the marker`() {
        val c = M5Config.parse(qr).with("ALARM_NUMBER", M5Value.Str("+4917012345678")).with("APN", M5Value.Str("ü\"x"))
        val text = c.render()
        assertTrue(text.startsWith(M5Config.MARKER + "\n"))
        assertTrue(M5Config.looksLike(text))
        assertEquals(c, M5Config.parse(text))
        assertTrue("PLATFORM_RS485_TOKEN = \"XXXXXXXXXXXXXXXX\"" in text)
    }

    @Test
    fun `recognises M5 configs but not Lua, JSON or labels`() {
        assertTrue(M5Config.looksLike(qr))
        assertTrue(M5Config.looksLike("﻿\n  PLATFORM_DEVICE_HASH = \"1-01\"\n"))
        assertFalse(M5Config.looksLike("return {\n  device = { device_hash = \"x\" },\n}"))
        assertFalse(M5Config.looksLike("{\"wifi\":{\"ssid\":\"a\"}}"))
        assertFalse(M5Config.looksLike("WIFI:T:WPA;S:RUT_1234;P:secret;;"))
        assertFalse(M5Config.looksLike("https://example.com/device_hash=123"))
    }

    @Test
    fun `reports what the firmware would refuse`() {
        val c = M5Config.parse(qr)
        assertEquals(emptyList(), c.problems())
        // rendered, the RS485 token is a literal, so the line can go without breaking the reference
        val noToken = M5Config.parse(c.render().replace("\nPLATFORM_TOKEN = \"XXXXXXXXXXXXXXXX\"\n", "\n"))
        assertTrue(noToken.problems().any { "PLATFORM_TOKEN" in it })
        val bad = c.with("PLATFORM_PULLDOWN", M5Value.Int(99)).with("PLATFORM_PATH", M5Value.Str("/v1/x/"))
            .with("ALARM_NUMBER", M5Value.Str("017012345"))
        val p = bad.problems().joinToString()
        assertTrue("PULLDOWN" in p && "sample/" in p && "ALARM_NUMBER" in p, p)
        val noRs485 = M5Config.parse(qr.lines().filterNot { it.startsWith("PLATFORM_RS485_PATH") }.joinToString("\n"))
        assertTrue(noRs485.problems().any { "PLATFORM_RS485_PATH" in it })
        assertFailsWith<M5ConfigException> { M5Config.parseValid("PLATFORM_DEVICE_HASH = \"1-01\"") }
    }

    @Test
    fun `refuses Python it does not understand`() {
        for (bad in listOf(
            "X = foo()",
            "X = UNKNOWN",
            "X = \"open",
            "X = 1.5",
            "X = \"a\" + 1",
            "import os",
            "X = [1, 2]",
        )) {
            assertFailsWith<M5ConfigException>(bad) { M5Config.parse(bad) }
        }
        assertFailsWith<M5ConfigException> { M5Config.parse("# only a comment\n") }
    }
}
