package com.progeo.luaflasher.core.m5

import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.io.SerialPort
import com.progeo.luaflasher.core.micropython.MicroPythonFiles
import com.progeo.luaflasher.core.micropython.RawRepl

class NotAnM5Exception(detail: String) : FlasherException(
    "This board does not look like an M5 with the ProGeo MicroPython firmware (UIFlow2 on the CoreS3 needed).",
    detail,
)

/** What is on the connected M5 (read in [M5Installer.connect]). */
data class M5DeviceInfo(
    /** `FW_VERSION` from `/flash/progeo/version.py`, null if the firmware is not installed. */
    val firmware: String?,
    /** `BUNDLE`, e.g. `m5-ain4-mpy-1.0.0` (derived from FW_VERSION as the firmware does). */
    val bundle: String?,
    /** config.py as stored on the device, null if there is none. */
    val configText: String?,
    /** The device hash in that config, null if missing or unreadable. */
    val deviceHash: String?,
    /** `/flash/main.py` starts the firmware at boot. */
    val autostart: Boolean,
)

enum class M5Step(val label: String) {
    CONNECT("Connecting to the M5"),
    COPY("Copying the firmware"),
    ACTIVATE("Switching to the new files"),
    AUTOSTART("Setting up the start at boot"),
    CONFIG("Writing config.py"),
    RESTART("Restarting the M5"),
}

/**
 * Services an M5 with the ProGeo MicroPython firmware over its USB console: reads what is installed,
 * copies a release ([M5Bundle]) and writes `config.py`. The firmware is stopped with Ctrl-C for
 * this and starts again after [restart] (hard reset; the watchdog keeps running until then).
 *
 * A firmware update copies every changed file as `<path>.new`, checks each on the board, and only
 * then renames them all in one call, so an interrupted copy leaves the old firmware working.
 * Files already identical on the board are skipped, so a retry continues where it stopped.
 * `config.py` is never part of a release and stays as it is unless written explicitly.
 */
class M5Installer(
    port: SerialPort,
    private val onStep: (M5Step) -> Unit = {},
    private val onLog: (String) -> Unit = {},
    onText: (String) -> Unit = {},
) {
    private val repl = RawRepl(port, onText)
    private val files = MicroPythonFiles(repl)

    suspend fun connect(): M5DeviceInfo {
        onStep(M5Step.CONNECT)
        repl.enter()
        try {
            files.init()
        } catch (e: FlasherException) {
            throw NotAnM5Exception("helpers: ${e.message}")
        }
        val versionText = files.read(VERSION_PATH)?.toString(Charsets.UTF_8)
        val firmware = versionText?.let { FW_VERSION.find(it)?.groupValues?.get(1) }
        val configText = files.read(M5Config.DEVICE_PATH)?.toString(Charsets.UTF_8)
        val deviceHash = configText?.let { runCatching { M5Config.parse(it).deviceHash }.getOrNull() }
        val autostart = files.read(AUTOSTART_PATH)?.toString(Charsets.UTF_8)?.contains("import main") == true
        val info = M5DeviceInfo(firmware, firmware?.let { BUNDLE_PREFIX + it }, configText, deviceHash, autostart)
        onLog(
            "M5: firmware ${firmware ?: "not installed"}, config.py " +
                (if (configText == null) "missing" else "for ${deviceHash ?: "unknown device"}") +
                ", autostart ${if (autostart) "on" else "off"}",
        )
        return info
    }

    /**
     * Copies [bundle] to the board. [autostart]: also write `/flash/main.py` so the firmware starts
     * at boot (needed once on a fresh UIFlow2). [onProgress]: bytes done, bytes total.
     */
    suspend fun installFirmware(bundle: M5Bundle, autostart: Boolean, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val manifest = bundle.manifest
        val wanted = manifest.files + listOfNotNull(manifest.autostart?.takeIf { autostart })
        onStep(M5Step.COPY)
        for (dir in wanted.map { it.path.substringBeforeLast('/') }.toSortedSet()) files.mkdirs(dir)

        val total = wanted.sumOf { it.size }
        var done = 0L
        val moves = ArrayList<Pair<String, String>>()
        for (file in wanted) {
            val onBoard = files.sha256(file.path)
            if (onBoard != null && onBoard.first.equals(file.sha256, ignoreCase = true) && onBoard.second == file.size) {
                done += file.size
                onProgress(done, total)
                continue
            }
            val staged = file.path + STAGED
            files.write(staged, bundle.bytes(file)) { n ->
                done += n
                onProgress(done, total)
            }
            moves += staged to file.path
        }
        onLog("${moves.size} of ${wanted.size} files changed (${manifest.bundleId})")
        if (moves.isNotEmpty()) {
            onStep(if (moves.any { it.second == AUTOSTART_PATH } && moves.size == 1) M5Step.AUTOSTART else M5Step.ACTIVATE)
            files.moveAll(moves)
        }
        val version = files.read(VERSION_PATH)?.toString(Charsets.UTF_8)?.let { FW_VERSION.find(it)?.groupValues?.get(1) }
        if (version != manifest.firmware) throw NotAnM5Exception("after the update version.py says $version, expected ${manifest.firmware}")
    }

    /** Writes [config] as `config.py` (checked, then renamed into place). Returns the previous file, if any. */
    suspend fun writeConfig(config: M5Config): String? {
        val problems = config.problems()
        if (problems.isNotEmpty()) throw M5ConfigException("The M5 config is incomplete: ${problems.joinToString("; ")}")
        onStep(M5Step.CONFIG)
        val previous = files.read(M5Config.DEVICE_PATH)?.toString(Charsets.UTF_8)
        files.mkdirs(M5Config.DEVICE_PATH.substringBeforeLast('/'))
        val staged = M5Config.DEVICE_PATH + STAGED
        files.write(staged, config.render().toByteArray(Charsets.UTF_8))
        files.moveAll(listOf(staged to M5Config.DEVICE_PATH))
        onLog("config.py written for ${config.deviceHash}")
        return previous
    }

    /** Hard reset: the firmware starts again (with autostart) and the watchdog timer ends. */
    suspend fun restart() {
        onStep(M5Step.RESTART)
        if (!repl.inRawRepl) repl.enter(RECOVER_TIMEOUT_MS)
        repl.execNoWait("import machine\nmachine.reset()")
    }

    /** [restart] after a failed step: the REPL may be mid-answer, so it is entered again first. */
    suspend fun recoverAndRestart() {
        repl.enter(RECOVER_TIMEOUT_MS)
        restart()
    }

    companion object {
        const val VERSION_PATH = "/flash/progeo/version.py"
        const val AUTOSTART_PATH = "/flash/main.py"
        const val BUNDLE_PREFIX = "m5-ain4-mpy-"
        const val RECOVER_TIMEOUT_MS = 10_000L
        private const val STAGED = ".new"
        private val FW_VERSION = Regex("""(?m)^FW_VERSION\s*=\s*["']([^"']+)["']""")
    }
}
