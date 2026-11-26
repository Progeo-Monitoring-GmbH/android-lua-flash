package com.progeo.teltonika

import com.progeo.teltonika.api.RouterApi
import com.progeo.teltonika.api.RouterApiException
import com.progeo.teltonika.api.RouterException
import com.progeo.teltonika.api.RouterLockedOutException
import com.progeo.teltonika.api.RouterLoginException
import com.progeo.teltonika.api.RouterUnreachableException
import com.progeo.teltonika.api.RutosClient
import com.progeo.teltonika.api.SignalQuality
import com.progeo.teltonika.label.Credentials
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RutosClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fake = FakeRutos()
    private val server = MockWebServer()
    private var now = 1_000_000L
    private lateinit var client: RutosClient
    private lateinit var api: RouterApi

    @Before
    fun setUp() {
        server.dispatcher = fake
        server.start()
        client = RutosClient(server.url("/").toString(), OkHttpClient(), clock = { now })
        api = RouterApi(client)
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun login() = client.login(Credentials("admin", "Factory1"))

    @Test
    fun `login and device info merged over all endpoints`() = runTest {
        login()
        val device = api.device()
        assertEquals("1234567890", device.serial)
        assertEquals("00:1E:42:AA:BB:CC", device.mac)
        assertEquals("RUT241", device.modelName)
        assertEquals("RUT241010000", device.productCode)
        assertEquals("RUT2M", device.firmwareVersion!!.family)
        assertEquals("07.06.1", device.firmwareVersion!!.short)
    }

    @Test
    fun `wrong password and lockout`() = runTest {
        assertFailsWith<RouterLoginException> { client.login(Credentials("admin", "nope")) }
        fake.lockedOut = true
        assertFailsWith<RouterLockedOutException> { login() }
    }

    @Test
    fun `logs in again before the token expires`() = runTest {
        login()
        api.modems()
        assertEquals(1, fake.logins)
        now += 290_000 // 9 s left: less than the margin
        api.modems()
        assertEquals(2, fake.logins)
    }

    @Test
    fun `logs in again once after a 401`() = runTest {
        login()
        fake.tokens.clear() // e.g. the router rebooted
        val modems = api.modems()
        assertEquals(1, modems.size)
        assertEquals(2, fake.logins)
    }

    @Test
    fun `gives up when the re-login is rejected`() = runTest {
        login()
        fake.tokens.clear()
        fake.password = "changedElsewhere1"
        assertFailsWith<RouterLoginException> { api.modems() }
    }

    @Test
    fun `modem values are read leniently`() = runTest {
        login()
        val m = api.modems().single()
        assertEquals("Telekom.de", m.operator)
        assertEquals(-92, m.signal.rsrp)
        assertEquals(-11.0, m.signal.rsrq)
        assertEquals(14.0, m.signal.sinr)
        assertEquals(-67, m.signal.rssi)
        assertEquals(SignalQuality.FAIR, m.signal.quality) // RSRP -92 is fair, SINR 14 good: the worse wins
        assertEquals("1", m.activeSim)
    }

    @Test
    fun `api errors carry the RutOS codes`() = runTest {
        login()
        val e = assertFailsWith<RouterApiException> { client.get("/api/nothing/here") }
        assertTrue(e.notImplemented)
        assertEquals(listOf(122), e.codes)
    }

    @Test
    fun `password change keeps the session usable`() = runTest {
        login()
        api.changePassword("admin", "NewPass123")
        assertEquals("NewPass123", fake.password)
        fake.tokens.clear()
        api.modems() // re-login with the new password
        assertEquals(2, fake.logins)
    }

    @Test
    fun `firmware upload with progress, then upgrade`() = runTest {
        login()
        val image = tmp.newFile("fw.bin").apply { writeBytes(ByteArray(300_000) { it.toByte() }) }
        var last = 0L
        val uploaded = api.uploadFirmware(image) { sent, total -> last = sent; assertEquals(300_000, total) }
        assertEquals(300_000, last)
        assertTrue(fake.uploadedBytes > 300_000) // multipart framing on top
        assertEquals("RUT2M_R_00.07.24.8", uploaded.version)
        assertEquals(null, api.verifyFirmware().problem)
        api.upgradeFirmware(keepSettings = true)
        assertEquals("1", fake.upgradeKeepSettings)
        assertTrue("POST /api/firmware/actions/verify" in fake.requests)
    }

    @Test
    fun `router rejects an image for other hardware`() = runTest {
        login()
        fake.firmwareCheck = FakeRutos.section("valid" to "1", "hw_support" to "0", "authorized" to "1")
        api.uploadFirmware(tmp.newFile("fw.bin").apply { writeBytes(ByteArray(10)) }) { _, _ -> }
        assertEquals("The router says this firmware does not support its hardware.", api.verifyFirmware().problem)
        api.deleteUploadedFirmware()
        assertEquals(0, fake.uploadedBytes)
    }

    @Test
    fun `upload starts with a fresh token`() = runTest {
        login()
        now += 120_000 // 179 s left: fine for a call, too little for an upload
        api.uploadFirmware(tmp.newFile("fw.bin").apply { writeBytes(ByteArray(10)) }) { _, _ -> }
        assertEquals(2, fake.logins)
    }

    @Test
    fun `backup download`() = runTest {
        login()
        assertContentEquals(FakeRutos.BACKUP, api.backup())
    }

    @Test
    fun `new Wi-Fi name and password on the access point only`() = runTest {
        login()
        val changed = api.setWifi("ProGeo-Site7", "Secret-1234")
        assertEquals(listOf("default_radio0", "guest"), changed.map { it.id })
        val ap = fake.wifi.getValue("default_radio0")
        assertEquals("ProGeo-Site7", ap["ssid"]!!.jsonPrimitive.content)
        assertEquals("Secret-1234", ap["key"]!!.jsonPrimitive.content)
        assertEquals("sae-mixed", ap["encryption"]!!.jsonPrimitive.content) // kept
        assertEquals("wpa2", fake.wifi.getValue("guest")["encryption"]!!.jsonPrimitive.content) // open → WPA2
        assertEquals("Office", fake.wifi.getValue("wifi_client")["ssid"]!!.jsonPrimitive.content)
        assertTrue(api.wifiInterfaces().filter { it.isAccessPoint }.all { it.ssid == "ProGeo-Site7" })
    }

    @Test
    fun `bad Wi-Fi values are refused before the router is touched`() = runTest {
        login()
        assertFailsWith<RouterException> { api.setWifi("Site", "short") }
        assertFailsWith<RouterException> { api.setWifi(" ", "Secret-1234") }
        assertFailsWith<RouterException> { api.setWifi("Site", "Schlüssel-123") }
        assertTrue(fake.requests.none { it.startsWith("PUT /api/wireless") })
    }

    @Test
    fun `unreachable router`() = runTest {
        server.shutdown()
        assertFailsWith<RouterUnreachableException> { login() }
    }
}
