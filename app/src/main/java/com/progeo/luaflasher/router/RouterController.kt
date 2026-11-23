package com.progeo.luaflasher.router

import android.content.Context
import android.os.Build
import com.progeo.core.UserFacingException
import com.progeo.core.security.SecretCipher
import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.config.LuaJson
import com.progeo.luaflasher.data.ProfileStore
import com.progeo.luaflasher.data.SettingsRepository
import com.progeo.teltonika.api.DeviceInfo
import com.progeo.teltonika.api.DhcpLease
import com.progeo.teltonika.api.InterfaceStatus
import com.progeo.teltonika.api.ModemStatus
import com.progeo.teltonika.api.RouterApi
import com.progeo.teltonika.api.RouterApiException
import com.progeo.teltonika.api.RouterException
import com.progeo.teltonika.api.RouterLoginException
import com.progeo.teltonika.api.RouterUnreachableException
import com.progeo.teltonika.api.RutosClient
import com.progeo.teltonika.api.Signal
import com.progeo.teltonika.config.ApplyResult
import com.progeo.teltonika.config.ConfigPlan
import com.progeo.teltonika.config.RouterTemplate
import com.progeo.teltonika.config.TemplateEngine
import com.progeo.teltonika.credentials.Identity
import com.progeo.teltonika.credentials.LoginCandidate
import com.progeo.teltonika.credentials.LoginOrder
import com.progeo.teltonika.credentials.Passwords
import com.progeo.teltonika.firmware.FirmwareCheck
import com.progeo.teltonika.firmware.FirmwareImage
import com.progeo.teltonika.firmware.FirmwarePolicy
import com.progeo.teltonika.firmware.FirmwareVersion
import com.progeo.teltonika.label.Credentials
import com.progeo.teltonika.label.RouterLabel
import com.progeo.teltonika.tls.CertificateChangedException
import com.progeo.teltonika.tls.PinningTrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.File
import java.time.OffsetDateTime

enum class Via { WIFI, CABLE }

enum class Phase { IDLE, CONNECTING, CONNECTED, FAILED }

/** The setup checklist (DRAFT.md section 3), in order. */
enum class SetupStep(val title: String) {
    BACKUP("Backup"),
    FIRMWARE("Firmware"),
    TEMPLATE("Configuration"),
    PASSWORD("Own password"),
    MOBILE("Mobile check"),
    REPORT("Report"),
}

data class StepLog(val text: String, val ok: Boolean? = null, val detail: String? = null)

data class RouterUi(
    val serial: String? = null,
    val label: RouterLabel? = null,
    val via: Via = Via.WIFI,
    val phase: Phase = Phase.IDLE,
    val log: List<StepLog> = emptyList(),
    /** The last failure, shown until the next action. */
    val error: String? = null,
    /** No stored password works: ask for one. */
    val needsPassword: Boolean = false,
    val message: String? = null,
    /** The running operation. */
    val busy: String? = null,
    val progress: Float? = null,
    val loginKind: LoginCandidate.Kind? = null,
    val device: DeviceInfo? = null,
    val modems: List<ModemStatus> = emptyList(),
    val interfaces: List<InterfaceStatus> = emptyList(),
    val leases: List<DhcpLease> = emptyList(),
    val statusErrors: List<String> = emptyList(),
    val signalHistory: List<Signal> = emptyList(),
    val templates: List<RouterTemplate> = emptyList(),
    val plan: ConfigPlan? = null,
    val applyResult: ApplyResult? = null,
    val firmwareImage: FirmwareImage? = null,
    val firmwareCheck: FirmwareCheck? = null,
    val done: Set<SetupStep> = emptySet(),
)

/**
 * One router session at a time: connect (look up → network → certificate → login → identity),
 * show its status, and run the setup steps. App-scoped like [com.progeo.luaflasher.flash.FlashSession],
 * so it survives navigation; [RouterService] keeps the process alive while a session is open.
 */
class RouterController(
    private val context: Context,
    private val scope: CoroutineScope,
    val store: RouterStore,
    val sync: RouterSync,
    private val backend: RouterBackend,
    val firmware: RouterFirmwareStore,
    private val settings: SettingsRepository,
    private val profiles: ProfileStore,
    private val http: OkHttpClient,
    private val templateCache: File,
    private val cipher: SecretCipher,
    private val backupDir: File,
) {
    private val network = RouterNetwork(context)
    private val _state = MutableStateFlow(RouterUi())
    val state: StateFlow<RouterUi> = _state.asStateFlow()

    private var api: RouterApi? = null
    private var job: Job? = null
    private var antennaJob: Job? = null

    val isOpen: Boolean get() = _state.value.phase != Phase.IDLE

    private val serial: String get() = _state.value.serial ?: throw RouterException("No router open.")
    private val record: RouterRecord get() = store.get(serial) ?: throw RouterException("The router's record is missing.")
    private val routerApi: RouterApi get() = api ?: throw RouterException("Not connected to the router.")

    // --- session ---

    /** Opens the router from a scanned label (any of the three codes). */
    fun open(label: RouterLabel, via: Via) = start(RouterUi(serial = label.serial, label = label, via = via))

    /** Opens a router the phone already knows. */
    fun openKnown(serial: String, via: Via) = start(RouterUi(serial = serial, via = via))

    /** An operation runs (e.g. a firmware installation): the session must not be replaced or closed. */
    val isBusy: Boolean get() = job?.isActive == true

    private fun start(initial: RouterUi) {
        if (isBusy && _state.value.phase != Phase.IDLE) {
            // Never cut off a running operation; the screen shows the open session instead.
            _state.update { it.copy(message = "Finish or wait for the open router (${it.serial}) first.") }
            return
        }
        stopJobs()
        api = null
        _state.value = initial.copy(phase = Phase.CONNECTING)
        RouterService.start(context)
        launchOp("Connecting") { connect(manual = null) }
    }

    fun retry(via: Via = _state.value.via) {
        _state.update { it.copy(via = via, phase = Phase.CONNECTING, needsPassword = false) }
        launchOp("Connecting") { connect(manual = null) }
    }

    /** Tries a typed password (when none of the stored ones works). */
    fun submitPassword(password: String) {
        _state.update { it.copy(phase = Phase.CONNECTING, needsPassword = false) }
        launchOp("Connecting") { connect(manual = password) }
    }

    fun close() {
        stopJobs()
        val old = api
        api = null
        scope.launch { runCatching { old?.client?.logout() } }
        network.release()
        _state.value = RouterUi()
    }

    private fun stopJobs() {
        antennaJob?.cancel()
        antennaJob = null
        job?.cancel()
        job = null
    }

    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }

    // --- connecting ---

    private suspend fun connect(manual: String?) {
        _state.update { it.copy(phase = Phase.CONNECTING, log = emptyList(), error = null) }
        val label = _state.value.label
        val serial = serial

        step("Looking up router $serial") {
            val known = sync.lookup(serial)
            when {
                known == null && label?.hasAccess == true -> {
                    store.update(serial, OutboxOp.SaveRecord(serial), OutboxOp.SaveCredentials(serial)) { newRecord(label) }
                    "New router: using the factory login from its label."
                }
                known == null -> throw RouterException(
                    "This router is not known yet. Scan the code on the device label (not the box) to set it up.",
                )
                label?.login != null && known.factoryPassword == null -> {
                    store.update(serial, OutboxOp.SaveCredentials(serial)) {
                        it!!.copy(factoryPassword = label.login!!.password, factoryAccess = it.factoryAccess ?: label.access())
                    }
                    "Known router."
                }
                else -> if (store.hasPending(serial)) "Known router (changes not on the server yet)." else "Known router."
            }
        }

        connectAndLogin(manual)
        _state.update { it.copy(phase = Phase.CONNECTED) }
        loadStatus()
        val r = record
        if (r.onFactoryPassword) {
            _state.update { it.copy(message = "The router still uses its factory password. Set its own password (setup step \"Own password\").") }
        }
        if (r.password != null && !r.onFactoryPassword) markDone(SetupStep.PASSWORD)
    }

    /** Network, certificate, login, identity. Updates the record with what was learned. */
    private suspend fun connectAndLogin(manual: String?) {
        val label = _state.value.label
        val via = _state.value.via
        var rec = record

        val (link, usedAccess) = step(if (via == Via.CABLE) "Using the network cable" else "Joining the router's Wi-Fi") {
            joinNetwork(rec, label, via)
        }
        val host = link.gateway ?: usedAccess?.lanIp ?: rec.access?.lanIp ?: DEFAULT_ROUTER_IP

        var trust = PinningTrustManager(rec.certFingerprint)
        var client = RutosClient("https://$host/", RouterNetwork.httpClient(http, link, trust))
        val candidate = step("Logging in at $host") {
            try {
                login(client, rec, manual, factoryOnly = false)
            } catch (e: CertificateChangedException) {
                // A reset router has a new certificate. Trust it again only with the factory login
                // from a label scanned just now.
                if (label?.login == null) throw e
                trust = PinningTrustManager(null)
                client = RutosClient("https://$host/", RouterNetwork.httpClient(http, link, trust))
                login(client, rec, manual = null, factoryOnly = true).also {
                    note("The certificate changed (router reset?). Trusted again after the factory login.")
                }
            }
        }
        val api = RouterApi(client)

        val device = step("Checking the router's identity") {
            val device = api.device()
            when (val id = Identity.check(serial, rec.mac ?: label?.mac, device)) {
                is Identity.Result.Mismatch -> {
                    runCatching { client.logout() }
                    throw RouterException("This is not the scanned router (expected ${id.expected}, found ${id.found}). Check which Wi-Fi the phone joined.")
                }
                is Identity.Result.Unknown -> note(id.reason)
                Identity.Result.Match -> Unit
            }
            device
        }

        rec = store.update(serial, OutboxOp.SaveRecord(serial), OutboxOp.SaveCredentials(serial)) { old ->
            var r = old!!.copy(
                certFingerprint = trust.seen ?: old.certFingerprint,
                model = device.modelName ?: old.model,
                productCode = device.productCode ?: old.productCode,
                mac = old.mac ?: device.mac,
                firmware = device.firmware ?: old.firmware,
                lastSeen = now(),
                username = candidate.credentials.username,
            )
            when (candidate.kind) {
                LoginCandidate.Kind.PENDING -> r = r.copy(password = candidate.credentials.password, pendingPassword = null)
                LoginCandidate.Kind.MANUAL -> r = r.copy(password = candidate.credentials.password)
                LoginCandidate.Kind.FACTORY -> if (r.password != null && r.password != candidate.credentials.password) {
                    r = r.copy(password = null) // the router was reset to factory settings
                }
                LoginCandidate.Kind.CURRENT -> Unit
            }
            if (usedAccess != null && usedAccess == r.pendingAccess) r = r.copy(access = usedAccess, pendingAccess = null)
            if (usedAccess != null && r.access == null && usedAccess != r.factoryAccess) r = r.copy(access = usedAccess)
            r
        }
        if (candidate.kind == LoginCandidate.Kind.FACTORY && rec.password == null && store.get(serial)?.factoryPassword != null) {
            note("Logged in with the factory password.")
        }
        this.api = api
        _state.update { it.copy(loginKind = candidate.kind, device = device) }
        scope.launch { sync.flush() }
    }

    private suspend fun joinNetwork(rec: RouterRecord, label: RouterLabel?, via: Via): Pair<RouterLink, RouterAccess?> {
        if (via == Via.CABLE) return network.cable() to null
        val options = listOfNotNull(rec.pendingAccess, rec.access, rec.factoryAccess, label?.access())
            .filter { !it.ssid.isNullOrBlank() }
            .distinctBy { it.ssid to it.wifiKey }
        if (options.isEmpty()) throw RouterException("The router's Wi-Fi is not known. Scan its device label, or connect a network cable.")
        var last: Exception? = null
        for (option in options) {
            try {
                return network.joinWifi(option.ssid!!, option.wifiKey, timeoutMs = 45_000) to option
            } catch (e: RouterWifiException) {
                last = e
            }
        }
        throw last!!
    }

    private suspend fun login(client: RutosClient, rec: RouterRecord, manual: String?, factoryOnly: Boolean): LoginCandidate {
        val factory = rec.factoryPassword?.let { Credentials(rec.username, it) }
        var candidates = LoginOrder.candidates(rec.username, rec.password, rec.pendingPassword, factory, _state.value.label)
        if (manual != null) candidates = listOf(LoginCandidate(Credentials(rec.username, manual), LoginCandidate.Kind.MANUAL)) + candidates
        if (factoryOnly) candidates = candidates.filter { it.kind == LoginCandidate.Kind.FACTORY }
        for (c in candidates) {
            try {
                client.login(c.credentials)
                return c
            } catch (e: RouterLoginException) {
                continue
            }
        }
        _state.update { it.copy(needsPassword = true) }
        throw RouterException(
            if (candidates.isEmpty()) "No password is known for this router. Enter it, or scan its device label."
            else "None of the known passwords works. Enter the current password, or reset the router and scan its label.",
        )
    }

    /** Drops the session and connects again, retrying for up to [maxWaitMs] (reboots take minutes). */
    private suspend fun reconnect(maxWaitMs: Long, firstDelayMs: Long) {
        api = null
        network.release()
        delay(firstDelayMs)
        val deadline = System.currentTimeMillis() + maxWaitMs
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                connectAndLogin(manual = null)
                loadStatus()
                return
            } catch (e: RouterUnreachableException) {
                last = e
            } catch (e: RouterWifiException) {
                last = e
            } catch (e: RouterApiException) {
                // The web server answers with errors while the router is still starting.
                last = e
            }
            network.release()
            delay(15_000)
        }
        _state.update { it.copy(phase = Phase.FAILED) }
        throw RouterException("The router did not come back. Check its lights, then use Retry.", last?.message)
    }

    // --- status ---

    fun refresh() = launchOp("Reading status") { loadStatus() }

    private suspend fun loadStatus() {
        val api = routerApi
        val errors = ArrayList<String>()
        suspend fun <T> part(what: String, block: suspend () -> T): T? = try {
            block()
        } catch (e: RouterException) {
            errors += "$what: ${e.userMessage}"
            null
        }
        val device = part("Device") { api.device() }
        val modems = part("Mobile") { api.modems() }
        val interfaces = part("Interfaces") { api.interfaces() }
        val leases = part("DHCP") { api.dhcpLeases() }
        _state.update { s ->
            s.copy(
                device = device ?: s.device,
                modems = modems ?: s.modems,
                interfaces = interfaces ?: s.interfaces,
                leases = leases ?: s.leases,
                statusErrors = errors,
            )
        }
    }

    /** Antenna mode: reads the signal every 1.5 s while [on]. */
    fun antenna(on: Boolean) {
        antennaJob?.cancel()
        antennaJob = null
        if (!on) return
        antennaJob = scope.launch {
            while (isActive) {
                val api = api ?: break
                runCatching { api.modems() }.onSuccess { modems ->
                    val signal = modems.firstOrNull { it.signal.rsrp != null || it.signal.rssi != null }?.signal
                    _state.update { s ->
                        s.copy(modems = modems, signalHistory = (s.signalHistory + listOfNotNull(signal)).takeLast(HISTORY))
                    }
                }
                delay(1_500)
            }
        }
    }

    // --- setup steps ---

    fun backup() = launchOp("Saving a backup") {
        val file = doBackup()
        _state.update { it.copy(message = "Backup saved (${file.length() / 1024} KB). It goes to the server when online.") }
    }

    private suspend fun doBackup(): File {
        val bytes = routerApi.backup()
        val created = now()
        val dir = File(backupDir, serial).apply { mkdirs() }
        val file = File(dir, "backup-$serial-${created.replace(":", "").replace("-", "")}.tar.gz")
        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        store.enqueue(OutboxOp.Backup(serial, created, file.absolutePath))
        scope.launch { sync.flush() }
        markDone(SetupStep.BACKUP)
        return file
    }

    fun checkFirmware() = launchOp("Checking firmware") {
        val base = settings.current().baseUrl
        val channel = runCatching { firmware.sync(base) }.getOrNull() ?: firmware.channel.value
            ?: throw RouterException("No router firmware list on the phone. Sync once with internet.")
        val device = _state.value.device ?: routerApi.device()
        val model = device.modelName ?: record.model
            ?: throw RouterException("The router did not report its model.")
        val image = channel.approvedFor(model, device.productCode ?: record.productCode)
            ?: throw RouterException("No firmware is approved for the $model yet.")
        val check = FirmwarePolicy.check(image, model, device.firmwareVersion)
        _state.update { it.copy(firmwareImage = image, firmwareCheck = check) }
        if (check == FirmwareCheck.AlreadyInstalled) markDone(SetupStep.FIRMWARE)
    }

    fun installFirmware() = launchOp("Updating firmware") {
        val image = _state.value.firmwareImage ?: throw RouterException("Check the firmware first.")
        if (_state.value.firmwareCheck !is FirmwareCheck.Ok) throw RouterException("This firmware cannot be installed on this router.")
        antenna(false)
        busy("Downloading ${image.version}")
        val file = firmware.download(settings.current().baseUrl, image) { done, total -> progress(done, total) }
        if (SetupStep.BACKUP !in _state.value.done) {
            busy("Saving a backup first")
            doBackup()
        }
        busy("Uploading the firmware to the router")
        routerApi.uploadFirmware(file) { sent, total -> progress(sent, total) }
        busy("Starting the installation")
        try {
            routerApi.upgradeFirmware(keepSettings = true)
        } catch (e: RouterUnreachableException) {
            // The router may drop the connection as soon as it starts installing.
        }
        busy("The router installs the firmware and restarts. This takes several minutes. Do not unplug it.")
        _state.update { it.copy(progress = null) }
        reconnect(maxWaitMs = 12 * 60_000L, firstDelayMs = 90_000)
        val now = _state.value.device?.firmwareVersion
        val target = image.parsedVersion
        if (now == null || target == null || now.compareTo(target) != 0) {
            throw RouterException("The router reports ${now?.short ?: "an unknown version"} instead of ${target?.short ?: image.version}.")
        }
        _state.update { it.copy(firmwareCheck = FirmwareCheck.AlreadyInstalled, message = "Firmware ${now.short} installed.") }
        markDone(SetupStep.FIRMWARE)
    }

    fun loadTemplates() = launchOp("Loading configurations") {
        val model = _state.value.device?.modelName ?: record.model
        val list = try {
            backend.templates(model).also(::cacheTemplates)
        } catch (e: UserFacingException) {
            cachedTemplates().ifEmpty { throw e }
        }
        _state.update { it.copy(templates = list.filter { t -> t.fits(model) }, plan = null, applyResult = null) }
    }

    fun planTemplate(template: RouterTemplate) = launchOp("Comparing with ${template.title}") {
        var rec = record
        if (rec.templateWifiKey == null) {
            rec = store.update(serial, OutboxOp.SaveCredentials(serial)) { it!!.copy(templateWifiKey = Passwords.generate(12)) }
        }
        val resolved = TemplateEngine.resolve(
            template,
            TemplateEngine.variables(serial, rec.mac, rec.model ?: _state.value.device?.modelName, rec.templateWifiKey),
            rec.overrides,
        )
        val plan = TemplateEngine.plan(routerApi.client, resolved)
        _state.update { it.copy(plan = plan, applyResult = null) }
        if (plan.inSync) markDone(SetupStep.TEMPLATE)
    }

    fun applyPlan() = launchOp("Applying the configuration") {
        val plan = _state.value.plan ?: throw RouterException("Compare with a configuration first.")
        antenna(false)
        if (SetupStep.BACKUP !in _state.value.done) {
            busy("Saving a backup first")
            doBackup()
        }
        val spec = plan.access
        if (plan.affectsConnection && spec != null) {
            // Written down before the router changes, so a crash cannot lose the way back in.
            store.update(serial, OutboxOp.SaveRecord(serial), OutboxOp.SaveCredentials(serial)) { r ->
                val cur = r!!.access ?: r.factoryAccess
                r.copy(pendingAccess = RouterAccess(spec.ssid ?: cur?.ssid, spec.wifiKey ?: cur?.wifiKey, spec.lanIp ?: cur?.lanIp))
            }
        }
        val result = TemplateEngine.apply(routerApi.client, plan) { i, n, title -> busy("Applying $title (${i + 1}/$n)") }
        _state.update { it.copy(applyResult = result) }
        if (result.ok) {
            store.update(serial, OutboxOp.SaveRecord(serial)) { it!!.copy(templateId = plan.template.id, templateVersion = plan.template.version) }
        }
        if (result.needsReconnect) {
            busy("Reconnecting with the new settings")
            reconnect(maxWaitMs = 3 * 60_000L, firstDelayMs = 8_000)
        }
        // Read everything again: also checks the values that could not be read back before.
        val again = TemplateEngine.plan(routerApi.client, plan.template)
        _state.update { it.copy(plan = again) }
        if (again.inSync) {
            markDone(SetupStep.TEMPLATE)
            _state.update { it.copy(message = "The router matches ${plan.template.title}.") }
        }
    }

    /**
     * Two-phase password change: the new password is stored (phone, then server) as pending
     * before the router gets it, and only marked confirmed after a login with it.
     */
    fun rotatePassword() = launchOp("Setting a new password") {
        val rec = record
        val new = Passwords.generate()
        store.update(serial, OutboxOp.SaveCredentials(serial)) { it!!.copy(pendingPassword = new) }
        sync.flush()
        routerApi.changePassword(rec.username, new)
        routerApi.client.login(Credentials(rec.username, new))
        store.update(serial, OutboxOp.SaveCredentials(serial)) { it!!.copy(password = new, pendingPassword = null) }
        val onServer = sync.flush()
        _state.update { it.copy(loginKind = LoginCandidate.Kind.CURRENT) }
        markDone(SetupStep.PASSWORD)
        _state.update {
            it.copy(
                message = if (onServer) "New password set and stored on the server."
                else "New password set and stored on the phone. It goes to the server when the phone is online.",
            )
        }
    }

    fun mobileCheck() = launchOp("Checking the mobile connection") {
        loadStatus()
        val checks = MobileChecks.of(_state.value.modems, _state.value.interfaces)
        if (checks.all { it.ok == true }) markDone(SetupStep.MOBILE)
    }

    fun sendReport(note: String) = launchOp("Sending the report") {
        loadStatus()
        val s = _state.value
        val rec = record
        val report = buildJsonObject {
            put("serial", rec.serial)
            put("created", now())
            put("model", rec.model)
            put("product_code", rec.productCode)
            put("imei", rec.imei)
            put("mac", rec.mac)
            put("firmware", s.device?.firmware ?: rec.firmware)
            put("template_id", rec.templateId)
            rec.templateVersion?.let { put("template_version", it) }
            put("own_password", !rec.onFactoryPassword)
            put("steps_done", buildJsonArray { s.done.forEach { add(JsonPrimitive(it.name.lowercase())) } })
            put("modems", buildJsonArray {
                s.modems.forEach { m ->
                    add(buildJsonObject {
                        put("operator", m.operator)
                        put("technology", m.technology)
                        put("band", m.band)
                        m.signal.rssi?.let { put("rssi", it) }
                        m.signal.rsrp?.let { put("rsrp", it) }
                        m.signal.rsrq?.let { put("rsrq", it) }
                        m.signal.sinr?.let { put("sinr", it) }
                        put("quality", m.signal.quality.name.lowercase())
                        put("iccid", m.iccid)
                        put("sim_state", m.simState)
                    })
                }
            })
            put("dhcp_clients", s.leases.size)
            put("phone", "${Build.MANUFACTURER} ${Build.MODEL}")
            if (note.isNotBlank()) put("note", note.trim())
        }
        store.enqueue(OutboxOp.Report(serial, report))
        val sent = sync.flush()
        markDone(SetupStep.REPORT)
        _state.update { it.copy(message = if (sent) "Report sent." else "Report saved. It goes to the server when the phone is online.") }
    }

    fun reboot() = launchOp("Restarting the router") {
        antenna(false)
        try {
            routerApi.reboot()
        } catch (e: RouterUnreachableException) {
            // may drop the connection right away
        }
        busy("Waiting for the router to restart")
        reconnect(maxWaitMs = 5 * 60_000L, firstDelayMs = 45_000)
        _state.update { it.copy(message = "The router is back.") }
    }

    /** Writes the router's Wi-Fi into a microBOX profile (`wifi.ssid`, `wifi.pwd`). */
    fun copyWifiToProfile(profileId: String) = launchOp("Updating the microBOX profile") {
        val access = record.access ?: record.factoryAccess ?: throw RouterException("The router's Wi-Fi is not known.")
        val profile = profiles.get(profileId) ?: throw RouterException("The profile no longer exists.")
        var config = profile.config
        config = LuaJson.with(config, "wifi.ssid", JsonPrimitive(access.ssid ?: ""))
        config = LuaJson.with(config, "wifi.pwd", JsonPrimitive(access.wifiKey ?: ""))
        profiles.save(profile.copy(config = config))
        _state.update { it.copy(message = "Profile ${profile.name} now uses the Wi-Fi \"${access.ssid}\".") }
    }

    /**
     * Before driving to a site: send the outbox, refresh every router on the phone, fetch the
     * firmware list and the approved images for these models, and cache the configurations.
     */
    fun prepareOffline() = launchOp("Syncing routers") {
        sync.flush()
        val refreshed = sync.refreshAll()
        val base = settings.current().baseUrl
        busy("Fetching the firmware list")
        var images = 0
        firmware.sync(base)?.let { channel ->
            store.routers.value
                .mapNotNull { r -> r.model?.let { channel.approvedFor(it, r.productCode) } }
                .distinctBy { it.id }
                .forEach { image ->
                    busy("Downloading ${image.version}")
                    firmware.download(base, image) { done, total -> progress(done, total) }
                    images++
                }
            firmware.prune()
        }
        busy("Fetching configurations")
        val templates = runCatching { backend.templates(null).also(::cacheTemplates).size }.getOrNull()
        val waiting = store.outbox.value.size
        _state.update {
            it.copy(
                message = "$refreshed routers updated, $images firmware images and ${templates ?: "no"} configurations on the phone" +
                    if (waiting > 0) ", $waiting changes wait for the server." else ".",
            )
        }
    }

    // --- helpers ---

    private fun launchOp(text: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update { it.copy(busy = text, error = null, message = null, progress = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = messageOf(e)
                _state.update { it.copy(error = message, phase = if (it.phase == Phase.CONNECTING) Phase.FAILED else it.phase) }
            } finally {
                _state.update { it.copy(busy = null, progress = null) }
            }
        }
    }

    private fun busy(text: String) = _state.update { it.copy(busy = text, progress = null) }

    private fun progress(done: Long, total: Long) =
        _state.update { it.copy(progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null) }

    private fun markDone(step: SetupStep) = _state.update { it.copy(done = it.done + step) }

    private suspend fun <T> step(text: String, block: suspend () -> T): T {
        _state.update { it.copy(log = it.log + StepLog(text)) }
        return try {
            val result = block()
            finishStep(ok = true, detail = result as? String)
            result
        } catch (e: Exception) {
            finishStep(ok = false, detail = if (e is CancellationException) null else messageOf(e))
            throw e
        }
    }

    private fun finishStep(ok: Boolean, detail: String?) = _state.update { s ->
        val last = s.log.lastOrNull() ?: return@update s
        s.copy(log = s.log.dropLast(1) + last.copy(ok = ok, detail = detail ?: last.detail))
    }

    private fun note(text: String) = _state.update { s ->
        val last = s.log.lastOrNull() ?: return@update s
        s.copy(log = s.log.dropLast(1) + last.copy(detail = listOfNotNull(last.detail, text).joinToString(" ")))
    }

    private fun cacheTemplates(list: List<RouterTemplate>) = runCatching {
        templateCache.writeText(cipher.encrypt(RouterTemplate.json.encodeToString(ListSerializer(RouterTemplate.serializer()), list)))
    }

    private fun cachedTemplates(): List<RouterTemplate> = runCatching {
        RouterTemplate.json.decodeFromString(ListSerializer(RouterTemplate.serializer()), cipher.decrypt(templateCache.readText()))
    }.getOrDefault(emptyList())

    private fun newRecord(label: RouterLabel) = RouterRecord(
        serial = label.serial,
        imei = label.imei,
        mac = label.mac,
        batch = label.batch,
        username = label.login!!.username,
        factoryPassword = label.login!!.password,
        factoryAccess = label.access(),
        lastSeen = now(),
    )

    private fun RouterLabel.access(): RouterAccess? = wifi?.let { RouterAccess(it.ssid, it.passphrase, null) }

    companion object {
        const val DEFAULT_ROUTER_IP = "192.168.1.1"
        private const val HISTORY = 120

        fun now(): String = OffsetDateTime.now().withNano(0).toString()

        fun messageOf(e: Throwable): String = when (e) {
            is RouterException -> e.userMessage
            is FlasherException -> e.userMessage
            is UserFacingException -> e.userMessage
            else -> e.message ?: e.toString()
        }
    }
}

/** One line of the mobile check. [ok] null: unknown. */
data class CheckItem(val title: String, val ok: Boolean?, val detail: String?)

object MobileChecks {
    fun of(modems: List<ModemStatus>, interfaces: List<InterfaceStatus>): List<CheckItem> {
        val m = modems.firstOrNull { it.simState != null || it.operator != null } ?: modems.firstOrNull()
            ?: return listOf(CheckItem("Modem", false, "The router reports no modem."))
        val sim = m.simState
        val simOk = sim?.let { s -> !s.contains("not", true) && !s.contains("absent", true) && !s.contains("error", true) }
        val pin = m.pinState
        val pinOk = pin?.let { p -> p.equals("ok", true) || p.contains("ready", true) || p == "0" }
        val registered = m.registration?.let { r -> r.contains("regist", true) && !r.contains("not", true) } ?: (m.operator != null)
        val signalOk = m.signal.quality.ordinal <= com.progeo.teltonika.api.SignalQuality.FAIR.ordinal
        val mobile = interfaces.filter { i -> i.name.startsWith("mob", true) || i.protocol?.contains("wwan", true) == true || i.protocol == "qmi" || i.protocol == "ncm" }
        val dataOk = if (mobile.isEmpty()) null else mobile.any { it.up == true && it.ipv4 != null }
        return listOf(
            CheckItem("SIM card", simOk, sim),
            CheckItem("SIM PIN", pinOk, pin),
            CheckItem("Registered in the network", registered, listOfNotNull(m.operator, m.technology, m.band).joinToString(" · ").ifEmpty { m.registration }),
            CheckItem("Signal", signalOk, m.signal.quality.label + (m.signal.rsrp?.let { " · RSRP $it dBm" } ?: "")),
            CheckItem("Mobile data", dataOk, mobile.firstOrNull { it.ipv4 != null }?.ipv4 ?: mobile.firstOrNull()?.name),
        )
    }
}

/** Shown in the app: "07.06.1" etc. */
fun String?.firmwareShort(): String? = this?.let { FirmwareVersion.parse(it)?.short ?: it }

/** A secret for display: all but the last two characters hidden. */
fun mask(secret: String?): String = when {
    secret.isNullOrEmpty() -> "–"
    secret.length <= 4 -> "••••"
    else -> "•".repeat(secret.length - 2) + secret.takeLast(2)
}
