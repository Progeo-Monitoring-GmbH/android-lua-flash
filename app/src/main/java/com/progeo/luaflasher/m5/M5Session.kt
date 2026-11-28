package com.progeo.luaflasher.m5

import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.m5.M5Config
import com.progeo.luaflasher.core.m5.M5DeviceInfo
import com.progeo.luaflasher.core.m5.M5Installer
import com.progeo.luaflasher.core.m5.M5Step
import com.progeo.luaflasher.flash.FlashSession
import com.progeo.luaflasher.usb.UsbSerialManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What to do with the connected M5. */
data class M5Job(
    /** Profile whose config.py is written; null = leave config.py as it is. */
    val profileId: String? = null,
    /** Release to install; null = keep the firmware. */
    val bundleId: String? = null,
    /** Also write /flash/main.py so the firmware starts at boot. */
    val autostart: Boolean = false,
    /** Write config.py even though the M5 has one for another device hash. */
    val overwriteOtherDevice: Boolean = false,
) {
    val readOnly: Boolean get() = profileId == null && bundleId == null
}

data class M5SessionState(
    val running: Boolean = false,
    val step: M5Step? = null,
    /** 0..1 while copying firmware. */
    val progress: Float? = null,
    val log: List<String> = emptyList(),
    /** What the M5 reported when the last job connected. */
    val device: M5DeviceInfo? = null,
    val done: String? = null,
    val error: String? = null,
    /** config.py on the M5 belongs to another device: ask before overwriting (hash on the M5, hash of the profile). */
    val otherDevice: Pair<String, String>? = null,
    /** Profile saved by the last job (read from the M5, or the backup of an overwritten config.py). */
    val savedProfileId: String? = null,
    /** The job that ran last, for Retry. */
    val lastJob: M5Job? = null,
)

/**
 * Runs one job at a time on the M5 at the USB port, in the app scope so it survives leaving the
 * screen. Owns the port for the whole job and never runs while a LUA flash job holds it (and vice
 * versa, via [isActive]). Always ends with a restart once connected, so the M5 goes back to work
 * even after a failed step (an interrupted firmware copy leaves the old firmware active).
 */
class M5Session(
    private val scope: CoroutineScope,
    private val usb: UsbSerialManager,
    private val flash: FlashSession,
    private val firmware: M5FirmwareStore,
    private val profiles: M5ProfileStore,
) {
    private val _state = MutableStateFlow(M5SessionState())
    val state: StateFlow<M5SessionState> = _state.asStateFlow()
    private var job: Job? = null

    val isActive: Boolean get() = _state.value.running

    private fun log(line: String) = _state.update { it.copy(log = (it.log + line).takeLast(MAX_LOG)) }

    fun clear() {
        if (!isActive) _state.value = M5SessionState()
    }

    fun start(request: M5Job) {
        if (isActive) return
        _state.value = M5SessionState(running = true, lastJob = request)
        job = scope.launch {
            try {
                run(request)
            } catch (e: CancellationException) {
                _state.update { it.copy(error = "Cancelled.") }
                throw e
            } catch (e: FlasherException) {
                log("Error: ${e.message}")
                _state.update { it.copy(error = e.userMessage) }
            } catch (e: Exception) {
                log("Error: $e")
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(running = false, step = null, progress = null) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun run(request: M5Job) {
        if (flash.isActive) throw FlasherException("A flash job holds the USB port. Finish or cancel it first.")
        val profile = request.profileId?.let { profiles.get(it) ?: throw FlasherException("The profile is gone. Scan the QR code again.") }
        // Open the release before touching the M5: a missing or damaged file fails here.
        val bundle = request.bundleId?.let { firmware.open(it) }

        val device = usb.firstDevice()
        log("USB: ${device.label}")
        usb.requestPermission(device)
        val port = usb.open(device)
        var connected = false
        var succeeded = false
        val installer = M5Installer(
            port,
            onStep = { s ->
                _state.update { it.copy(step = s) }
                log(s.label + "…")
            },
            onLog = ::log,
        )
        try {
            val info = installer.connect()
            connected = true
            _state.update { it.copy(device = info) }

            if (request.readOnly) {
                val text = info.configText ?: throw FlasherException("This M5 has no config.py yet. Scan its config QR code instead.")
                val config = try {
                    M5Config.parse(text)
                } catch (e: FlasherException) {
                    throw FlasherException("The M5's config.py could not be read: ${e.userMessage}")
                }
                val saved = profiles.saveConfig(config, M5Source.DEVICE)
                _state.update { it.copy(savedProfileId = saved.id, done = "Read config.py of ${saved.name}.") }
                succeeded = true
                return
            }

            if (profile != null) {
                val onDevice = info.deviceHash
                val wanted = profile.deviceHash
                if (onDevice != null && wanted != null && onDevice != wanted && !request.overwriteOtherDevice) {
                    _state.update { it.copy(otherDevice = onDevice to wanted) }
                    throw FlasherException("This M5 is configured as $onDevice, the profile is for $wanted. Nothing was changed.")
                }
            }

            if (bundle != null) {
                installer.installFirmware(bundle, request.autostart) { done, total ->
                    _state.update { it.copy(progress = done.toFloat() / total.coerceAtLeast(1)) }
                }
                _state.update { it.copy(progress = null) }
            }
            if (profile != null) {
                val previous = installer.writeConfig(profile.config)
                // Keep a different config.py that was overwritten as its own profile (it holds that device's token).
                previous?.let { text ->
                    runCatching { M5Config.parse(text) }.getOrNull()
                        ?.takeIf { it.deviceHash != null && it.deviceHash != profile.deviceHash && profiles.profiles.value.none { p -> p.name == it.deviceHash } }
                        ?.let { old ->
                            val saved = profiles.saveConfig(old, M5Source.DEVICE)
                            log("Previous config.py kept as profile ${saved.name}")
                        }
                }
            }
            val what = listOfNotNull(
                bundle?.let { "firmware ${it.manifest.firmware}" },
                profile?.let { "config.py for ${it.name}" },
            ).joinToString(" and ")
            _state.update { it.copy(done = "Done: $what. The M5 restarts and goes back to work.") }
            succeeded = true
        } finally {
            // Once stopped, the firmware only runs again after a reset: always restart, also after an error.
            if (connected) {
                runCatching { if (succeeded) installer.restart() else installer.recoverAndRestart() }
                    .onSuccess { log("Restarted.") }
                    .onFailure { log("Restart failed: ${it.message}. Switch the M5 off and on.") }
            }
            port.close()
        }
    }

    companion object {
        private const val MAX_LOG = 200
    }
}
