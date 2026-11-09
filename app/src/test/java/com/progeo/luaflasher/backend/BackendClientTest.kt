package com.progeo.luaflasher.backend

import com.progeo.luaflasher.core.config.FieldKind
import com.progeo.luaflasher.core.config.LuaJson
import com.progeo.luaflasher.data.ProfileStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendClientTest {
    private val server = MockWebServer()
    private val requests = Collections.synchronizedList(ArrayList<RecordedRequest>())
    private val bodies = Collections.synchronizedMap(HashMap<RecordedRequest, String>())
    private val refreshes = AtomicInteger()
    private var generation = AtomicInteger(1)

    /** Valid refresh tokens; rotation blacklists the old one. */
    private val validRefresh = Collections.synchronizedSet(HashSet<String>())
    private var refreshFails = false
    private var otpEnabled = false

    private fun jwt(n: Int) = "x." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"username":"jdoe","exp":9999999999,"is_demo":false,"gen":$n}""".toByteArray()) + ".sig"

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun tokensBody(n: Int): String {
        validRefresh += "refresh-$n"
        return """{"access":"${jwt(n)}","refresh":"refresh-$n"}"""
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val body = request.body.readUtf8()
                bodies[request] = body
                val path = request.requestUrl!!.encodedPath
                return when (path) {
                    "/api/token/" -> {
                        val o = Json.parseToJsonElement(body) as JsonObject
                        when {
                            o["password"]?.jsonPrimitive?.content != "secret" -> json(401, """{"detail":"No active account found with the given credentials"}""")
                            otpEnabled && o["otp"] == null -> json(401, """{"detail":"Two-factor code required","reason":"otp_required","otp_required":true}""")
                            otpEnabled && o["otp"]?.jsonPrimitive?.content != "123456" -> json(401, """{"detail":"Two-factor code required","reason":"otp_invalid","otp_required":true}""")
                            else -> json(200, tokensBody(generation.get()))
                        }
                    }
                    "/api/token/refresh/" -> {
                        refreshes.incrementAndGet()
                        Thread.sleep(100) // give parallel requests time to pile up
                        val refresh = (Json.parseToJsonElement(body) as JsonObject)["refresh"]!!.jsonPrimitive.content
                        if (refreshFails || !validRefresh.remove(refresh)) {
                            json(401, """{"detail":"Token is blacklisted","code":"token_not_valid"}""")
                        } else {
                            json(200, tokensBody(generation.incrementAndGet()))
                        }
                    }
                    "/api/token/logout/" -> json(200, "{}")
                    else -> {
                        val auth = request.getHeader("Authorization")
                        if (auth != "Bearer ${jwt(generation.get())}") {
                            json(401, """{"detail":"Given token not valid for any token type","code":"token_not_valid"}""")
                        } else when (path) {
                            "/v1/device/" -> json(200, """[{"id":17,"raw_hash":"4711-01","name":"Hall A"},{"id":18,"raw_hash":"4711-02"}]""")
                            "/v1/device/17/config/request/" -> json(200, """{"success":true,"command":{"id":812,"command":"get_config","status":"pending"},"pending_commands":["get_config"]}""")
                            "/v1/device/17/config/" -> json(200, API2_CONFIG)
                            "/v1/pending/" -> json(403, """{"success":false,"reason":"Account pending review","pending_review":true}""")
                            "/v1/perm/" -> json(403, """{"success":false,"missing_permissions":["module_devices_commands"]}""")
                            else -> json(404, "{}")
                        }
                    }
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(tokens: TokenStore = InMemoryTokenStore()) =
        BackendClient({ server.url("/").toString() }, tokens, OkHttpClient())

    @Test
    fun `login with two-factor code`() = runBlocking<Unit> {
        otpEnabled = true
        val c = client()
        assertEquals(LoginResult.Failed("Login failed. Check user name and password, or try again in 15 minutes."), c.login("jdoe", "wrong", null))
        assertEquals(LoginResult.OtpRequired, c.login("jdoe", "secret", null))
        assertEquals(LoginResult.OtpInvalid, c.login("jdoe", "secret", "000000"))
        assertEquals(LoginResult.Success, c.login("jdoe", "secret", "123456"))
        val state = assertIs<AuthState.LoggedIn>(c.state.value)
        assertEquals("jdoe", state.username)
        // no bearer header on the token endpoint
        assertTrue(requests.filter { it.path == "/api/token/" }.all { it.getHeader("Authorization") == null })
    }

    @Test
    fun `bearer header and location parameter on device calls`() = runBlocking<Unit> {
        val c = client()
        c.login("jdoe", "secret", null)
        val api = DeviceConfigApi(c)
        val devices = api.devices("4711")
        assertEquals(listOf("4711-01 · Hall A", "4711-02"), devices.map { it.label })
        api.requestConfig(17, "4711")
        val config = api.config(17, "4711")
        assertEquals("4711-01", config.rawHash)
        assertEquals("2026-11-09T19:40:03+00:00", config.fetchedAt)
        assertNull(config.requestStatus)
        assertEquals(7, config.resultTtlDays)
        val parsed = LuaJson.parse(config.content!!)
        assertEquals("4711-01", LuaJson.get(parsed, "device.device_hash")!!.jsonPrimitive.content)

        val deviceCalls = requests.filter { it.path!!.startsWith("/v1/") }
        assertTrue(deviceCalls.all { it.requestUrl!!.queryParameter("location") == "4711" })
        assertTrue(deviceCalls.all { it.getHeader("Authorization")!!.startsWith("Bearer ") })
        assertEquals("POST", deviceCalls.single { it.path!!.contains("/config/request/") }.method)
    }

    @Test
    fun `expired access token is refreshed once and both tokens are stored`() = runBlocking<Unit> {
        val tokens = InMemoryTokenStore()
        val c = client(tokens)
        c.login("jdoe", "secret", null)
        generation.incrementAndGet() // server-side: the access token expired
        validRefresh.clear()
        validRefresh += "refresh-1"

        // Several calls at once: all get a 401, but only one refresh goes out.
        val results = (1..5).map { async(kotlinx.coroutines.Dispatchers.IO) { DeviceConfigApi(c).devices("4711") } }.awaitAll()
        assertTrue(results.all { it.size == 2 })
        assertEquals(1, refreshes.get())
        assertEquals("refresh-3", tokens.load()!!.refresh)
        assertEquals(jwt(3), tokens.load()!!.access)
    }

    @Test
    fun `failed refresh logs out`() = runBlocking<Unit> {
        val tokens = InMemoryTokenStore()
        val c = client(tokens)
        c.login("jdoe", "secret", null)
        generation.incrementAndGet()
        refreshFails = true
        assertFailsWith<NotLoggedInException> { DeviceConfigApi(c).devices("4711") }
        assertNull(tokens.load())
        assertEquals(AuthState.LoggedOut, c.state.value)
    }

    @Test
    fun `special account states give their own messages`() = runBlocking<Unit> {
        val c = client()
        c.login("jdoe", "secret", null)
        assertEquals("Your access request is still being reviewed.", assertFailsWith<BackendException> { c.get("v1/pending/") }.userMessage)
        assertTrue("module_devices_commands" in assertFailsWith<BackendException> { c.get("v1/perm/") }.userMessage)
    }

    @Test
    fun `logout blacklists the refresh token and forgets both`() = runBlocking<Unit> {
        val tokens = InMemoryTokenStore()
        val c = client(tokens)
        c.login("jdoe", "secret", null)
        c.logout()
        assertNull(tokens.load())
        val logout = requests.single { it.path == "/api/token/logout/" }
        assertEquals("""{"refresh":"refresh-1"}""", bodies[logout])
        assertFailsWith<NotLoggedInException> { c.get("v1/device/") }
    }

    @Test
    fun `device list in other shapes`() {
        val wrapped = Json.parseToJsonElement("""{"success":true,"results":[{"id":3,"hash":"9-01"},{"name":"no id"}]}""")
        assertEquals(listOf(RemoteDevice(3, "9-01", "9-01")), DeviceConfigApi.deviceList(wrapped))
        val pending = DeviceConfigApi.parseConfig(17, Json.parseToJsonElement("""{"success":true,"config":null,"requested":{"id":812,"status":"sent"}}"""))
        assertNull(pending.content)
        assertEquals("sent", pending.requestStatus)
    }

    @Test
    fun `old dotted profiles get the template's types`() {
        val kinds = mapOf("device.version" to FieldKind.STRING, "measurement.interval_sec" to FieldKind.NUMBER)
        val config = ProfileStore.fromDotted(mapOf("device.version" to "2026.11", "measurement.interval_sec" to "1800", "x.flag" to "true")) { kinds[it] }
        assertEquals("""{"device":{"version":"2026.11"},"measurement":{"interval_sec":1800},"x":{"flag":"true"}}""", config.toString())
    }

    companion object {
        private val API2_CONFIG = """
            {
              "success": true,
              "device": {"id": 17, "raw_hash": "4711-01", "project_id": 4711, "model": "smartex mx"},
              "config": {
                "command_id": 812,
                "path": "config/device_config.lua",
                "content": "return {\n  wifi = { ssid = \"Site\", pwd = \"p\\\"w\" },\n  device = { model = \"smartex mx\", device_hash = \"4711-01\", project_id = \"4711\" },\n  measurement = { interval_sec = 1800 },\n}\n",
                "fetched_at": "2026-11-09T19:40:03+00:00"
              },
              "params": {"pulldown_resistance": 136, "interval_sec": 1800, "poll_sec": 14400},
              "editable": {"interval_sec": {"min": 60, "max": 86400}},
              "requested": null,
              "result_ttl_days": 7,
              "pending_commands": [],
              "last_command_poll": "2026-11-09T19:40:03+00:00"
            }
        """.trimIndent()
    }
}
