package com.progeo.core.auth

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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Login with a scanned code against the portal's endpoints (access_viewset.py, simplejwt). */
class CodeLoginTest {
    private val server = MockWebServer()
    private val usedTokens = HashSet<String>()
    private val bodies = ArrayList<JsonObject>()

    private val token = "Xk3v9J2mQp7sLr0aBcDeFgHiJkLmNoPqRsTuVwXyZ01"
    private val twoFactorToken = "TwoFactorTokenTwoFactorTokenTwoFactorToken1"

    private fun jwt(type: String, user: String = "jdoe") = "x." +
        Base64.getUrlEncoder().withoutPadding().encodeToString("""{"token_type":"$type","username":"$user","exp":9999999999}""".toByteArray()) + ".sig"

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val o = if (body.isNotEmpty()) Json.parseToJsonElement(body) as JsonObject else JsonObject(emptyMap())
                synchronized(bodies) { bodies += o }
                return when (request.requestUrl!!.encodedPath) {
                    "/api/auth-support/login-link/exchange/" -> {
                        val t = o["token"]?.jsonPrimitive?.content
                        val otp = o["otp"]?.jsonPrimitive?.content
                        when {
                            t == null || t in usedTokens || (t != token && t != twoFactorToken) -> json(400, """{"success":false,"reason":"invalid_token"}""")
                            t == twoFactorToken && otp == null -> json(400, """{"success":false,"reason":"otp_required","otp_required":true}""")
                            t == twoFactorToken && otp != "123456" -> json(400, """{"success":false,"reason":"otp_invalid","otp_required":true}""")
                            else -> {
                                usedTokens += t
                                json(200, """{"success":true,"refresh":"${jwt("refresh")}","access":"${jwt("access")}","forward":"/"}""")
                            }
                        }
                    }
                    "/api/token/refresh/" -> {
                        val r = o["refresh"]?.jsonPrimitive?.content
                        if (r == null || r in usedTokens) {
                            json(401, """{"detail":"Token is blacklisted","code":"token_not_valid"}""")
                        } else {
                            usedTokens += r
                            json(200, """{"access":"${jwt("access", "qr")}","refresh":"${jwt("refresh", "qr")}2"}""")
                        }
                    }
                    "/api/auth-support/module-permissions/" -> json(
                        200,
                        """{"success":true,"user_id":7,"username":"jdoe","permissions":{"module_devices_enabled":true,"module_devices_commands":false},"enabled":["module_devices_enabled"]}""",
                    )
                    else -> json(404, "{}")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(store: TokenStore = InMemoryTokenStore()) =
        BackendClient(baseUrl = { server.url("/").toString() }, tokens = store, base = OkHttpClient())

    @Test
    fun `login token is exchanged once`() = runBlocking<Unit> {
        val store = InMemoryTokenStore()
        val c = client(store)
        assertEquals(LoginResult.Success, c.login(LoginCode.LoginToken(token)))
        assertEquals("jdoe", (c.state.value as AuthState.LoggedIn).username)
        assertTrue(store.load()!!.refresh.isNotEmpty(), "the refresh token is stored")

        c.logout()
        val again = c.login(LoginCode.LoginToken(token))
        assertIs<LoginResult.Failed>(again)
        assertTrue("already used" in again.message)
        assertIs<AuthState.LoggedOut>(c.state.value)
    }

    @Test
    fun `login token with two-factor code`() = runBlocking<Unit> {
        val c = client()
        val code = LoginCode.LoginToken(twoFactorToken)
        assertEquals(LoginResult.OtpRequired, c.login(code))
        assertEquals(LoginResult.OtpInvalid, c.login(code, "000000"))
        assertEquals(LoginResult.Success, c.login(code, " 123456 "))
        assertEquals("123456", bodies.last()["otp"]!!.jsonPrimitive.content)
    }

    @Test
    fun `refresh token from the code is rotated`() = runBlocking<Unit> {
        val store = InMemoryTokenStore()
        val c = client(store)
        val scanned = jwt("refresh", "qr")
        assertEquals(LoginResult.Success, c.login(LoginCode.RefreshToken(scanned)))
        assertEquals("qr", (c.state.value as AuthState.LoggedIn).username)
        assertTrue(store.load()!!.refresh != scanned, "the scanned token is replaced by the rotated one")
        assertIs<LoginResult.Failed>(client().login(LoginCode.RefreshToken(scanned)))
    }

    @Test
    fun `module permissions`() = runBlocking<Unit> {
        val c = client()
        c.login(LoginCode.LoginToken(token))
        val p = c.modulePermissions()
        assertTrue(p.has("module_devices_enabled"))
        assertEquals(listOf("module_devices_commands"), p.missing(listOf("module_devices_enabled", "module_devices_commands")))
        val fromMap = ModulePermissions.parse(Json.parseToJsonElement("""{"permissions":{"a":true,"b":false}}"""))
        assertEquals(setOf("a"), fromMap.enabled)
        assertNull(ModulePermissions.parse(Json.parseToJsonElement("[]")).enabled.firstOrNull())
    }
}
