package com.progeo.luaflasher.core.bundle

import com.google.crypto.tink.subtle.Ed25519Sign
import com.progeo.luaflasher.core.AppTooOldException
import com.progeo.luaflasher.core.BundleException
import com.progeo.luaflasher.core.Fixtures
import com.progeo.luaflasher.core.SignatureException
import com.progeo.luaflasher.core.config.ConfigTemplate
import com.progeo.luaflasher.core.io.sha256Hex
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BundleTest {

    // --- signatures ---

    private val keyPair = Ed25519Sign.KeyPair.newKeyPair()
    private val testKeys = PublicKeys(mapOf("test" to keyPair.publicKey))

    private fun sign(data: ByteArray, id: String = "test") =
        "$id:" + Base64.getEncoder().encodeToString(Ed25519Sign(keyPair.privateKey).sign(data))

    @Test
    fun `valid signature`() {
        val data = "hello".toByteArray()
        assertEquals("test", SignatureVerifier(testKeys).verify(data, sign(data)))
        // trailing newline in the .sig file is tolerated
        assertEquals("test", SignatureVerifier(testKeys).verify(data, sign(data) + "\n"))
    }

    @Test
    fun `changed byte unknown key and malformed signature are rejected`() {
        val data = "hello".toByteArray()
        val sig = sign(data)
        val verifier = SignatureVerifier(testKeys)
        assertFailsWith<SignatureException> { verifier.verify("hellp".toByteArray(), sig) }
        assertFailsWith<SignatureException> { verifier.verify(data, sign(data, id = "other")) }
        assertFailsWith<SignatureException> { verifier.verify(data, "test") }
        assertFailsWith<SignatureException> { verifier.verify(data, "test:not base64!") }
        assertFailsWith<SignatureException> { verifier.verify(data, "test:" + Base64.getEncoder().encodeToString(ByteArray(10))) }
        val raw = Base64.getDecoder().decode(sig.substringAfter(':'))
        raw[5] = (raw[5].toInt() xor 1).toByte()
        assertFailsWith<SignatureException> { verifier.verify(data, "test:" + Base64.getEncoder().encodeToString(raw)) }
    }

    @Test
    fun `the published beta channel verifies with the real key`() {
        val keys = PublicKeys.parse(Fixtures.publicKeysJson())
        val channelBytes = Fixtures.bytes("beta.json")
        assertEquals("main", SignatureVerifier(keys).verify(channelBytes, Fixtures.text("beta.json.sig")))

        val tampered = channelBytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFailsWith<SignatureException> { SignatureVerifier(keys).verify(tampered, Fixtures.text("beta.json.sig")) }

        val channel = ChannelParser.parse(channelBytes)
        assertEquals("beta", channel.channel)
        val latest = channel.latestBundle!!
        assertEquals("v1.2.0_fw-v1.2.0-tls-lua53", latest.id)
        assertEquals("bundles/lua-wlan-v1.2.0_fw-v1.2.0-tls-lua53.zip", latest.url)
        assertEquals(1, latest.configSchema)
    }

    /** End-to-end with a downloaded bundle: -PbundleZip=<path to .zip, .zip.sig next to it>. */
    @Test
    fun `a downloaded bundle verifies and validates`() {
        val path = System.getProperty("bundleZip") ?: return
        val zip = File(path).readBytes()
        val keys = PublicKeys.parse(Fixtures.publicKeysJson())
        assertEquals("main", SignatureVerifier(keys).verify(zip, File("$path.sig").readText()))
        val bundle = BundleValidator.validate(zip)
        assertEquals(listOf(0x00000L, 0x10000L), bundle.flashSegments().map { it.address })
        ConfigTemplate.parse(bundle.template)
    }

    // --- bundle validation ---

    @Test
    fun `valid bundle`() {
        val bundle = BundleValidator.validate(TestBundles.zip())
        assertEquals("v9.9.9_fw-v1.2.0-tls-lua53", bundle.manifest.bundleId)
        val segments = bundle.flashSegments()
        assertEquals(listOf(0L, 0x10000L), segments.map { it.address })
        assertContentEquals(TestBundles.app, segments[1].data)
        val payload = bundle.installPayload("return {}\n")
        assertEquals("config/device_config.lua", payload.deviceConfig.path)
        assertEquals(listOf("config/runtime_config.lua"), payload.files.map { it.path })
        assertEquals("init.lua", payload.initLua.path)
        assertEquals("v1.2.0-tls-lua53", payload.firmwareVersion)
    }

    @Test
    fun `the real bundle json parses`() {
        val manifest = BundleValidator.parseManifest(Fixtures.text("bundle.json"))
        assertEquals(2, manifest.firmware.segments.size)
        assertEquals(9, manifest.lfs.modules.size)
        assertEquals("templates/device_config.lua.template", manifest.deviceConfigTemplate)
    }

    @Test
    fun `a changed byte is rejected`() {
        val entries = TestBundles.entries()
        entries["firmware/0x10000.bin"] = TestBundles.app.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val e = assertFailsWith<BundleException> { BundleValidator.validate(TestBundles.zip(entries)) }
        assertTrue("SHA-256" in e.message!!, e.message)
    }

    @Test
    fun `size mismatch and missing files are rejected`() {
        val entries = TestBundles.entries()
        entries["fs/init.lua"] = TestBundles.init + 0
        assertFailsWith<BundleException> { BundleValidator.validate(TestBundles.zip(entries)) }

        val missing = TestBundles.entries().also { it.remove("fs/lfs.img") }
        assertFailsWith<BundleException> { BundleValidator.validate(TestBundles.zip(missing)) }

        val noTemplate = TestBundles.entries().also { it.remove("templates/device_config.lua.template") }
        assertFailsWith<BundleException> { BundleValidator.validate(TestBundles.zip(noTemplate)) }
    }

    @Test
    fun `zip slip names are rejected`() {
        for (name in listOf("../evil.lua", "fs/../../evil", "/etc/passwd", "C:/windows/x", "fs\\init.lua", "./bundle.json")) {
            val entries = TestBundles.entries().also { it[name] = byteArrayOf(1) }
            assertFailsWith<BundleException>(name) { BundleValidator.validate(TestBundles.zip(entries)) }
        }
    }

    @Test
    fun `manifest pointing outside the bundle is rejected`() {
        val manifest = TestBundles.manifest(segments = listOf(Triple("0x00000", "../firmware.bin", TestBundles.boot)))
        assertFailsWith<BundleException> { BundleValidator.validate(TestBundles.zip(TestBundles.entries(manifest))) }
    }

    @Test
    fun `overlapping segments are rejected`() {
        val manifest = TestBundles.manifest(
            segments = listOf(
                Triple("0x00000", "firmware/0x00000.bin", TestBundles.boot),
                Triple("0x07000", "firmware/0x10000.bin", TestBundles.app),
            ),
        )
        assertFailsWith<BundleException> { BundleValidator.validate(TestBundles.zip(TestBundles.entries(manifest))) }
    }

    @Test
    fun `unknown format and newer config schema ask for an app update`() {
        assertFailsWith<AppTooOldException> { BundleValidator.validate(TestBundles.zip(TestBundles.entries(TestBundles.manifest(format = 2)))) }
        assertFailsWith<AppTooOldException> { BundleValidator.validate(TestBundles.zip(TestBundles.entries(TestBundles.manifest(configSchema = 2)))) }
    }

    @Test
    fun `not a zip`() {
        assertFailsWith<BundleException> { BundleValidator.validate("nope".toByteArray()) }
    }

    @Test
    fun `channel urls must stay relative to the base url`() {
        val good = String(Fixtures.bytes("beta.json"))
        val bad = good.replace("\"bundles/lua-wlan", "\"https://evil.example/lua-wlan")
        assertFailsWith<BundleException> { ChannelParser.parse(bad.toByteArray()) }
        assertEquals(64, sha256Hex(ByteArray(0)).length)
    }
}
