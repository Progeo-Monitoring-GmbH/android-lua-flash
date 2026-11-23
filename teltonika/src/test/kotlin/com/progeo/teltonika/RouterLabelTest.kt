package com.progeo.teltonika

import com.progeo.teltonika.label.Credentials
import com.progeo.teltonika.label.RouterLabel
import com.progeo.teltonika.label.RouterLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterLabelTest {

    @Test
    fun `device label from the Teltonika wiki`() {
        val label = RouterLabels.parse(
            "WIFI:T:WPA;S:RUT241_AABB;P:wifiKey9;SN:1234567890;I:860000000000001;M:001E42AABBCC;U:admin;PW:Factory1;B:007;",
        )!!
        assertEquals(RouterLabel.Source.DEVICE_LABEL, label.source)
        assertEquals("1234567890", label.serial)
        assertEquals("860000000000001", label.imei)
        assertEquals("00:1E:42:AA:BB:CC", label.mac)
        assertEquals("007", label.batch)
        assertEquals("RUT241_AABB", label.wifi!!.ssid)
        assertEquals("wifiKey9", label.wifi!!.passphrase)
        assertEquals("WPA", label.wifi!!.security)
        assertEquals(Credentials("admin", "Factory1"), label.login)
        assertTrue(label.hasAccess)
    }

    @Test
    fun `box label carries identity only`() {
        val label = RouterLabels.parse("SN:1234567890;I:860000000000001;M:AABBCCDDEEFF;B:999;")!!
        assertEquals(RouterLabel.Source.BOX_LABEL, label.source)
        assertEquals("AA:BB:CC:DD:EE:FF", label.mac)
        assertNull(label.wifi)
        assertNull(label.login)
        assertFalse(label.hasAccess)
    }

    @Test
    fun `serial barcode`() {
        val label = RouterLabels.parse(" 1234567890 ")!!
        assertEquals(RouterLabel.Source.SERIAL, label.source)
        assertEquals("1234567890", label.serial)
    }

    @Test
    fun `escaped characters in SSID and keys`() {
        val label = RouterLabels.parse("""WIFI:T:WPA;S:My\;Net\:1;P:a\\b\;c;SN:1234567890;U:admin;PW:x\;y;;""")!!
        assertEquals("My;Net:1", label.wifi!!.ssid)
        assertEquals("a\\b;c", label.wifi!!.passphrase)
        assertEquals("x;y", label.login!!.password)
    }

    @Test
    fun `missing user defaults to admin, case-insensitive prefix`() {
        val label = RouterLabels.parse("wifi:S:net;P:12345678;SN:1234567890;PW:secret12;")!!
        assertEquals("admin", label.login!!.username)
    }

    @Test
    fun `not a router label`() {
        assertNull(RouterLabels.parse("WIFI:T:WPA;S:home;P:12345678;;"))
        assertNull(RouterLabels.parse("SN:12345;M:AABBCCDDEEFF;"))
        assertNull(RouterLabels.parse("https://example.com"))
        assertNull(RouterLabels.parse("123456789"))
    }

    @Test
    fun `credentials never show the password`() {
        assertFalse(Credentials("admin", "Secret123").toString().contains("Secret123"))
    }
}
