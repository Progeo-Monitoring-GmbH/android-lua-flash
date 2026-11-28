package com.progeo.luaflasher.core.m5

import com.progeo.luaflasher.core.BundleException
import com.progeo.luaflasher.core.bundle.BundleFormat
import com.progeo.luaflasher.core.fakes.FakeMicroPython
import com.progeo.luaflasher.core.io.sha256Hex
import com.progeo.luaflasher.core.micropython.FileTransferException
import com.progeo.luaflasher.core.micropython.MicroPythonException
import com.progeo.luaflasher.core.micropython.NoReplException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds release zips the way m5-stack `mPy/tools/release_bundle.py` does. */
internal object TestM5Bundles {
    data class F(val path: String, val content: ByteArray)

    fun zip(
        version: String = "1.0.1",
        files: List<F> = defaultFiles(version),
        autostart: F? = F("/flash/main.py", "import sys\nsys.path.insert(0, '/flash/progeo')\nimport main\nmain.run()\n".toByteArray()),
        device: String = "m5-ain4",
        configPath: String = "/flash/progeo/config.py",
        tamper: (MutableMap<String, ByteArray>) -> Unit = {},
    ): ByteArray {
        fun entry(f: F) = buildJsonObject {
            put("path", f.path)
            put("file", "files/" + f.path.trimStart('/'))
            put("size", f.content.size)
            put("sha256", sha256Hex(f.content))
        }
        val manifest = buildJsonObject {
            put("format", 1)
            put("device", device)
            put("runtime", "micropython-uiflow2")
            put("bundle_id", "m5-ain4-mpy-$version")
            put("release", "v$version")
            put("firmware", version)
            put("app_dir", "/flash/progeo")
            put("config_path", configPath)
            put("files", buildJsonArray { files.forEach { add(entry(it)) } })
            autostart?.let { put("autostart", entry(it)) }
        }
        val entries = linkedMapOf("bundle.json" to manifest.toString().toByteArray())
        (files + listOfNotNull(autostart)).forEach { entries["files/" + it.path.trimStart('/')] = it.content }
        tamper(entries)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun defaultFiles(version: String) = listOf(
        F("/flash/progeo/main.py", "def run():\n    pass\n".toByteArray()),
        F("/flash/progeo/version.py", "FW_VERSION = \"$version\"\nBUNDLE = \"m5-ain4-mpy-\" + FW_VERSION\n".toByteArray()),
        F("/flash/progeo/big.py", ByteArray(5000) { (it % 97 + 32).toByte() }),
        F("/flash/res/font/A_18.vlw", ByteArray(3000) { (it * 7).toByte() }),
    )
}

class M5BundleTest {
    @Test
    fun `opens a release and checks every file`() {
        val b = M5Bundles.open(TestM5Bundles.zip())
        assertEquals("m5-ain4-mpy-1.0.1", b.manifest.bundleId)
        assertEquals(4, b.manifest.files.size)
        assertEquals("/flash/main.py", b.manifest.autostart?.path)
        assertEquals(8000L + b.manifest.files.take(2).sumOf { it.size }, b.totalBytes)
    }

    @Test
    fun `refuses damaged, foreign and dangerous releases`() {
        assertFailsWith<BundleException> {
            M5Bundles.open(TestM5Bundles.zip { it["files/flash/progeo/big.py"] = ByteArray(5000) })
        }
        assertFailsWith<BundleException> { M5Bundles.open(TestM5Bundles.zip(device = "lua-wlan")) }
        assertFailsWith<BundleException> { M5Bundles.open(TestM5Bundles.zip(configPath = "/config.py")) }
        for (path in listOf("/flash/progeo/config.py", "/flash/boot.py", "/flash/progeo/../boot.py", "/sd/x.py")) {
            assertFailsWith<BundleException>(path) {
                M5Bundles.open(TestM5Bundles.zip(files = listOf(TestM5Bundles.F(path, "x".toByteArray()))))
            }
        }
        assertFailsWith<BundleException> {
            M5Bundles.open(TestM5Bundles.zip(autostart = TestM5Bundles.F("/flash/boot.py", "x".toByteArray())))
        }
    }

    @Test
    fun `parses the channel the CI publishes`() {
        val json = """
            {"format": 1, "channel": "mstack", "device": "m5-ain4", "updated": "2026-11-28T10:46:15Z",
             "min_app_version": 1, "latest": "m5-ain4-mpy-1.0.0",
             "bundles": [{"id": "m5-ain4-mpy-1.0.0", "release": "v1.0.0", "firmware": "1.0.0",
               "url": "bundles/m5-ain4-mpy-1.0.0.zip", "size": 108253, "sha256": "0c68",
               "sig_url": "bundles/m5-ain4-mpy-1.0.0.zip.sig", "created": "2026-11-28T09:44:51Z", "notes": "- x"}]}
        """.trimIndent()
        val ch = M5Bundles.parseChannel(json.toByteArray())
        assertEquals("1.0.0", ch.latestBundle?.firmware)
        assertFailsWith<BundleException> { M5Bundles.parseChannel(json.replace("m5-ain4\",", "lua-wlan\",").toByteArray()) }
        assertFailsWith<BundleException> { M5Bundles.parseChannel(json.replace("\"format\": 1", "\"format\": 2").toByteArray()) }
        // The LUA_WLAN parser must not be confused by it either way; only the format matters here.
        assertTrue(BundleFormat.FORMAT == 1)
    }
}

class M5InstallerTest {
    private val config = M5Config.parseValid(
        """
        # progeo:m5-config:1
        PLATFORM_PROJECT_ID = "940001"
        PLATFORM_DEVICE_HASH = "940001-07"
        PLATFORM_HOST = "api.progeo.com"
        PLATFORM_PATH = "/v1/device/940001/sample/"
        PLATFORM_TOKEN = "TOKEN0123456789A"
        PLATFORM_MODEL = "M5 AIN4"
        PLATFORM_DEVICE_VER = "2026.11"
        PLATFORM_PULLDOWN = 136
        PLATFORM_INTERVAL_SEC = 300
        PLATFORM_POLL_SEC = 300
        PLATFORM_SENSORS = 4
        PLATFORM_PE = 0
        """.trimIndent(),
    )

    private fun deployed(version: String = "1.0.0") = FakeMicroPython().apply {
        putText("/flash/progeo/version.py", "FW_VERSION = \"$version\"\nBUNDLE = \"m5-ain4-mpy-\" + FW_VERSION\n")
        putText("/flash/progeo/main.py", "old")
        putText("/flash/progeo/config.py", "PLATFORM_DEVICE_HASH = \"940001-03\"\n")
        putText("/flash/main.py", "import sys\nimport main\nmain.run()\n")
    }

    @Test
    fun `reads what is installed after stopping the firmware`() = runTest {
        val m5 = deployed().apply { ignoreInterrupts = 2 }
        val steps = ArrayList<M5Step>()
        val info = M5Installer(m5, onStep = { steps += it }).connect()
        assertEquals("1.0.0", info.firmware)
        assertEquals("m5-ain4-mpy-1.0.0", info.bundle)
        assertEquals("940001-03", info.deviceHash)
        assertTrue(info.autostart)
        assertFalse(m5.running)
        assertEquals(listOf(M5Step.CONNECT), steps)
        assertEquals(0, m5.softResets)
    }

    @Test
    fun `fresh UIFlow2 without firmware or config`() = runTest {
        val info = M5Installer(FakeMicroPython(running = false)).connect()
        assertNull(info.firmware)
        assertNull(info.configText)
        assertFalse(info.autostart)
    }

    @Test
    fun `silent board`() = runTest {
        assertFailsWith<NoReplException> { M5Installer(FakeMicroPython(dead = true)).connect() }
    }

    @Test
    fun `updates the firmware, keeps config_py, and a second run copies nothing`() = runTest {
        val m5 = deployed()
        val bundle = M5Bundles.open(TestM5Bundles.zip("1.0.1"))
        val installer = M5Installer(m5)
        installer.connect()
        var progress = 0L to 0L
        installer.installFirmware(bundle, autostart = false) { d, t -> progress = d to t }
        for (f in bundle.manifest.files) assertContentEquals(bundle.bytes(f), m5.files[f.path], f.path)
        assertEquals("PLATFORM_DEVICE_HASH = \"940001-03\"\n", m5.text("/flash/progeo/config.py"))
        assertEquals("import sys\nimport main\nmain.run()\n", m5.text("/flash/main.py"), "autostart untouched")
        assertTrue(m5.files.keys.none { it.endsWith(".new") })
        assertEquals(progress.second, progress.first)

        val writes = m5.executed.count { it.startsWith("_pg_w") }
        installer.installFirmware(bundle, autostart = false)
        assertEquals(writes, m5.executed.count { it.startsWith("_pg_w") }, "nothing copied again")
        installer.restart()
        assertEquals(1, m5.hardResets)
        assertEquals(0, m5.softResets)
    }

    @Test
    fun `an interrupted copy leaves the old firmware in place and a retry finishes`() = runTest {
        val m5 = deployed().apply { failWriteOf = "big.py.new" }
        val bundle = M5Bundles.open(TestM5Bundles.zip("1.0.1"))
        val installer = M5Installer(m5)
        installer.connect()
        assertFailsWith<MicroPythonException> { installer.installFirmware(bundle, autostart = false) }
        assertEquals("old", m5.text("/flash/progeo/main.py"), "nothing activated yet")
        assertTrue(m5.text("/flash/progeo/version.py")!!.contains("1.0.0"))

        installer.installFirmware(bundle, autostart = false)
        assertTrue(m5.text("/flash/progeo/version.py")!!.contains("1.0.1"))
    }

    @Test
    fun `restarts the M5 after a failed step without a soft reset`() = runTest {
        val m5 = deployed().apply { failWriteOf = "main.py.new" }
        val installer = M5Installer(m5)
        installer.connect()
        assertFailsWith<MicroPythonException> { installer.installFirmware(M5Bundles.open(TestM5Bundles.zip("1.0.1")), autostart = false) }
        installer.recoverAndRestart()
        assertEquals(1, m5.hardResets)
        assertEquals(0, m5.softResets)
        assertTrue(m5.running, "firmware runs again")
    }

    @Test
    fun `a corrupted write is caught by the check on the board`() = runTest {
        val m5 = deployed().apply { corruptWriteOf = "A_18.vlw.new" }
        val installer = M5Installer(m5)
        installer.connect()
        assertFailsWith<FileTransferException> { installer.installFirmware(M5Bundles.open(TestM5Bundles.zip("1.0.1")), autostart = false) }
        assertEquals("old", m5.text("/flash/progeo/main.py"))
    }

    @Test
    fun `first install with autostart on a fresh UIFlow2`() = runTest {
        val m5 = FakeMicroPython(running = false)
        val bundle = M5Bundles.open(TestM5Bundles.zip("1.0.1"))
        val installer = M5Installer(m5)
        installer.connect()
        installer.installFirmware(bundle, autostart = true)
        installer.writeConfig(config)
        assertTrue(m5.text("/flash/main.py")!!.contains("main.run()"))
        assertEquals(config, M5Config.parse(m5.text("/flash/progeo/config.py")!!))
        assertTrue(M5Installer(m5).connect().autostart)
    }

    @Test
    fun `writes config_py and hands back the previous one`() = runTest {
        val m5 = deployed()
        val installer = M5Installer(m5)
        installer.connect()
        val previous = installer.writeConfig(config)
        assertEquals("PLATFORM_DEVICE_HASH = \"940001-03\"\n", previous)
        assertEquals(config.render(), m5.text("/flash/progeo/config.py"))
        assertEquals("940001-07", M5Installer(m5).connect().deviceHash)
        assertFailsWith<M5ConfigException> { installer.writeConfig(M5Config.parse("PLATFORM_DEVICE_HASH = \"x\"")) }
        assertEquals(0, m5.softResets)
    }
}
