package com.progeo.luaflasher.flash

import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.bundle.DEVICE_CONFIG_PATH
import com.progeo.luaflasher.core.config.LuaJson
import com.progeo.luaflasher.core.config.LuaParseException
import com.progeo.luaflasher.core.nodemcu.LuaConsole
import com.progeo.luaflasher.core.nodemcu.NodeMcuFiles
import com.progeo.luaflasher.usb.UsbSerialManager
import kotlinx.serialization.json.JsonObject

class NoDeviceConfigException : FlasherException(
    "The board has no config/device_config.lua yet. Create a profile instead, or fetch it via the server.",
)

class BadDeviceConfigException(detail: String) : FlasherException(
    "The board's config/device_config.lua could not be read: $detail",
)

/**
 * Reads `config/device_config.lua` from the board on the USB adapter, over the NodeMCU Lua
 * console (the board runs normally: jumper removed, own power supply). No login needed.
 */
class BoardConfigReader(private val usb: UsbSerialManager, private val session: FlashSession) {

    suspend fun read(onStatus: (String) -> Unit = {}): JsonObject {
        if (session.isActive) throw FlasherException("A flash job holds the USB port. Finish or cancel it first.")
        onStatus("Connecting to the USB adapter…")
        val device = usb.firstDevice()
        usb.requestPermission(device)
        val port = usb.open(device)
        try {
            port.purge()
            val console = LuaConsole(port)
            onStatus("Waiting for NodeMCU… (press RST on the board if this takes long)")
            console.waitForPrompt(PROMPT_TIMEOUT_MS)
            val files = NodeMcuFiles(console)
            val size = files.list()[DEVICE_CONFIG_PATH] ?: throw NoDeviceConfigException()
            onStatus("Reading $DEVICE_CONFIG_PATH ($size bytes)…")
            val bytes = files.read(DEVICE_CONFIG_PATH, size) ?: throw NoDeviceConfigException()
            return try {
                LuaJson.parse(String(bytes, Charsets.UTF_8))
            } catch (e: LuaParseException) {
                throw BadDeviceConfigException(e.message ?: "syntax error")
            }
        } finally {
            port.close()
        }
    }

    companion object {
        private const val PROMPT_TIMEOUT_MS = 45_000L
    }
}
