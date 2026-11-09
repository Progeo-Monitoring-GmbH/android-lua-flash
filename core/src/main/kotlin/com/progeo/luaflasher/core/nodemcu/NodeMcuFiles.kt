package com.progeo.luaflasher.core.nodemcu

import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.config.LuaString.quote

class FileReadException(path: String, detail: String) : FlasherException(
    "Reading $path from the board failed. Retry; check the cable.",
    "read $path: $detail",
)

/**
 * Files on the board's SPIFFS through the Lua console. There is no encoder module on the
 * device, so content comes back as hex, [CHUNK] bytes per console answer.
 */
class NodeMcuFiles(private val console: LuaConsole) {
    private var helperDefined = false

    /** Remote files with their sizes. */
    suspend fun list(): Map<String, Long> {
        val output = console.exec("for k,v in pairs(file.list()) do print('#F',k,v) end").output
        return LIST_LINE.findAll(output).associate { it.groupValues[1] to it.groupValues[2].toLong() }
    }

    /** The content of [path], or null if the file does not exist. */
    suspend fun read(path: String, size: Long? = null): ByteArray? {
        val length = size ?: list()[path] ?: return null
        ensureHelper()
        val out = java.io.ByteArrayOutputStream(length.toInt())
        var offset = 0L
        while (offset < length) {
            val hex = console.exec("return _hx(${quote(path)},$offset,$CHUNK)").value
            if (hex.length % 2 != 0 || hex.any { it !in HEX_CHARS }) throw FileReadException(path, "bad answer at $offset")
            if (hex.isEmpty()) break
            for (i in hex.indices step 2) out.write(hex.substring(i, i + 2).toInt(16))
            offset += hex.length / 2
        }
        if (out.size().toLong() != length) throw FileReadException(path, "got ${out.size()} of $length bytes")
        return out.toByteArray()
    }

    private suspend fun ensureHelper() {
        if (helperDefined && console.exec("return type(_hx)").value == "function") return
        console.clear()
        console.sendLine(HEX_HELPER)
        console.awaitText(2_000) { it.endsWith("> ") }
        if (console.exec("return type(_hx)").value != "function") throw FileReadException("(helper)", "_hx not defined")
        helperDefined = true
    }

    companion object {
        const val CHUNK = 256

        private val LIST_LINE = Regex("(?m)^#F\t([^\t\r\n]+)\t(\\d+)\r?$")
        private const val HEX_CHARS = "0123456789ABCDEFabcdef"

        /** Defines _hx(path, offset, n): n bytes of the file from offset, as hex. */
        const val HEX_HELPER =
            "function _hx(p,o,n) local f=file.open(p,'r') if not f then return '' end f:seek('set',o) " +
                "local s=f:read(n) or '' f:close() return (s:gsub('.',function(c) return ('%02X'):format(c:byte()) end)) end"
    }
}
