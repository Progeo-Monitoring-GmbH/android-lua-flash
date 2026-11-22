package com.progeo.core.auth

import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoginCodeTest {
    private val token = "Xk3v9J2mQp7sLr0aBcDeFgHiJkLmNoPqRsTuVwXyZ01" // 43 chars like token_urlsafe(32)

    private fun jwt(type: String) = "eyJhbGciOiJIUzI1NiJ9." +
        Base64.getUrlEncoder().withoutPadding().encodeToString("""{"token_type":"$type","user_id":7}""".toByteArray()) + ".sig"

    @Test
    fun `login link of the portal`() {
        assertEquals(LoginCode.LoginToken(token), LoginCode.parse("https://dashboard.progeo.com/login/link/$token"))
        assertEquals(LoginCode.LoginToken(token), LoginCode.parse("  https://dashboard.progeo.com/login/link/$token/\n"))
    }

    @Test
    fun `query, app link, json and bare forms`() {
        assertEquals(LoginCode.LoginToken(token), LoginCode.parse("progeo://login?token=$token"))
        assertEquals(LoginCode.LoginToken(token), LoginCode.parse("""{"token":"$token"}"""))
        assertEquals(LoginCode.LoginToken(token), LoginCode.parse(token))
        val refresh = jwt("refresh")
        assertEquals(LoginCode.RefreshToken(refresh), LoginCode.parse(refresh))
        assertEquals(LoginCode.RefreshToken(refresh), LoginCode.parse("""{"refresh":"$refresh"}"""))
        assertEquals(LoginCode.RefreshToken(refresh), LoginCode.parse("progeo://login?refresh=$refresh"))
    }

    /** Made up; same form as a DRF authtoken key (40 hex digits), like the homepage QR code. */
    private val apiKey = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `api token of the homepage qr code`() {
        assertEquals(LoginCode.ApiToken(apiKey), LoginCode.parse("""{"token": "$apiKey"}"""))
        assertEquals(LoginCode.ApiToken(apiKey), LoginCode.parse(apiKey))
        assertEquals(LoginCode.ApiToken(apiKey), LoginCode.parse("progeo://login?token=$apiKey"))
        // a login link token (43 characters, base64url) stays a login token
        assertEquals(LoginCode.LoginToken(token), LoginCode.parse("""{"token":"$token"}"""))
        assertEquals("ApiToken(…)", LoginCode.ApiToken(apiKey).toString())
    }

    @Test
    fun `other codes are refused`() {
        assertNull(LoginCode.parse(""))
        assertNull(LoginCode.parse("4711-01"), "a device hash is no login code")
        assertNull(LoginCode.parse("WIFI:S:Setup-4711;T:WPA;P:secret;;"))
        assertNull(LoginCode.parse("https://dashboard.progeo.com/locations/12"))
        assertNull(LoginCode.parse(jwt("access")), "access tokens cannot be renewed")
        assertNull(LoginCode.parse("""{"token":"short"}"""))
        assertNull(LoginCode.parse("{not json"))
    }

    @Test
    fun `the secret never shows up in logs`() {
        assertEquals("LoginToken(…)", LoginCode.LoginToken(token).toString())
    }
}
