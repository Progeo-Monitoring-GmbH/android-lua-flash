package com.progeo.luaflasher.m5

import com.google.crypto.tink.subtle.Ed25519Sign
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
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class M5FirmwareStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val keyPair = Ed25519Sign.KeyPair.newKeyPair()
    private val verifier = SignatureVerifier(PublicKeys(mapOf("test" to keyPair.publicKey)))
    private val server = MockWebServer()
    private val files = HashMap<String, ByteArray>()

    private fun sign(data: ByteArray) = "test:" + Base64.getEncoder().encodeToString(Ed25519Sign(keyPair.privateKey).sign(data))

    /** A release zip as m5-stack mPy/tools/release_bundle.py builds it. */
    private fun releaseZip(version: String): ByteArray {
        val main = "def run():\n    pass\n".toByteArray()
        val ver = "FW_VERSION = \"$version\"\n".toByteArray()
        fun entry(path: String, data: ByteArray) =
            """{"path":"$path","file":"files${path}","size":${data.size},"sha256":"${sha256Hex(data)}"}"""
        val manifest = """
            {"format":1,"device":"m5-ain4","bundle_id":"m5-ain4-mpy-$version","release":"v$version","firmware":"$version",
             "app_dir":"/flash/progeo","config_path":"/flash/progeo/config.py",
             "files":[${entry("/flash/progeo/main.py", main)},${entry("/flash/progeo/version.py", ver)}]}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in listOf("bundle.json" to manifest.toByteArray(), "files/flash/progeo/main.py" to main, "files/flash/progeo/version.py" to ver)) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun publish(vararg versions: String, latest: String = versions.first(), sigFor: (String, ByteArray) -> ByteArray = { _, d -> sign(d).toByteArray() }) {
        val entries = versions.map { v ->
            val zip = releaseZip(v)
            val name = "bundles/m5-ain4-mpy-$v.zip"
            files[name] = zip
            files["$name.sig"] = sigFor(v, zip)
            """{"id":"m5-ain4-mpy-$v","release":"v$v","firmware":"$v","url":"$name","size":${zip.size},
               "sha256":"${sha256Hex(zip)}","sig_url":"$name.sig"}"""
        }
        val channel = """{"format":1,"channel":"mstack","device":"m5-ain4","min_app_version":1,"latest":"m5-ain4-mpy-$latest",
            "bundles":[${entries.joinToString(",")}]}""".toByteArray()
        files["versions/mstack"] = channel
        files["versions/mstack.sig"] = sign(channel).toByteArray()
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = files[request.path!!.removePrefix("/firmware/")] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(Buffer().write(body))
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun baseUrl() = server.url("/firmware/").toString()

    private fun store(dir: java.io.File = tmp.newFolder("m5")) = M5FirmwareStore(OkHttpClient(), verifier, dir, appVersionCode = 1)

    @Test
    fun `sync verifies the channel, downloads latest and opens it offline`() = runTest {
        publish("1.0.1", "1.0.0")
        val dir = tmp.newFolder("m5")
        val store = store(dir)
        assertTrue(store.sync(baseUrl()).isSuccess)
        assertEquals(setOf("m5-ain4-mpy-1.0.1"), store.state.value.downloaded)
        assertEquals("1.0.1", store.open("m5-ain4-mpy-1.0.1").manifest.firmware)

        server.shutdown()
        val offline = store(dir)
        offline.loadCached()
        assertEquals("m5-ain4-mpy-1.0.1", offline.state.value.channel?.latest)
        assertEquals(2, offline.open("m5-ain4-mpy-1.0.1").manifest.files.size)
    }

    @Test
    fun `older releases on demand`() = runTest {
        publish("1.0.1", "1.0.0")
        val store = store()
        store.sync(baseUrl())
        val old = store.state.value.channel!!.bundles.first { it.firmware == "1.0.0" }
        assertTrue(store.download(baseUrl(), old).isSuccess)
        assertTrue(store.isDownloaded(old.id))
    }

    @Test
    fun `a release with a bad signature is never stored`() = runTest {
        publish("1.0.1") { _, _ -> sign("other".toByteArray()).toByteArray() }
        val store = store()
        assertTrue(store.sync(baseUrl()).isFailure)
        assertFalse(store.isDownloaded("m5-ain4-mpy-1.0.1"))
        assertNotNull(store.state.value.error)
    }

    @Test
    fun `a tampered file on the phone is refused when opened`() = runTest {
        publish("1.0.1")
        val dir = tmp.newFolder("m5")
        val store = store(dir)
        store.sync(baseUrl())
        val zip = java.io.File(dir, "m5-ain4-mpy-1.0.1.zip")
        zip.writeBytes(zip.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        assertFailsWith<Exception> { store.open("m5-ain4-mpy-1.0.1") }
    }

    @Test
    fun `nothing published yet`() = runTest {
        val store = store()
        assertTrue(store.sync(baseUrl()).isSuccess)
        assertTrue(store.state.value.notPublished)
    }
}
