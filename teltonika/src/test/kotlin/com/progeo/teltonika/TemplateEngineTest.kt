package com.progeo.teltonika

import com.progeo.teltonika.api.RutosClient
import com.progeo.teltonika.config.RouterTemplate
import com.progeo.teltonika.config.SectionResult
import com.progeo.teltonika.config.TemplateEngine
import com.progeo.teltonika.config.TemplateException
import com.progeo.teltonika.label.Credentials
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TemplateEngineTest {
    private val fake = FakeRutos()
    private val server = MockWebServer()
    private lateinit var client: RutosClient

    private val template = RouterTemplate.json.decodeFromString<RouterTemplate>(
        """
        {"format":1,"id":"eu-default","version":3,"title":"EU default","region":"eu","models":["RUT986","RUT901"],
         "sections":[
          {"title":"Wi-Fi","endpoint":"/api/wireless/interfaces/config/default_radio0","affects_connection":true,
           "values":{"ssid":"PG-{mac_last4}","key":"{wifi_key}","encryption":"psk2"}},
          {"title":"Mobile","endpoint":"/api/network/mobile/config/mob1s1a1","values":{"apn":"internet.m2m","auth_mode":"none"}},
          {"title":"NTP","endpoint":"/api/date_time/ntp/client/config/general","values":{"enabled":"1","zone":"Europe/Berlin"}}
         ],
         "access":{"ssid":"PG-{mac_last4}","wifi_key":"{wifi_key}"}}
        """.trimIndent(),
    )

    private val variables = TemplateEngine.variables("1234567890", "00:1E:42:AA:BB:CC", "RUT986", "wifiKey123")

    @Before
    fun setUp() {
        server.dispatcher = fake
        server.start()
        client = RutosClient(server.url("/").toString(), OkHttpClient())
        fake.config["/api/wireless/interfaces/config/default_radio0"] = FakeRutos.section("ssid" to "RUT986_BBCC", "key" to "factory9", "encryption" to "psk2")
        fake.config["/api/network/mobile/config/mob1s1a1"] = FakeRutos.section("apn" to "", "auth_mode" to "none")
        fake.config["/api/date_time/ntp/client/config/general"] = FakeRutos.section("enabled" to "true", "zone" to "Europe/Berlin")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `placeholders and overrides`() {
        val resolved = TemplateEngine.resolve(
            template,
            variables,
            overrides = mapOf("/api/network/mobile/config/mob1s1a1" to mapOf("apn" to JsonPrimitive("site.apn"))),
        )
        assertEquals("PG-BBCC", resolved.sections[0].values["ssid"]!!.jsonPrimitive.content)
        assertEquals("wifiKey123", resolved.sections[0].values["key"]!!.jsonPrimitive.content)
        assertEquals("site.apn", resolved.sections[1].values["apn"]!!.jsonPrimitive.content)
        assertEquals("PG-BBCC", resolved.access!!.ssid)
    }

    @Test
    fun `missing placeholder value is an error before anything is written`() {
        val noKey = TemplateEngine.variables("1234567890", "00:1E:42:AA:BB:CC", "RUT986", wifiKey = null)
        assertFailsWith<TemplateException> { TemplateEngine.resolve(template, noKey) }
    }

    @Test
    fun `plan lists only differences, apply writes connection last`() = runTest {
        client.login(Credentials("admin", "Factory1"))
        val plan = TemplateEngine.plan(client, TemplateEngine.resolve(template, variables))
        // NTP: "true" equals "1"; zone equal → no change.
        assertEquals(listOf(2, 1, 0), plan.sections.map { it.changes.size })
        assertTrue(plan.affectsConnection)
        assertTrue(plan.sections[0].changes.first { it.key == "key" }.secret)

        val order = mutableListOf<String>()
        val result = TemplateEngine.apply(client, plan) { _, _, title -> order += title }
        assertEquals(listOf("Mobile", "Wi-Fi"), order)
        assertTrue(result.ok)
        assertTrue(result.needsReconnect)
        assertIs<SectionResult.Applied>(result.results[0])
        assertIs<SectionResult.AppliedUnverified>(result.results[1])
        assertEquals("internet.m2m", fake.config["/api/network/mobile/config/mob1s1a1"]!!["apn"]!!.jsonPrimitive.content)
        // Only the changed keys were sent.
        assertEquals("PG-BBCC", fake.config["/api/wireless/interfaces/config/default_radio0"]!!["ssid"]!!.jsonPrimitive.content)

        val again = TemplateEngine.plan(client, TemplateEngine.resolve(template, variables))
        assertTrue(again.inSync)
    }

    @Test
    fun `missing endpoint is reported per section`() = runTest {
        client.login(Credentials("admin", "Factory1"))
        fake.config.remove("/api/network/mobile/config/mob1s1a1")
        val plan = TemplateEngine.plan(client, TemplateEngine.resolve(template, variables))
        assertEquals(1, plan.errors.size)
        assertFalse(plan.inSync)
        val result = TemplateEngine.apply(client, plan)
        assertEquals(1, result.results.size) // only Wi-Fi; the broken section is skipped
    }

    @Test
    fun `value comparison`() {
        assertTrue(TemplateEngine.same(JsonPrimitive("1"), JsonPrimitive(true)))
        assertTrue(TemplateEngine.same(JsonPrimitive("10"), JsonPrimitive(10)))
        assertTrue(TemplateEngine.same(null, JsonPrimitive("")))
        assertTrue(TemplateEngine.same(JsonArray(listOf(JsonPrimitive("a"))), JsonPrimitive("a")))
        assertFalse(TemplateEngine.same(JsonPrimitive("a"), JsonPrimitive("b")))
        assertFalse(TemplateEngine.same(null, JsonPrimitive("x")))
    }
}
