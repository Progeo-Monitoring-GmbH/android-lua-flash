package com.progeo.luaflasher.core.nodemcu

import com.progeo.luaflasher.core.LuaCommandException
import com.progeo.luaflasher.core.LuaTimeoutException
import com.progeo.luaflasher.core.NoPromptException
import com.progeo.luaflasher.core.io.SerialPort
import kotlinx.coroutines.withTimeoutOrNull

/** Result of [LuaConsole.exec]: the returned value as printed by Lua, and what was printed before it. */
data class LuaResult(val value: String, val output: String)

/**
 * The NodeMCU Lua console at 115200 baud (PLAN.md section 6). Text is handled as ISO-8859-1
 * so every byte maps to one char and binary bytes (ACK/NAK) survive.
 */
class LuaConsole(
    private val port: SerialPort,
    private val onText: (String) -> Unit = {},
) {
    private val rx = StringBuilder()
    private var seq = 0

    val buffer: CharSequence get() = rx

    fun clear() = rx.setLength(0)

    /** Reads once; true if something arrived. */
    private suspend fun pump(timeoutMs: Long): Boolean {
        val data = port.read(timeoutMs)
        if (data.isEmpty()) return false
        val text = String(data, Charsets.ISO_8859_1)
        rx.append(text)
        onText(text)
        return true
    }

    suspend fun writeRaw(bytes: ByteArray) = port.write(bytes)

    suspend fun sendLine(line: String) {
        require('\n' !in line) { "Console lines must not contain newlines" }
        require(line.length <= MAX_LINE) { "Console line too long (${line.length} > $MAX_LINE): $line" }
        port.write((line + "\n").toByteArray(Charsets.ISO_8859_1))
    }

    /** Reads until [predicate] holds for the received text; false on timeout. */
    suspend fun awaitText(timeoutMs: Long, predicate: (CharSequence) -> Boolean): Boolean {
        if (predicate(rx)) return true
        return withTimeoutOrNull(timeoutMs) {
            while (!predicate(rx)) pump(timeoutMs)
            true
        } ?: false
    }

    /** Reads and forwards everything for [durationMs]. */
    suspend fun drain(durationMs: Long) {
        withTimeoutOrNull(durationMs) { while (true) pump(durationMs) }
    }

    /**
     * Sends a newline every second until the input ends with the prompt `> `.
     * The first boot after a firmware write formats SPIFFS, which takes up to ~60 s.
     */
    suspend fun waitForPrompt(timeoutMs: Long = PROMPT_TIMEOUT_MS) {
        clear()
        val found = withTimeoutOrNull(timeoutMs) {
            var prompt = false
            while (!prompt) {
                port.write(byteArrayOf('\n'.code.toByte()))
                prompt = awaitText(PROMPT_POKE_MS) { it.endsWith("> ") }
            }
            true
        } ?: false
        if (!found) throw NoPromptException()
        clear()
    }

    /**
     * Runs [lua] (a function body, use `return x` for a value) wrapped in pcall, so its
     * result can be told apart from echo and log output. Throws [LuaCommandException] if
     * the code raised an error or did not compile.
     */
    suspend fun exec(lua: String, timeoutMs: Long = EXEC_TIMEOUT_MS, awaitPrompt: Boolean = true): LuaResult {
        val n = ++seq
        clear()
        sendLine(wrap(lua, n))
        val marker = Regex("(?m)^#R$n\t(true|false)\t([^\r\n]*)\r?\n")
        var match: MatchResult? = null
        var syntaxError: MatchResult? = null
        val done = awaitText(timeoutMs) { text ->
            match = marker.find(text)
            syntaxError = if (match == null) SYNTAX_ERROR.find(text) else null
            match != null || syntaxError != null
        }
        syntaxError?.let { throw LuaCommandException(lua, it.groupValues[1].trim()) }
        val m = match ?: if (done) error("unreachable") else throw LuaTimeoutException(lua)
        val output = rx.substring(0, m.range.first)
        if (awaitPrompt) awaitText(PROMPT_AFTER_EXEC_MS) { it.endsWith("> ") }
        val (ok, value) = m.destructured
        if (ok != "true") throw LuaCommandException(lua, value)
        return LuaResult(value, stripEcho(output))
    }

    /** Sends `node.restart()` and waits for the prompt of the new boot. */
    suspend fun restart(timeoutMs: Long = PROMPT_TIMEOUT_MS) {
        clear()
        sendLine("node.restart()")
        // The old interpreter still prints a prompt before the reset; let it pass.
        drain(RESTART_SETTLE_MS)
        waitForPrompt(timeoutMs)
    }

    companion object {
        const val MAX_LINE = 200
        const val PROMPT_TIMEOUT_MS = 90_000L
        const val PROMPT_POKE_MS = 1_000L
        const val EXEC_TIMEOUT_MS = 10_000L
        const val PROMPT_AFTER_EXEC_MS = 2_000L
        const val RESTART_SETTLE_MS = 1_500L

        private val SYNTAX_ERROR = Regex("(?m)^(?:lua: )?(stdin:\\d+:[^\r\n]*)\r?\n")

        fun wrap(lua: String, n: Int) = "local ok,e=pcall(function() $lua end) print('#R$n',ok,e)"

        /** Longest body that still fits a console line once wrapped (with a 4-digit sequence number). */
        val MAX_BODY = MAX_LINE - wrap("", 9999).length

        private fun stripEcho(output: String): String {
            val firstNewline = output.indexOf('\n')
            return if (output.startsWith("local ok,e=pcall") && firstNewline >= 0) output.substring(firstNewline + 1) else output
        }
    }
}
