package com.progeo.luaflasher.core.nodemcu

import com.progeo.luaflasher.core.DeviceConfigException
import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.LfsReloadException
import com.progeo.luaflasher.core.LuaCommandException
import com.progeo.luaflasher.core.LuaTimeoutException
import com.progeo.luaflasher.core.WrongFirmwareException
import com.progeo.luaflasher.core.config.LuaString.quote

enum class InstallMode {
    /** Firmware + files, filesystem formatted. */
    FULL,

    /** Files only; the firmware on the board must match the bundle. */
    UPDATE,

    /** Only config/device_config.lua (plus runtime_config.lua from the bundle). */
    CONFIG_ONLY,
}

class RemoteFile(val path: String, val content: ByteArray)

/** What phase B writes; everything comes from one verified bundle plus the generated device config. */
class InstallPayload(
    val bundleId: String,
    val firmwareVersion: String,
    val lfsImage: ByteArray,
    val lfsModules: List<String>,
    /** Plain SPIFFS files from the bundle, e.g. config/runtime_config.lua. */
    val files: List<RemoteFile>,
    val initLua: RemoteFile,
    val deviceConfig: RemoteFile,
)

data class InstallOptions(
    val mode: InstallMode,
    /** Keep config/wifi_config.lua written by the setup portal. */
    val keepWifi: Boolean = false,
    /** Allow UPDATE although fw.version does not match the bundle. */
    val ignoreFirmwareMismatch: Boolean = false,
    val bootWatchMs: Long = 20_000,
)

enum class InstallStep(val label: String) {
    CHECK("Checking the board"),
    CLEAN("Cleaning the filesystem"),
    RESTART_IDLE("Restarting without the app"),
    UPLOAD_LFS("Uploading the code image"),
    LOAD_LFS("Loading the code image"),
    UPLOAD_CONFIG("Uploading the configuration"),
    WRITE_VERSION("Writing fw.version"),
    UPLOAD_INIT("Uploading init.lua"),
    BOOT_WATCH("Watching the first boot"),
}

data class InstallProgress(val step: InstallStep, val fraction: Float, val detail: String = "")

data class InstallResult(val bootLog: String, val success: Boolean, val bootFailed: String?)

/**
 * Phase B (PLAN.md section 6): mirrors scripts/flash_nodemcu.ps1. Every step can be
 * repeated, so re-running after any failure recovers the board.
 */
class NodeMcuInstaller(
    private val console: LuaConsole,
    private val log: (String) -> Unit = {},
) {
    private val uploader = FileUploader(console)
    private val files = NodeMcuFiles(console)

    suspend fun install(
        payload: InstallPayload,
        options: InstallOptions,
        onProgress: (InstallProgress) -> Unit = {},
    ): InstallResult {
        fun step(s: InstallStep, f: Float = 0f, d: String = "") = onProgress(InstallProgress(s, f, d)).also {
            if (f == 0f) log("${s.label}${if (d.isNotEmpty()) ": $d" else ""}")
        }

        // 1. Prompt and firmware check.
        step(InstallStep.CHECK)
        console.waitForPrompt()
        val version = console.exec("return _VERSION..' '..tostring(node.LFS ~= nil)").value
        if (version != "Lua 5.3 true") throw WrongFirmwareException(version)
        if (options.mode == InstallMode.UPDATE && !options.ignoreFirmwareMismatch) {
            val onBoard = readFirmwareVersion()
            if (onBoard != payload.firmwareVersion) {
                throw WrongFirmwareException("fw.version firmware=${onBoard.ifEmpty { "(none)" }}, bundle needs ${payload.firmwareVersion}")
            }
        }

        if (options.mode == InstallMode.CONFIG_ONLY) {
            if (!options.keepWifi) removeIfPresent(listOf(WIFI_CONFIG), existingFiles())
            uploadConfig(listOf(payload.deviceConfig) + payload.files, onProgress)
            return restartAndWatch(options, onProgress)
        }

        // 2. Clean.
        step(InstallStep.CLEAN)
        val existing = existingFiles()
        val keptWifi = if (options.keepWifi && WIFI_CONFIG in existing) files.read(WIFI_CONFIG, existing.getValue(WIFI_CONFIG)) else null
        if (options.mode == InstallMode.FULL) {
            console.exec("file.format()", timeoutMs = FORMAT_TIMEOUT_MS)
        } else {
            val bundlePaths = (payload.files + payload.deviceConfig).map { it.path }.toSet()
            val stale = existing.keys.filter { path ->
                path == "init.lua" || path == "init.lc" || path == LFS_IMAGE ||
                    (path.startsWith("config/") && path !in bundlePaths && !(options.keepWifi && path == WIFI_CONFIG)) ||
                    payload.lfsModules.any { path == "$it.lua" || path == "$it.lc" }
            }
            removeIfPresent(stale, existing)
        }

        // 3. Restart without init.lua: nothing competes for heap or UART.
        step(InstallStep.RESTART_IDLE)
        console.exec("file.remove('init.lua') file.remove('init.lc')")
        console.restart()

        // 4. LFS image.
        step(InstallStep.UPLOAD_LFS, 0f, "${payload.lfsImage.size} bytes")
        uploader.upload(LFS_IMAGE, payload.lfsImage) { sent, total ->
            onProgress(InstallProgress(InstallStep.UPLOAD_LFS, sent.toFloat() / total.coerceAtLeast(1)))
        }
        step(InstallStep.LOAD_LFS)
        reloadLfs()

        // 5. Check and remove the image file.
        val hasMain = console.exec("return node.LFS.get('main') ~= nil").value
        if (hasMain != "true") throw LfsReloadException("node.LFS.get('main') is nil after reload")
        console.exec("file.remove('$LFS_IMAGE')")

        // 6. Configuration.
        val configFiles = buildList {
            addAll(payload.files)
            add(payload.deviceConfig)
            if (keptWifi != null && options.mode == InstallMode.FULL) add(RemoteFile(WIFI_CONFIG, keptWifi))
        }
        uploadConfig(configFiles, onProgress)

        // 7. fw.version.
        step(InstallStep.WRITE_VERSION)
        val fwVersion = "firmware=${payload.firmwareVersion}\nbundle=${payload.bundleId}\n"
        uploader.upload(FW_VERSION, fwVersion.toByteArray(Charsets.UTF_8))

        // 8. init.lua last, then restart.
        step(InstallStep.UPLOAD_INIT)
        uploader.upload(payload.initLua.path, payload.initLua.content)
        return restartAndWatch(options, onProgress)
    }

    /** `firmware=` of fw.version on the board, or "" if there is none. */
    suspend fun readFirmwareVersion(): String =
        console.exec(
            "local f=file.open('$FW_VERSION','r') if not f then return '' end local s=f:read(200) or '' f:close() " +
                "return s:match('firmware=([^\\r\\n]*)') or ''",
        ).value

    /** Remote files with their sizes. */
    suspend fun existingFiles(): Map<String, Long> = files.list()

    private suspend fun removeIfPresent(paths: List<String>, existing: Map<String, Long>) {
        for (path in paths) {
            if (path in existing) {
                log("removing $path")
                console.exec("file.remove(${quote(path)})")
            }
        }
    }


    /**
     * `node.LFS.reload` either restarts the node (success) or returns why it refused the image
     * (firmware/image mismatch). On success the Lua 5.3 firmware first raises the error
     * "system restarting", which pcall catches; a silent restart (no result before the
     * timeout) is success as well. Whether the modules arrived is checked afterwards.
     */
    private suspend fun reloadLfs() {
        val outcome: String? = try {
            console.exec("return node.LFS.reload('$LFS_IMAGE')", timeoutMs = LFS_RELOAD_RESULT_MS, awaitPrompt = false).value
        } catch (e: LuaCommandException) {
            e.luaError
        } catch (e: LuaTimeoutException) {
            null
        }
        if (outcome != null && !RESTARTING.containsMatchIn(outcome)) throw LfsReloadException(outcome)
        log("LFS image accepted, node restarting")
        // The old interpreter may still print a prompt before the reset; let the restart happen.
        console.drain(LFS_RESTART_SETTLE_MS)
        console.waitForPrompt()
    }

    private suspend fun uploadConfig(configFiles: List<RemoteFile>, onProgress: (InstallProgress) -> Unit) {
        configFiles.forEachIndexed { index, file ->
            onProgress(InstallProgress(InstallStep.UPLOAD_CONFIG, index.toFloat() / configFiles.size, file.path))
            log("uploading ${file.path}")
            uploader.upload(file.path, file.content)
            if (file.path.startsWith("config/") && file.path.endsWith(".lua")) {
                val type = console.exec(
                    "local f,err=loadfile(${quote(file.path)}) if not f then return err end return type(f())",
                ).value
                if (type != "table") throw DeviceConfigException("${file.path}: $type")
            }
        }
    }

    private suspend fun restartAndWatch(options: InstallOptions, onProgress: (InstallProgress) -> Unit): InstallResult {
        onProgress(InstallProgress(InstallStep.BOOT_WATCH, 0f))
        log(InstallStep.BOOT_WATCH.label)
        console.clear()
        console.sendLine("node.restart()")
        console.drain(options.bootWatchMs)
        val bootLog = console.buffer.toString()
        val failed = BOOT_FAILED.find(bootLog)?.groupValues?.get(1)
        val success = failed == null && "BOOT heap:" in bootLog
        onProgress(InstallProgress(InstallStep.BOOT_WATCH, 1f))
        return InstallResult(bootLog, success, failed)
    }

    companion object {
        const val LFS_IMAGE = "lfs.img"
        const val FW_VERSION = "fw.version"
        const val WIFI_CONFIG = "config/wifi_config.lua"
        const val FORMAT_TIMEOUT_MS = 180_000L
        const val LFS_RELOAD_RESULT_MS = 8_000L
        const val LFS_RESTART_SETTLE_MS = 3_000L

        /** What node.LFS.reload raises when it accepted the image and restarts the node. */
        private val RESTARTING = Regex("system restarting", RegexOption.IGNORE_CASE)
        private val BOOT_FAILED = Regex("Boot failed:([^\r\n]*)")
    }
}
