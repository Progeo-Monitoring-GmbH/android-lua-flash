package com.progeo.luaflasher.router

import com.progeo.core.auth.BackendClient
import com.progeo.core.auth.InMemoryTokenStore
import com.progeo.core.auth.Tokens
import com.progeo.teltonika.api.InterfaceStatus
import com.progeo.teltonika.api.ModemStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterBackendTest {
    private val server = MockWebServer()
    private val posted = Collections.synchronizedMap(LinkedHashMap<String, JsonObject>())
    private lateinit var backend: RouterBackend

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (request.method == "POST") {
                    posted[path] = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    return json("{}")
                }
                return when (path) {
                    "/api/routers/1234567890/" -> json(
                        """{"serial":"1234567890","model":"RUT241","product_code":"RUT241010000","cert_fingerprint":"AB:CD",
                            "access":{"ssid":"PG-BBCC","lan_ip":"192.168.10.1"},"future_field":1,
                            "credentials":{"username":"admin","password":"Own12345","factory_password":"Factory1","wifi_key":"wifiKey123"}}""",
                    )
                    "/api/routers/templates/" -> json(
                        """{"templates":[{"id":"us","version":1,"title":"US","models":["RUT241"],"sections":[]},{"broken":true}]}""",
                    )
                    else -> MockResponse().setResponseCode(404).setBody("""{"detail":"Not found."}""")
                }
            }
        }
        server.start()
        val client = BackendClient({ server.url("/").toString() }, InMemoryTokenStore(Tokens("x.e30.sig", "refresh")), OkHttpClient())
        backend = RouterBackend(client)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `unknown router is null`() = runTest {
        assertNull(backend.get("9999999999"))
    }

    @Test
    fun `record with credentials`() = runTest {
        val r = backend.get("1234567890")!!
        assertEquals("RUT241", r.model)
        assertEquals("RUT241010000", r.productCode)
        assertEquals("Own12345", r.password)
        assertEquals("Factory1", r.factoryPassword)
        assertEquals(RouterAccess("PG-BBCC", "wifiKey123", "192.168.10.1"), r.access)
        assertFalse(r.onFactoryPassword)
    }

    @Test
    fun `record upload carries no secrets, credentials go separately`() = runTest {
        val r = RouterRecord(
            serial = "1234567890", password = "Own12345", pendingPassword = "Next1234", factoryPassword = "Factory1",
            access = RouterAccess("PG-BBCC", "wifiKey123", null), templateWifiKey = "tmplKey99",
        )
        backend.saveRecord(r)
        val record = posted["/api/routers/1234567890/"]!!
        val text = record.toString()
        listOf("Own12345", "Next1234", "Factory1", "wifiKey123", "tmplKey99").forEach { assertFalse(text.contains(it), "record leaks $it") }
        assertEquals(JsonPrimitive("PG-BBCC"), record["access"]!!.jsonObject["ssid"])

        backend.saveCredentials(r)
        val creds = posted["/api/routers/1234567890/credentials/"]!!
        assertEquals("Own12345", (creds["password"] as JsonPrimitive).content)
        assertEquals("Next1234", (creds["pending_password"] as JsonPrimitive).content)
        assertEquals("wifiKey123", (creds["wifi_key"] as JsonPrimitive).content)
    }

    @Test
    fun `templates skip entries that do not parse`() = runTest {
        val list = backend.templates("RUT241")
        assertEquals(listOf("us"), list.map { it.id })
    }

    @Test
    fun `outbox ops round-trip through the store format`() {
        val ops: List<OutboxOp> = listOf(
            OutboxOp.SaveRecord("1"),
            OutboxOp.SaveCredentials("1"),
            OutboxOp.Backup("1", "2026-11-23T10:00:00+01:00", "/x/backup.tar.gz"),
            OutboxOp.Report("1", buildJsonObject { put("note", "ok") }),
        )
        val text = RouterStore.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(OutboxOp.serializer()), ops)
        assertTrue(text.contains("\"op\":\"credentials\""))
        assertEquals(ops, RouterStore.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(OutboxOp.serializer()), text))
    }

    @Test
    fun `mobile check`() {
        val modem = ModemStatus.from(
            buildJsonObject {
                put("simstate", "Inserted")
                put("pinstate", "OK")
                put("netstate", "Registered (home)")
                put("operator", "AT&T")
                put("rsrp", "-85")
                put("sinr", "15")
            },
        )
        val mob = InterfaceStatus("mob1s1a1", true, "wwan", "qmimux0", "10.1.2.3", null, null, null)
        val checks = MobileChecks.of(listOf(modem), listOf(mob))
        assertTrue(checks.all { it.ok == true }, checks.toString())

        val noSim = ModemStatus.from(buildJsonObject { put("simstate", "Not inserted"); put("rsrp", "-118") })
        val bad = MobileChecks.of(listOf(noSim), listOf(mob.copy(up = false, ipv4 = null)))
        assertEquals(false, bad.first { it.title == "SIM card" }.ok)
        assertEquals(false, bad.first { it.title == "Signal" }.ok)
        assertEquals(false, bad.first { it.title == "Mobile data" }.ok)
    }
}
