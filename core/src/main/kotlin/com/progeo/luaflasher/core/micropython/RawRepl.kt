package com.progeo.luaflasher.core.micropython

import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.io.SerialPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class NoReplException : FlasherException(
    "The M5 does not answer on USB. Check the cable (data, not charge-only), wait until the M5 has started, then retry.",
)

class ReplTimeoutException(what: String) : FlasherException(
    "The M5 stopped answering. Check the cable, then retry; the update can be repeated safely.",
    "Timeout: $what",
)

class MicroPythonException(code: String, val error: String) : FlasherException(
    "The M5 reported an error: ${error.lines().lastOrNull { it.isNotBlank() }?.trim() ?: error}",
    "MicroPython error for ${code.take(120)}: $error",
)

/**
 * The MicroPython raw REPL (as mpremote/pyboard.py use it) on a [SerialPort]:
 *
 * - [enter]: Ctrl-C stops the running firmware (main.run() returns to the REPL), Ctrl-A switches
 *   to the raw REPL, which answers `raw REPL; CTRL-B to exit` and `>`;
 * - [exec]: code + Ctrl-D, the board answers `OK`, stdout, `\x04`, stderr, `\x04`, `>`;
 * - [exit]: Ctrl-B back to the normal REPL.
 *
 * Never soft-resets (no Ctrl-D on an empty input): the M5 firmware keeps its hardware watchdog fed
 * from a timer after Ctrl-C, and a soft reset would stop that timer, so the watchdog would reset
 * the board in the middle of an upload (m5-stack `mPy/device/watchdog.py`, upload.py `resume`).
 * Text on the wire is handled as ISO-8859-1, one char per byte.
 */
class RawRepl(
    private val port: SerialPort,
    private val onText: (String) -> Unit = {},
) {
    private val rx = StringBuilder()

    var inRawRepl = false
        private set

    private suspend fun pump(timeoutMs: Long): Boolean {
        val data = port.read(timeoutMs)
        if (data.isEmpty()) return false
        val text = String(data, Charsets.ISO_8859_1)
        rx.append(text)
        onText(text)
        return true
    }

    private suspend fun awaitText(timeoutMs: Long, predicate: (CharSequence) -> Boolean): Boolean {
        if (predicate(rx)) return true
        return withTimeoutOrNull(timeoutMs) {
            while (!predicate(rx)) pump(timeoutMs)
            true
        } ?: false
    }

    private suspend fun drain(ms: Long) {
        withTimeoutOrNull(ms) { while (true) pump(ms) }
    }

    private suspend fun send(text: String) = port.write(text.toByteArray(Charsets.ISO_8859_1))

    /** Stops the running program and enters the raw REPL; retries while the board is busy. */
    suspend fun enter(timeoutMs: Long = ENTER_TIMEOUT_MS) {
        val ok = withTimeoutOrNull(timeoutMs) {
            while (true) {
                send("\r\u0003")
                delay(INTERRUPT_GAP_MS)
                send("\u0003")
                drain(SETTLE_MS)
                rx.setLength(0)
                send("\r\u0001")
                if (awaitText(BANNER_WAIT_MS) { it.contains(BANNER) && it.endsWith(">") }) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
        if (!ok) throw NoReplException()
        rx.setLength(0)
        inRawRepl = true
    }

    /**
     * Runs [code] and returns what it printed. Throws [MicroPythonException] with the traceback if
     * it raised. The code goes out in small pieces with pauses: the raw REPL has no flow control.
     */
    suspend fun exec(code: String, timeoutMs: Long = EXEC_TIMEOUT_MS): String {
        check(inRawRepl) { "enter() first" }
        require(code.isNotBlank()) { "empty code would soft-reset the board" }
        rx.setLength(0)
        val bytes = code.toByteArray(Charsets.UTF_8)
        var off = 0
        while (off < bytes.size) {
            val n = minOf(CHUNK, bytes.size - off)
            port.write(bytes.copyOfRange(off, off + n))
            off += n
            if (off < bytes.size) delay(CHUNK_PAUSE_MS)
        }
        send("\u0004")
        if (!awaitText(OK_TIMEOUT_MS) { it.length >= 2 }) throw ReplTimeoutException("no OK for ${code.take(60)}")
        if (!rx.startsWith("OK")) {
            inRawRepl = false
            throw ReplTimeoutException("expected OK, got '${rx.take(80)}'")
        }
        // OK <stdout> \x04 <stderr> \x04 >
        val done = awaitText(timeoutMs) { t -> t.count { it == '\u0004' } >= 2 && t.endsWith(">") }
        if (!done) throw ReplTimeoutException("no result for ${code.take(60)}")
        val first = rx.indexOf("\u0004")
        val second = rx.indexOf("\u0004", first + 1)
        val stdout = latin1ToUtf8(rx.substring(2, first))
        val stderr = latin1ToUtf8(rx.substring(first + 1, second))
        rx.setLength(0)
        if (stderr.isNotBlank()) throw MicroPythonException(code, stderr.trim())
        return stdout
    }

    /** Sends [code] and returns once the board accepted it, without waiting for a result (reset). */
    suspend fun execNoWait(code: String) {
        check(inRawRepl) { "enter() first" }
        require(code.isNotBlank())
        rx.setLength(0)
        port.write(code.toByteArray(Charsets.UTF_8))
        send("\u0004")
        awaitText(OK_TIMEOUT_MS) { it.startsWith("OK") }
        inRawRepl = false
    }

    /** Back to the normal REPL (the firmware stays stopped until the next reset). */
    suspend fun exit() {
        if (!inRawRepl) return
        send("\r\u0002")
        drain(SETTLE_MS)
        inRawRepl = false
    }

    companion object {
        const val BANNER = "raw REPL; CTRL-B to exit"
        const val ENTER_TIMEOUT_MS = 30_000L
        const val EXEC_TIMEOUT_MS = 15_000L
        const val OK_TIMEOUT_MS = 5_000L
        const val INTERRUPT_GAP_MS = 100L
        const val SETTLE_MS = 400L
        const val BANNER_WAIT_MS = 1_500L
        const val CHUNK = 256
        const val CHUNK_PAUSE_MS = 10L

        private fun latin1ToUtf8(s: String) = String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
    }
}
