package com.progeo.luaflasher.data

import com.google.crypto.tink.subtle.Ed25519Sign
import com.progeo.luaflasher.core.SignatureException
import com.progeo.luaflasher.core.bundle.ChannelBundle
import com.progeo.luaflasher.core.bundle.PublicKeys
import com.progeo.luaflasher.core.bundle.SignatureVerifier
import com.progeo.luaflasher.core.io.sha256Hex
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val keyPair = Ed25519Sign.KeyPair.newKeyPair()
    private val verifier = SignatureVerifier(PublicKeys(mapOf("test" to keyPair.publicKey)))
    private val server = MockWebServer()
    private val files = HashMap<String, ByteArray>()
    private val ranges = ArrayList<String?>()

    private fun sign(data: ByteArray) = "test:" + Base64.getEncoder().encodeToString(Ed25519Sign(keyPair.privateKey).sign(data))

    private val bundle = Random(5).nextBytes(100_000)

    private fun channelJson(sha: String = sha256Hex(bundle), size: Int = bundle.size) = """
        {"format":1,"channel":"beta","updated":"2026-11-03T13:47:01Z","min_app_version":1,"latest":"b1",
         "bundles":[{"id":"b1","release":"v1","firmware":"fw1","config_schema":1,"url":"bundles/b1.zip","size":$size,
         "sha256":"$sha","sig_url":"bundles/b1.zip.sig","created":"2026-11-02T19:40:21Z","notes":""}]}
    """.trimIndent().toByteArray()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.removePrefix("/firmware/")
                val body = files[path] ?: return MockResponse().setResponseCode(404)
                if (path.endsWith(".zip")) ranges += request.getHeader("Range")
                val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toInt() }
                return if (range != null) {
                    MockResponse().setResponseCode(206).setBody(Buffer().write(body.copyOfRange(range, body.size)))
                } else {
                    MockResponse().setBody(Buffer().write(body))
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun baseUrl() = server.url("/firmware/").toString()

    private fun client() = ChannelClient(OkHttpClient(), verifier, tmp.newFolder("channels"))

    private fun entry() = ChannelBundle("b1", "v1", "fw1", 1, "bundles/b1.zip", bundle.size.toLong(), sha256Hex(bundle), "bundles/b1.zip.sig")

    @Test
    fun `channel is verified then parsed and cached`() = runTest {
        val json = channelJson()
        files["versions/beta"] = json
        files["versions/beta.sig"] = sign(json).toByteArray()
        val client = client()
        val result = assertIs<ChannelResult.Published>(client.fetch(baseUrl(), "beta"))
        assertEquals("b1", result.channel.latest)
        assertEquals("b1", client.cached("beta")?.latest)
    }

    @Test
    fun `tampered channel is rejected and the cache kept`() = runTest {
        val json = channelJson()
        files["versions/beta"] = json
        files["versions/beta.sig"] = sign(json).toByteArray()
        val client = client()
        client.fetch(baseUrl(), "beta")

        files["versions/beta"] = channelJson(sha = "0".repeat(64))
        assertFailsWith<SignatureException> { client.fetch(baseUrl(), "beta") }
        assertEquals(sha256Hex(bundle), client.cached("beta")?.bundles?.single()?.sha256)
    }

    @Test
    fun `missing channel means not published`() = runTest {
        assertIs<ChannelResult.NotPublished>(client().fetch(baseUrl(), "stable"))
    }

    @Test
    fun `bundle download resumes with Range`() = runTest {
        files["bundles/b1.zip"] = bundle
        files["bundles/b1.zip.sig"] = sign(bundle).toByteArray()
        val target = tmp.newFile("b1.zip.part")
        target.writeBytes(bundle.copyOfRange(0, 40_000))
        val keyId = client().downloadBundle(baseUrl(), entry(), target)
        assertEquals("test", keyId)
        assertContentEquals(bundle, target.readBytes())
        assertEquals(listOf<String?>("bytes=40000-"), ranges)
    }

    @Test
    fun `tampered bundle is deleted`() = runTest {
        val tampered = bundle.copyOf().also { it[500] = (it[500] + 1).toByte() }
        files["bundles/b1.zip"] = tampered
        files["bundles/b1.zip.sig"] = sign(tampered).toByteArray()
        val target = tmp.newFile("b1.zip.part")
        target.delete()
        assertFailsWith<SignatureException> { client().downloadBundle(baseUrl(), entry(), target) }
        assertFalse(target.exists())
    }

    @Test
    fun `bundle with a signature from an unknown key is deleted`() = runTest {
        files["bundles/b1.zip"] = bundle
        files["bundles/b1.zip.sig"] = sign(bundle).replace("test:", "other:").toByteArray()
        val target = tmp.newFile("b1.zip.part")
        target.delete()
        val client = client()
        val e = assertFailsWith<SignatureException> { client.downloadBundle(baseUrl(), entry(), target) }
        assertTrue("unknown" in e.message!!)
        assertFalse(target.exists())
        assertNull(client.cached("beta"))
    }
}
