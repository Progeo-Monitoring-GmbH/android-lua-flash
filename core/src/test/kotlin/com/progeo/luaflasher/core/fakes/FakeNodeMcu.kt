package com.progeo.luaflasher.core.fakes

import com.progeo.luaflasher.core.io.SerialPort
import com.progeo.luaflasher.core.nodemcu.FileUploader
import com.progeo.luaflasher.core.nodemcu.NodeMcuInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Simulates the NodeMCU Lua console: echo, prompt, the commands the installer sends,
 * the uploader's binary protocol, restarts and init.lua. Not a Lua interpreter: it knows
 * exactly the command shapes the app uses and fails loudly on anything else.
 */
class FakeNodeMcu(
    private val scope: CoroutineScope,
    var luaVersion: String = "Lua 5.3 true",
    /** Answer of node.LFS.reload; null = success (restart). */
    var lfsReloadError: String? = null,
    /** NAK the upload block with this index (0-based) once. */
    var nakBlock: Int? = null,
    /** Silent: no power or wrong mode. */
    var dead: Boolean = false,
    val bootDelayMs: Long = 300,
) : SerialPort {
    val files = LinkedHashMap<String, ByteArray>()
    var lfsModules: Set<String> = emptySet()
    val commands = ArrayList<String>()
    var restarts = 0
    var formats = 0

    private val output = Channel<ByteArray>(Channel.UNLIMITED)
    private val line = StringBuilder()
    private var booting = false
    private var recvDefined = false
    private var hexDefined = false
    private var defining = false

    private enum class Mode { REPL, NAME, BLOCKS }

    private var mode = Mode.REPL
    private var nameBuf = ByteArrayOutputStream()
    private var blockBuf = ByteArrayOutputStream()
    private var fileName = ""
    private var fileData = ByteArrayOutputStream()
    private var blockIndex = 0

    private fun emit(text: String) {
        output.trySend(text.toByteArray(Charsets.ISO_8859_1))
    }

    override suspend fun write(data: ByteArray) {
        if (dead || booting) return
        for (b in data) {
            when (mode) {
                Mode.REPL -> replByte(b)
                Mode.NAME -> nameByte(b)
                Mode.BLOCKS -> blockByte(b)
            }
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray =
        withTimeoutOrNull(timeoutMs) { output.receive() } ?: ByteArray(0)

    override suspend fun setBaudRate(baud: Int) = Unit
    override suspend fun setDtr(value: Boolean) = Unit
    override suspend fun setRts(value: Boolean) = Unit
    override suspend fun purge() {
        while (output.tryReceive().isSuccess) Unit
    }

    /** The user pressed RST. */
    fun reset() = boot()

    private fun replByte(b: Byte) {
        val c = (b.toInt() and 0xFF).toChar()
        if (c == '\r') return
        if (c != '\n') {
            line.append(c)
            return
        }
        val text = line.toString()
        line.setLength(0)
        emit(text + "\r\n")
        execute(text)
    }

    private fun prompt() = emit(if (defining) ">> " else "> ")

    private fun execute(text: String) {
        if (text.isNotEmpty()) commands += text
        when {
            text.isEmpty() -> prompt()
            defining -> {
                if (text == "end" && commands.getOrNull(commands.size - 2) == "w(0, 'C')") {
                    defining = false
                    recvDefined = true
                }
                prompt()
            }
            text == FileUploader.RECV_LUA.first() -> {
                defining = true
                prompt()
            }
            text == NodeMcuInstaller.HEX_HELPER -> {
                hexDefined = true
                prompt()
            }
            text == "recv()" -> {
                if (!recvDefined) {
                    emit("stdin:1: attempt to call a nil value (global 'recv')\r\n")
                    prompt()
                    return
                }
                emit("C")
                prompt()
                mode = Mode.NAME
                nameBuf.reset()
            }
            text == "node.restart()" -> {
                prompt()
                boot()
            }
            text.startsWith("local ok,e=pcall(function() ") -> {
                val m = WRAPPED.matchEntire(text) ?: error("fake: bad wrapper: $text")
                val (body, n) = m.destructured
                if (body.startsWith("syntax error")) {
                    emit("stdin:1: unexpected symbol near 'error'\r\n")
                    prompt()
                    return
                }
                val result = evaluate(body) ?: return // null: the node restarted
                result.printed.forEach { emit(it + "\r\n") }
                emit("#R$n\t${result.ok}\t${result.value}\r\n")
                prompt()
            }
            else -> error("fake: unsupported line: $text")
        }
    }

    private class Eval(val ok: Boolean, val value: String, val printed: List<String> = emptyList())

    private fun ok(value: String = "nil", printed: List<String> = emptyList()) = Eval(true, value, printed)

    private fun evaluate(body: String): Eval? {
        val removes = REMOVE.findAll(body).map { it.groupValues[2] }.toList()
        return when {
            body == "error('boom')" -> Eval(false, "stdin:1: boom")
            body == "return _VERSION..' '..tostring(node.LFS ~= nil)" -> ok(luaVersion)
            body.startsWith("local f=file.open('fw.version','r')") -> {
                val text = files["fw.version"]?.let { String(it) }.orEmpty()
                ok(Regex("firmware=([^\r\n]*)").find(text)?.groupValues?.get(1).orEmpty())
            }
            body == "for k,v in pairs(file.list()) do print('#F',k,v) end" ->
                ok(printed = files.map { (k, v) -> "#F\t$k\t${v.size}" })
            body == "file.format()" -> {
                files.clear()
                formats++
                ok()
            }
            body == "return type(recv)" -> ok(if (recvDefined) "function" else "nil")
            body.startsWith("return file.list()[") -> {
                val path = QUOTED.find(body)!!.groupValues[2]
                ok(files[path]?.size?.toString() ?: "nil")
            }
            body == "return node.LFS.reload('lfs.img')" -> {
                val error = lfsReloadError
                if (error != null) return Eval(true, error)
                val image = files["lfs.img"] ?: return Eval(true, "lfs.img not found")
                lfsModules = String(image, Charsets.ISO_8859_1).split(',').toSet()
                boot()
                null
            }
            body == "return node.LFS.get('main') ~= nil" -> ok(("main" in lfsModules).toString())
            body.startsWith("local f,err=loadfile(") -> {
                val path = QUOTED.find(body)!!.groupValues[2]
                val content = files[path]?.let { String(it) }
                when {
                    content == null -> ok("cannot open $path")
                    content.lines().any { it.startsWith("return {") } -> ok("table")
                    else -> ok("$path:1: syntax error")
                }
            }
            body.startsWith("return _hx(") -> {
                check(hexDefined) { "_hx not defined" }
                val path = QUOTED.find(body)!!.groupValues[2]
                ok(files[path]?.joinToString("") { "%02X".format(it) }.orEmpty())
            }
            removes.isNotEmpty() && body.replace(REMOVE, "").isBlank() -> {
                removes.forEach { files.remove(it) }
                ok()
            }
            else -> error("fake: unsupported command: $body")
        }
    }

    private fun boot() {
        restarts++
        booting = true
        mode = Mode.REPL
        line.setLength(0)
        recvDefined = false
        hexDefined = false
        defining = false
        scope.launch {
            delay(bootDelayMs)
            output.trySend(byteArrayOf(0x8F.toByte(), 0x00, 0xFE.toByte(), 0x13)) // ROM text at 74880 baud
            emit("\r\nNodeMCU 3.0.0.0 built with Docker\r\n\tbranch: release\r\n")
            booting = false
            emit("> ")
            if ("init.lua" in files) {
                delay(2_000)
                if ("main" in lfsModules) emit("BOOT heap: 31234\r\n") else emit("Boot failed: module 'main' not found\r\n")
            }
        }
    }

    private fun nameByte(b: Byte) {
        if (b.toInt() == 0) {
            fileName = String(nameBuf.toByteArray(), Charsets.ISO_8859_1)
            files.remove(fileName)
            fileData.reset()
            blockBuf.reset()
            blockIndex = 0
            mode = Mode.BLOCKS
            emit("\u0006")
        } else {
            nameBuf.write(b.toInt())
        }
    }

    private fun blockByte(b: Byte) {
        blockBuf.write(b.toInt())
        if (blockBuf.size() < 130) return
        val block = blockBuf.toByteArray()
        blockBuf.reset()
        val t = block[0].toInt()
        val l = block[1].toInt() and 0xFF
        if (t != 1 || (nakBlock == blockIndex)) {
            nakBlock = null
            files[fileName] = fileData.toByteArray()
            mode = Mode.REPL
            emit("\u0015")
            return
        }
        fileData.write(block, 2, l)
        blockIndex++
        if (l == 0) {
            files[fileName] = fileData.toByteArray()
            mode = Mode.REPL
        }
        emit("\u0006")
    }

    companion object {
        private val WRAPPED = Regex("local ok,e=pcall\\(function\\(\\) (.*) end\\) print\\('#R(\\d+)',ok,e\\)")
        private val REMOVE = Regex("file\\.remove\\((['\"])([^'\"]*)\\1\\)\\s*")
        private val QUOTED = Regex("(['\"])([^'\"]*)\\1")
    }
}
