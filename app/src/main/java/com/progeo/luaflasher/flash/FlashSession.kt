package com.progeo.luaflasher.flash

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.progeo.luaflasher.core.DeviceConfigException
import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.bundle.flashSegments
import com.progeo.luaflasher.core.bundle.installPayload
import com.progeo.luaflasher.core.config.ConfigTemplate
import com.progeo.luaflasher.core.config.DeviceConfigBuilder
import com.progeo.luaflasher.core.config.LuaParseException
import com.progeo.luaflasher.core.config.LuaTableParser
import com.progeo.luaflasher.core.esp.EspFlasher
import com.progeo.luaflasher.core.esp.FlashProgress
import com.progeo.luaflasher.core.esp.FlashSegment
import com.progeo.luaflasher.core.esp.RomLoader
import com.progeo.luaflasher.core.io.SerialPortClosedException
import com.progeo.luaflasher.core.nodemcu.InstallMode
import com.progeo.luaflasher.core.nodemcu.InstallOptions
import com.progeo.luaflasher.core.nodemcu.InstallPayload
import com.progeo.luaflasher.core.nodemcu.InstallProgress
import com.progeo.luaflasher.core.nodemcu.InstallResult
import com.progeo.luaflasher.core.nodemcu.LuaConsole
import com.progeo.luaflasher.core.nodemcu.NodeMcuInstaller
import com.progeo.luaflasher.data.BundleStore
import com.progeo.luaflasher.data.SettingsRepository
import com.progeo.luaflasher.usb.AndroidSerialPort
import com.progeo.luaflasher.usb.SerialDevice
import com.progeo.luaflasher.usb.UsbSerialManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** What to flash; chosen on the wizard's setup screen. */
data class FlashJob(
    val bundleId: String,
    /** The profile's config tree (template defaults fill the rest); empty without a profile. */
    val profileConfig: JsonObject,
    val deviceHash: String,
    val options: InstallOptions,
    /** Hand-edited device config (expert tab); null = generated. */
    val configOverride: String? = null,
)

enum class Phase { FIRMWARE, INSTALL }

sealed interface FlashState {
    data object Idle : FlashState

    /** "Set the boot jumper, press RST, tap Continue". */
    data object AwaitBootMode : FlashState
    data object Syncing : FlashState
    data class Writing(val chip: String, val progress: FlashProgress?) : FlashState

    /** "Remove the jumper, press RST, tap Continue". */
    data class AwaitNormalMode(val chip: String?) : FlashState
    data class Installing(val progress: InstallProgress) : FlashState
    data class Done(val result: InstallResult, val mode: InstallMode) : FlashState
    data class Failed(val message: String, val detail: String?, val resumeAt: Phase) : FlashState

    val isWorking: Boolean get() = this is Syncing || this is Writing || this is Installing
}

/**
 * The flash state machine (PLAN.md section 4, step 6). Owns the serial port for the whole job;
 * every step can be retried. Lives in the application, kept alive by [FlashService].
 */
class FlashSession(
    private val context: Context,
    private val store: BundleStore,
    private val usb: UsbSerialManager,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<FlashState>(FlashState.Idle)
    val state: StateFlow<FlashState> = _state.asStateFlow()

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()
    private val logBuffer = StringBuilder()

    private var job: FlashJob? = null
    private var mode: InstallMode = InstallMode.FULL
    private var segments: List<FlashSegment> = emptyList()
    private var payload: InstallPayload? = null
    private var device: SerialDevice? = null
    private var port: AndroidSerialPort? = null
    private var worker: Job? = null
    private var chip: String? = null

    val isActive: Boolean get() = _state.value != FlashState.Idle

    /** The bundle id of the running job, for the UI. */
    val bundleId: String? get() = job?.bundleId

    /**
     * Loads and re-checks the bundle, builds the device config and opens the port.
     * Throws [FlasherException] for anything the user must fix first.
     */
    suspend fun start(job: FlashJob, device: SerialDevice) {
        check(!_state.value.isWorking) { "a flash is running" }
        close()
        logBuffer.setLength(0)
        _log.value = ""
        val bundle = store.load(job.bundleId)
        val lua = job.configOverride ?: run {
            DeviceConfigBuilder.lua(
                ConfigTemplate.parse(bundle.template),
                job.profileConfig,
                job.deviceHash,
                header = "Generated by LUA Flasher for ${bundle.manifest.bundleId}",
            )
        }
        try {
            LuaTableParser.parse(lua)
        } catch (e: LuaParseException) {
            throw DeviceConfigException(e.message ?: "syntax error")
        }
        this.job = job
        this.mode = job.options.mode
        this.segments = bundle.flashSegments()
        this.payload = bundle.installPayload(lua)
        this.device = device
        this.chip = null
        usb.requestPermission(device)
        port = usb.open(device)
        log("Bundle ${bundle.manifest.bundleId}, mode $mode, adapter ${device.label}")
        _state.value = if (mode == InstallMode.FULL) FlashState.AwaitBootMode else FlashState.AwaitNormalMode(null)
        ContextCompat.startForegroundService(context, Intent(context, FlashService::class.java))
    }

    /** The user did what the current screen asked (jumper, RST) and tapped Continue. */
    fun proceed() {
        when (_state.value) {
            FlashState.AwaitBootMode -> launch(Phase.FIRMWARE) { firmware() }
            is FlashState.AwaitNormalMode -> launch(Phase.INSTALL) { install() }
            else -> Unit
        }
    }

    fun retry() {
        val failed = _state.value as? FlashState.Failed ?: return
        _state.value = if (failed.resumeAt == Phase.FIRMWARE) FlashState.AwaitBootMode else FlashState.AwaitNormalMode(chip)
    }

    /** Ends the job and releases the port. */
    fun cancel() {
        worker?.cancel()
        close()
        job = null
        payload = null
        segments = emptyList()
        _state.value = FlashState.Idle
    }

    private fun launch(phase: Phase, block: suspend () -> Unit) {
        worker = scope.launch {
            try {
                ensurePort()
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerialPortClosedException) {
                closePort()
                fail("USB connection lost. Plug the cable in again and tap Retry.", e.message, phase)
            } catch (e: FlasherException) {
                fail(e.userMessage, e.message, phase)
            } catch (e: Exception) {
                fail("Unexpected error: ${e.message ?: e.javaClass.simpleName}", e.stackTraceToString(), phase)
            }
        }
    }

    private fun fail(message: String, detail: String?, phase: Phase) {
        log("FAILED: $message${detail?.let { "\n  $it" } ?: ""}")
        _state.value = FlashState.Failed(message, detail, phase)
    }

    private suspend fun ensurePort() {
        if (port?.isOpen == true) return
        closePort()
        val d = usb.devices.value.firstOrNull { it.device.deviceName == device?.device?.deviceName } ?: usb.firstDevice()
        usb.requestPermission(d)
        device = d
        port = usb.open(d)
        log("Reopened ${d.label}")
    }

    private suspend fun firmware() {
        val port = port!!
        _state.value = FlashState.Syncing
        val loader = RomLoader(port) { log(it) }
        // Sync right after the user pressed RST (PLAN.md section 2).
        if (settings.current().autoReset) loader.classicReset()
        loader.purge()
        loader.sync()
        val info = loader.detectChip()
        chip = info.description
        log("Connected: ${info.description}")
        _state.value = FlashState.Writing(info.description, null)
        EspFlasher(loader) { log(it) }.write(segments) { progress ->
            _state.value = FlashState.Writing(info.description, progress)
        }
        log("Firmware written")
        _state.value = FlashState.AwaitNormalMode(info.description)
    }

    private suspend fun install() {
        val port = port!!
        val options = job!!.options
        port.purge()
        val console = LuaConsole(port) { appendRaw(it) }
        val installer = NodeMcuInstaller(console) { log(it) }
        val result = installer.install(payload!!, options) { progress ->
            _state.value = FlashState.Installing(progress)
        }
        log(if (result.success) "BOOT OK" else "Boot not confirmed${result.bootFailed?.let { ": $it" } ?: ""}")
        _state.value = FlashState.Done(result, mode)
        closePort()
    }

    fun log(line: String) {
        synchronized(logBuffer) {
            if (logBuffer.isNotEmpty() && logBuffer.last() != '\n') logBuffer.append('\n')
            logBuffer.append("## ").append(line).append('\n')
            trim()
            _log.value = logBuffer.toString()
        }
    }

    private fun appendRaw(text: String) {
        synchronized(logBuffer) {
            // Keep the console readable: show control bytes (ACK/NAK, ROM garbage) as dots.
            text.forEach { c -> logBuffer.append(if (c == '\n' || c == '\t' || c.code in 0x20..0x7E) c else if (c == '\r') "" else "·") }
            trim()
            _log.value = logBuffer.toString()
        }
    }

    private fun trim() {
        if (logBuffer.length > MAX_LOG) logBuffer.delete(0, logBuffer.length - MAX_LOG)
    }

    private fun closePort() {
        runCatching { port?.close() }
        port = null
    }

    private fun close() {
        worker?.cancel()
        closePort()
    }

    companion object {
        private const val MAX_LOG = 256 * 1024
    }
}
