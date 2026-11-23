package com.progeo.teltonika

import com.progeo.teltonika.api.DeviceInfo
import com.progeo.teltonika.api.RouterApi
import com.progeo.teltonika.api.RutosClient
import com.progeo.teltonika.credentials.Identity
import com.progeo.teltonika.credentials.LoginCandidate
import com.progeo.teltonika.credentials.LoginOrder
import com.progeo.teltonika.credentials.Passwords
import com.progeo.teltonika.label.Credentials
import com.progeo.teltonika.label.RouterLabels
import com.progeo.teltonika.tls.CertificateChangedException
import com.progeo.teltonika.tls.PinningTrustManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CredentialsAndTlsTest {

    @Test
    fun `generated passwords meet the RutOS rules`() {
        repeat(200) {
            val p = Passwords.generate()
            assertEquals(16, p.length)
            assertTrue(Passwords.meetsRutosRules(p), p)
        }
        assertTrue(Passwords.meetsRutosRules(Passwords.generate(8)))
    }

    @Test
    fun `login order current, pending, factory, without duplicates`() {
        val label = RouterLabels.parse("WIFI:S:x;P:12345678;SN:1234567890;U:admin;PW:Factory1;")
        val list = LoginOrder.candidates("admin", current = "Current1", pending = "Pending1", factory = null, label = label)
        assertEquals(listOf(LoginCandidate.Kind.CURRENT, LoginCandidate.Kind.PENDING, LoginCandidate.Kind.FACTORY), list.map { it.kind })
        val dup = LoginOrder.candidates("admin", current = "Factory1", pending = null, factory = Credentials("admin", "Factory1"), label = null)
        assertEquals(1, dup.size)
    }

    private fun device(serial: String?, mac: String?) =
        DeviceInfo(serial, null, null, mac, null, null, null, null, null, JsonObject(emptyMap()))

    @Test
    fun `identity check`() {
        assertEquals(Identity.Result.Match, Identity.check("1234567890", "00:1E:42:AA:BB:CC", device("1234567890", "001E42AABBCC")))
        assertIs<Identity.Result.Mismatch>(Identity.check("1234567890", null, device("1111111111", null)))
        assertIs<Identity.Result.Mismatch>(Identity.check("1234567890", "00:1E:42:AA:BB:CC", device(null, "00:1E:42:AA:BB:CD")))
        assertEquals(Identity.Result.Match, Identity.check("1234567890", "00:1E:42:AA:BB:CC", device(null, "00-1e-42-aa-bb-cc")))
        assertIs<Identity.Result.Unknown>(Identity.check("1234567890", null, device(null, null)))
    }

    @Test
    fun `certificate pinned on first use, change detected`() = runTest {
        val routerCert = HeldCertificate.Builder().addSubjectAlternativeName("192.168.1.1").build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(routerCert).build().sslSocketFactory(), false)
        server.dispatcher = FakeRutos()
        server.start()
        try {
            // First contact: accept and remember.
            val tofu = PinningTrustManager(pinned = null)
            val first = RutosClient(server.url("/").toString(), tofu.applyTo(OkHttpClient.Builder()).build())
            first.login(Credentials("admin", "Factory1"))
            val pin = assertNotNull(tofu.seen)
            assertEquals(PinningTrustManager.fingerprint(routerCert.certificate), pin)

            // Same certificate with the pin: fine.
            val pinned = RutosClient(server.url("/").toString(), PinningTrustManager(pin).applyTo(OkHttpClient.Builder()).build())
            pinned.login(Credentials("admin", "Factory1"))
            assertEquals("1234567890", RouterApi(pinned).device().serial)

            // Another certificate pinned: refused.
            val other = PinningTrustManager.fingerprint(HeldCertificate.Builder().build().certificate)
            val wrong = RutosClient(server.url("/").toString(), PinningTrustManager(other).applyTo(OkHttpClient.Builder()).build())
            assertFailsWith<CertificateChangedException> { wrong.login(Credentials("admin", "Factory1")) }
        } finally {
            server.shutdown()
        }
    }
}
