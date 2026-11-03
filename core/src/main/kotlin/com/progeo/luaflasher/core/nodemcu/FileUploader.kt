package com.progeo.luaflasher.core.nodemcu

import com.progeo.luaflasher.core.UploadException
import com.progeo.luaflasher.core.config.LuaString

/**
 * nodemcu-uploader's binary transfer (luacode.py RECV_LUA): the node receives the
 * file name terminated by NUL, then 130-byte blocks `0x01, len, data, zero padding`,
 * acknowledging each with ACK (0x06) or NAK (0x15). A block with len 0 ends the file.
 */
class FileUploader(private val console: LuaConsole) {

    suspend fun upload(path: String, content: ByteArray, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }) {
        require(path.isNotEmpty() && '\u0000' !in path && '\n' !in path) { "Invalid remote path: $path" }
        ensureRecv()

        console.clear()
        console.sendLine("recv()")
        if (!console.awaitText(START_TIMEOUT_MS) { READY.containsMatchIn(it) }) {
            throw UploadException(path, "device did not start receiving: ${console.buffer.takeLast(80)}")
        }

        console.clear()
        console.writeRaw(path.toByteArray(Charsets.ISO_8859_1) + 0)
        awaitAck(path, "file name")

        var pos = 0
        onProgress(0, content.size.toLong())
        while (pos < content.size) {
            val len = minOf(CHUNK, content.size - pos)
            sendBlock(content, pos, len)
            awaitAck(path, "block at $pos")
            pos += len
            onProgress(pos.toLong(), content.size.toLong())
        }
        sendBlock(content, 0, 0)
        awaitAck(path, "end block")

        val size = console.exec("return file.list()[${LuaString.quote(path)}]").value
        if (size != content.size.toString()) {
            throw UploadException(path, "size on device is $size, expected ${content.size}")
        }
    }

    /** Defines `recv()` unless it is already there (it is lost on every restart). */
    private suspend fun ensureRecv() {
        if (console.exec("return type(recv)").value == "function") return
        console.clear()
        for (line in RECV_LUA) {
            console.clear()
            console.sendLine(line)
            if (!console.awaitText(LINE_TIMEOUT_MS) { it.endsWith("> ") }) {
                throw UploadException("recv()", "no prompt while defining the upload helper")
            }
        }
        val type = console.exec("return type(recv)").value
        if (type != "function") throw UploadException("recv()", "upload helper not defined (type: $type)")
    }

    private suspend fun sendBlock(content: ByteArray, from: Int, len: Int) {
        val block = ByteArray(BLOCK)
        block[0] = 0x01
        block[1] = len.toByte()
        content.copyInto(block, 2, from, from + len)
        console.clear()
        console.writeRaw(block)
    }

    private suspend fun awaitAck(path: String, what: String) {
        val answered = console.awaitText(ACK_TIMEOUT_MS) { ACK in it || NAK in it }
        if (!answered) throw UploadException(path, "no ACK for $what")
        if (NAK in console.buffer) throw UploadException(path, "NAK for $what")
    }

    companion object {
        const val CHUNK = 128
        const val BLOCK = CHUNK + 2
        const val ACK = '\u0006'
        const val NAK = '\u0015'
        const val ACK_TIMEOUT_MS = 3_000L
        const val START_TIMEOUT_MS = 5_000L
        const val LINE_TIMEOUT_MS = 3_000L

        /** `recv()` writes 'C', then the interpreter prints its prompt. */
        private val READY = Regex("C\\s*> $")

        /** luacode.py RECV_LUA, one console line each (all well under 200 chars). */
        val RECV_LUA = listOf(
            "function recv()",
            """local on,w,ack,nack=uart.on,uart.write,'\6','\21'""",
            "local fd",
            "local function recv_block(d)",
            "local t,l = d:byte(1,2)",
            "if t ~= 1 then w(0, nack); fd:close(); return on('data') end",
            "if l >= 0 then fd:write(d:sub(3, l+2)) end",
            "if l == 0 then fd:close(); w(0, ack); return on('data') else w(0, ack) end",
            "end",
            "local function recv_name(d) d = d:gsub('%z.*', '') file.remove(d) fd=file.open(d, 'w') on('data', 130, recv_block, 0) w(0, ack) end",
            """on('data', '\0', recv_name, 0)""",
            "w(0, 'C')",
            "end",
        )
    }
}
