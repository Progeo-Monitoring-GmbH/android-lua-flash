package com.progeo.luaflasher.core.fakes

import com.progeo.luaflasher.core.io.SerialPort
import com.progeo.luaflasher.core.io.sha256Hex
import com.progeo.luaflasher.core.micropython.MicroPythonFiles
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Simulates an M5 with UIFlow2 MicroPython over USB: the running firmware (stopped by Ctrl-C), the
 * normal and raw REPL, and exactly the calls [MicroPythonFiles] sends (`_pg_*` helpers, reset).
 * Not a Python interpreter: anything else fails loudly with a NameError on stderr.
 * Counts soft resets, which the app must never cause (the firmware's watchdog timer would stop).
 */
class FakeMicroPython(
    /** The ProGeo firmware runs (main.run()) and must be interrupted first. */
    var running: Boolean = true,
    /** Silent: wrong cable, no power. */
    var dead: Boolean = false,
    /** Ignore this many Ctrl-C before the firmware stops (busy). */
    var ignoreInterrupts: Int = 0,
    /** Fail the next write to a path ending with this (one time). */
    var failWriteOf: String? = null,
    /** Corrupt the next write to a path ending with this (one time): the check must catch it. */
    var corruptWriteOf: String? = null,
) : SerialPort {
    val files = LinkedHashMap<String, ByteArray>()
    val dirs = mutableSetOf("/flash")
    val executed = ArrayList<String>()
    var softResets = 0
    var hardResets = 0
    var helpersDefined = false

    private val output = Channel<ByteArray>(Channel.UNLIMITED)
    private var raw = false
    private val input = ByteArrayOutputStream()

    private fun emit(text: String) {
        output.trySend(text.toByteArray(Charsets.UTF_8))
    }

    init {
        if (running) emit("  12.3s Messung: 4 Sensoren\r\n")
    }

    fun putText(path: String, text: String) {
        files[path] = text.toByteArray()
        var dir = path.substringBeforeLast('/')
        while (dir.isNotEmpty()) {
            dirs += dir
            dir = dir.substringBeforeLast('/', "")
        }
    }

    fun text(path: String): String? = files[path]?.toString(Charsets.UTF_8)

    override suspend fun write(data: ByteArray) {
        if (dead) return
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (raw) rawByte(c) else normalByte(c)
        }
    }

    private fun normalByte(c: Int) {
        when (c) {
            0x03 -> if (running) {
                if (ignoreInterrupts > 0) {
                    ignoreInterrupts--
                } else {
                    running = false
                    emit("abgebrochen\r\nMicroPython v1.24.1 on 2026-01-01; M5STACK CoreS3 with ESP32S3\r\n>>> ")
                }
            } else {
                emit("\r\n>>> ")
            }
            0x01 -> if (!running) {
                raw = true
                input.reset()
                emit("raw REPL; CTRL-B to exit\r\n>")
            }
            0x04 -> if (!running) softReset()
            else -> Unit
        }
    }

    private fun rawByte(c: Int) {
        when (c) {
            0x04 -> {
                val code = input.toString(Charsets.UTF_8)
                input.reset()
                if (code.isEmpty()) softReset() else run(code)
            }
            0x02 -> {
                raw = false
                emit("\r\nMicroPython v1.24.1\r\n>>> ")
            }
            0x03 -> input.reset()
            0x01 -> {
                input.reset()
                emit("raw REPL; CTRL-B to exit\r\n>")
            }
            else -> input.write(c)
        }
    }

    private fun softReset() {
        softResets++
        helpersDefined = false
        emit("OK\r\nMPY: soft reboot\r\n")
    }

    private fun run(code: String) {
        executed += code
        if (code.trim() == "import machine\nmachine.reset()") {
            emit("OK")
            hardResets++
            helpersDefined = false
            raw = false
            running = true
            return
        }
        val (out, err) = try {
            execute(code) to ""
        } catch (e: PyError) {
            "" to "Traceback (most recent call last):\r\n  File \"<stdin>\", line 1, in <module>\r\n${e.message}\r\n"
        }
        emit("OK$out\u0004$err\u0004>")
    }

    private class PyError(message: String) : Exception(message)

    private fun need(cond: Boolean, error: String) {
        if (!cond) throw PyError(error)
    }

    private fun execute(code: String): String {
        if (code == MicroPythonFiles.HELPERS) {
            helpersDefined = true
            return ""
        }
        val call = Regex("""^(_pg_\w+)\((.*)\)$""", RegexOption.DOT_MATCHES_ALL).matchEntire(code.trim())
            ?: throw PyError("NameError: fake does not understand: ${code.take(80)}")
        need(helpersDefined, "NameError: name '${call.groupValues[1]}' isn't defined")
        val args = call.groupValues[2]
        val strings = Regex(""""((?:[^"\\]|\\.)*)"|'([^']*)'""").findAll(args).map { m ->
            m.groups[1]?.value?.let(::unescape) ?: m.groupValues[2]
        }.toList()
        return when (call.groupValues[1]) {
            "_pg_s" -> "${files[strings[0]]?.size ?: -1}\r\n"
            "_pg_md" -> {
                var q = ""
                for (s in strings[0].trim('/').split('/')) {
                    q += "/$s"
                    dirs += q
                }
                ""
            }
            "_pg_h" -> files[strings[0]]?.let { "${sha256Hex(it)} ${it.size}\r\n" } ?: "-\r\n"
            "_pg_w" -> {
                val path = strings[0]
                need(path.substringBeforeLast('/') in dirs, "OSError: [Errno 2] ENOENT")
                failWriteOf?.let {
                    if (path.endsWith(it)) {
                        failWriteOf = null
                        throw PyError("OSError: [Errno 28] ENOSPC")
                    }
                }
                var data = Base64.getDecoder().decode(strings[1])
                corruptWriteOf?.let {
                    if (path.endsWith(it) && data.isNotEmpty()) {
                        corruptWriteOf = null
                        data = data.copyOf().also { d -> d[0] = (d[0] + 1).toByte() }
                    }
                }
                val append = args.trim().endsWith(",1")
                files[path] = if (append) (files[path] ?: ByteArray(0)) + data else data
                ""
            }
            "_pg_r" -> {
                val nums = args.substringAfter("\",").split(',').map { it.trim().toInt() }
                val data = files[strings[0]] ?: throw PyError("OSError: [Errno 2] ENOENT")
                val end = minOf(data.size, nums[0] + nums[1])
                Base64.getEncoder().encodeToString(data.copyOfRange(minOf(nums[0], end), end)) + "\r\n"
            }
            "_pg_rm" -> {
                files.remove(strings[0])
                ""
            }
            "_pg_mvall" -> {
                strings.chunked(2).forEach { (a, b) ->
                    val data = files.remove(a) ?: throw PyError("OSError: [Errno 2] ENOENT")
                    files[b] = data
                }
                ""
            }
            else -> throw PyError("NameError: ${call.groupValues[1]}")
        }
    }

    private fun unescape(s: String) = s.replace(Regex("""\\(.)""")) { m ->
        when (val c = m.groupValues[1]) {
            "n" -> "\n"
            "t" -> "\t"
            else -> c
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
}
