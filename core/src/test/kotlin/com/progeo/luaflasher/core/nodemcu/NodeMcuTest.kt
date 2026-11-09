package com.progeo.luaflasher.core.nodemcu

import com.progeo.luaflasher.core.LfsReloadException
import com.progeo.luaflasher.core.LuaCommandException
import com.progeo.luaflasher.core.NoPromptException
import com.progeo.luaflasher.core.UploadException
import com.progeo.luaflasher.core.WrongFirmwareException
import com.progeo.luaflasher.core.fakes.FakeNodeMcu
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeMcuTest {

    private val modules = listOf("Relais", "connWLAN", "control", "display", "logger", "main", "measurement", "portal", "webserver")

    private fun payload(config: String = "return {\n  device = {\n    device_hash = \"abc\",\n  },\n}\n") = InstallPayload(
        bundleId = "v1.2.0_fw-v1.2.0-tls-lua53",
        firmwareVersion = "v1.2.0-tls-lua53",
        // The fake reads the module names from the image.
        lfsImage = modules.joinToString(",").toByteArray(),
        lfsModules = modules,
        files = listOf(RemoteFile("config/runtime_config.lua", "return {\n  a = 1,\n}\n".toByteArray())),
        initLua = RemoteFile("init.lua", "-- init".toByteArray()),
        deviceConfig = RemoteFile("config/device_config.lua", config.toByteArray()),
    )

    private fun TestScope.board(configure: FakeNodeMcu.() -> Unit = {}): FakeNodeMcu =
        FakeNodeMcu(backgroundScope).apply(configure).also { it.reset() }

    @Test
    fun `waits for the prompt and runs wrapped commands`() = runTest {
        val node = board()
        val console = LuaConsole(node)
        console.waitForPrompt()
        assertEquals("Lua 5.3 true", console.exec("return _VERSION..' '..tostring(node.LFS ~= nil)").value)
    }

    @Test
    fun `no prompt from a silent board`() = runTest {
        val console = LuaConsole(FakeNodeMcu(backgroundScope, dead = true))
        assertFailsWith<NoPromptException> { console.waitForPrompt() }
        assertEquals(LuaConsole.PROMPT_TIMEOUT_MS, testScheduler.currentTime)
    }

    @Test
    fun `lua errors and compile errors surface as LuaCommandException`() = runTest {
        val node = board()
        val console = LuaConsole(node)
        console.waitForPrompt()
        val runtime = assertFailsWith<LuaCommandException> { console.exec("error('boom')") }
        assertEquals("The board reported an error: stdin:1: boom", runtime.userMessage)
        val syntax = assertFailsWith<LuaCommandException> { console.exec("syntax error here") }
        assertTrue("unexpected symbol" in syntax.userMessage)
        // the console is still usable afterwards
        assertEquals("nil", console.exec("return type(recv)").value)
    }

    @Test
    fun `uploads a 128 KB file and checks the size`() = runTest {
        val node = board()
        val console = LuaConsole(node)
        console.waitForPrompt()
        val data = Random(3).nextBytes(128 * 1024)
        var last = 0L
        FileUploader(console).upload("big.bin", data) { sent, _ -> last = sent }
        assertContentEquals(data, node.files["big.bin"])
        assertEquals(data.size.toLong(), last)
        // empty file
        FileUploader(console).upload("empty.txt", ByteArray(0))
        assertEquals(0, node.files["empty.txt"]?.size)
    }

    @Test
    fun `NAK aborts the upload`() = runTest {
        val node = board { nakBlock = 2 }
        val console = LuaConsole(node)
        console.waitForPrompt()
        val e = assertFailsWith<UploadException> { FileUploader(console).upload("x.bin", ByteArray(1000)) }
        assertTrue("NAK" in e.message!!)
    }

    @Test
    fun `reads files of any size and content from the board`() = runTest {
        val content = ByteArray(2_600) { (it % 256).toByte() } // several chunks, every byte value
        val node = board { files["config/device_config.lua"] = content }
        val console = LuaConsole(node)
        console.waitForPrompt()
        val reader = NodeMcuFiles(console)
        assertEquals(mapOf("config/device_config.lua" to 2_600L), reader.list())
        assertContentEquals(content, reader.read("config/device_config.lua"))
        assertNull(reader.read("config/missing.lua"))
        // empty file
        node.files["empty.lua"] = ByteArray(0)
        assertContentEquals(ByteArray(0), reader.read("empty.lua"))
    }

    @Test
    fun `reads the device config a full install wrote`() = runTest {
        val node = board()
        val config = "return {\n  device = {\n    device_hash = \"4711-01\",\n  },\n}\n"
        NodeMcuInstaller(LuaConsole(node)).install(payload(config), InstallOptions(InstallMode.FULL))
        node.reset()
        val console = LuaConsole(node)
        console.waitForPrompt()
        assertEquals(config, String(NodeMcuFiles(console).read("config/device_config.lua")!!))
    }

    @Test
    fun `all console lines fit`() {
        FileUploader.RECV_LUA.forEach { assertTrue(it.length <= LuaConsole.MAX_LINE, it) }
        assertTrue(NodeMcuFiles.HEX_HELPER.length <= LuaConsole.MAX_LINE, "hex helper is ${NodeMcuFiles.HEX_HELPER.length}")
        assertTrue(LuaConsole.MAX_BODY >= 140, "max body ${LuaConsole.MAX_BODY}")
    }

    @Test
    fun `full install on a fresh board`() = runTest {
        val node = board { files["old.lua"] = "x".toByteArray() }
        val steps = ArrayList<InstallStep>()
        val result = NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.FULL)) { steps += it.step }

        assertTrue(result.success, result.bootLog)
        assertNull(result.bootFailed)
        assertEquals(1, node.formats)
        assertEquals(
            setOf("config/runtime_config.lua", "config/device_config.lua", "fw.version", "init.lua"),
            node.files.keys,
        )
        assertEquals("firmware=v1.2.0-tls-lua53\nbundle=v1.2.0_fw-v1.2.0-tls-lua53\n", String(node.files["fw.version"]!!))
        assertEquals(InstallStep.entries.toSet(), steps.toSet())
        // init.lua is uploaded last
        val uploads = node.commands.filter { it == "recv()" }.size
        assertEquals(5, uploads) // lfs.img, runtime_config, device_config, fw.version, init.lua
    }

    @Test
    fun `full install keeps the portal Wi-Fi only when asked`() = runTest {
        val wifi = "return {\n  ssid = \"x\",\n}\n".toByteArray()
        val node = board { files[NodeMcuInstaller.WIFI_CONFIG] = wifi }
        NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.FULL, keepWifi = true))
        assertContentEquals(wifi, node.files[NodeMcuInstaller.WIFI_CONFIG])

        node.reset()
        NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.FULL, keepWifi = false))
        assertNull(node.files[NodeMcuInstaller.WIFI_CONFIG])
    }

    @Test
    fun `update removes stale files and keeps unrelated ones`() = runTest {
        val node = board()
        NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.FULL))
        node.files["config/wifi_config.lua"] = "return {}".toByteArray()
        node.files["config/device_config.lua.bak"] = "old".toByteArray()
        node.files["main.lc"] = "old".toByteArray()
        node.files["data.csv"] = "keep".toByteArray()
        node.reset()

        val result = NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.UPDATE))
        assertTrue(result.success)
        assertEquals(1, node.formats)
        assertFalse("config/wifi_config.lua" in node.files)
        assertFalse("config/device_config.lua.bak" in node.files)
        assertFalse("main.lc" in node.files)
        assertTrue("data.csv" in node.files)
    }

    @Test
    fun `update refuses a different firmware`() = runTest {
        val node = board { files["fw.version"] = "firmware=v1.1.0-tls-lua53\nbundle=x\n".toByteArray() }
        assertFailsWith<WrongFirmwareException> {
            NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.UPDATE))
        }
        // expert override
        val result = NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.UPDATE, ignoreFirmwareMismatch = true))
        assertTrue(result.success)
    }

    @Test
    fun `legacy firmware is rejected`() = runTest {
        val node = board { luaVersion = "Lua 5.1 false" }
        assertFailsWith<WrongFirmwareException> {
            NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.FULL))
        }
    }

    @Test
    fun `lfs reload that raises system restarting is success`() = runTest {
        // What the real board did: pcall catches "stdin:1: system restarting", then the node resets.
        val node = board { lfsReloadRaisesRestarting = true }
        val result = NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.FULL))
        assertTrue(result.success, result.bootLog)
        assertTrue("main" in node.lfsModules)
        assertFalse("lfs.img" in node.files)
    }

    @Test
    fun `lfs reload that restarts silently is success too`() = runTest {
        val node = board { lfsReloadRaisesRestarting = false }
        assertTrue(NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.FULL)).success)
    }

    @Test
    fun `lfs reload error is reported and a re-run recovers`() = runTest {
        val node = board { lfsReloadError = "Incorrect LFS header" }
        val e = assertFailsWith<LfsReloadException> {
            NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.FULL))
        }
        assertEquals("The code image does not match the firmware on the board. Run Full install.", e.userMessage)
        assertFalse("init.lua" in node.files)

        node.lfsReloadError = null
        val result = NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.FULL))
        assertTrue(result.success)
    }

    @Test
    fun `a broken device config is rejected before init lua is written`() = runTest {
        val node = board()
        assertFailsWith<com.progeo.luaflasher.core.DeviceConfigException> {
            NodeMcuInstaller(LuaConsole(node)).install(payload(config = "this is not lua"), InstallOptions(InstallMode.FULL))
        }
        assertFalse("init.lua" in node.files)
    }

    @Test
    fun `config only touches just the config`() = runTest {
        val node = board()
        NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.FULL))
        node.reset()
        val formats = node.formats
        val result = NodeMcuInstaller(LuaConsole(node)).install(
            payload(config = "return {\n  device = {\n    device_hash = \"new\",\n  },\n}\n"),
            InstallOptions(InstallMode.CONFIG_ONLY),
        )
        assertTrue(result.success)
        assertEquals(formats, node.formats)
        assertTrue("new" in String(node.files["config/device_config.lua"]!!))
        assertNotNull(node.files["init.lua"])
    }

    @Test
    fun `boot failure is detected`() = runTest {
        val node = board()
        NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.FULL))
        node.lfsModules = emptySet()
        node.reset()
        val result = NodeMcuInstaller(LuaConsole(node)).install(payload(), InstallOptions(InstallMode.CONFIG_ONLY))
        assertFalse(result.success)
        assertEquals(" module 'main' not found", result.bootFailed)
    }
}
