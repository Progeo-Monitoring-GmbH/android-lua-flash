package com.progeo.luaflasher

import android.app.Application
import android.content.Context
import com.progeo.core.auth.BackendClient
import com.progeo.core.auth.EncryptedTokenStore
import com.progeo.luaflasher.core.bundle.PublicKeys
import com.progeo.luaflasher.core.bundle.SignatureVerifier
import com.progeo.luaflasher.core.config.ConfigTemplate
import com.progeo.luaflasher.core.config.FieldKind
import com.progeo.luaflasher.data.AppSettings
import com.progeo.luaflasher.data.BundleStore
import com.progeo.luaflasher.data.ChannelClient
import com.progeo.luaflasher.data.ProfileStore
import com.progeo.core.security.SecretCipher
import com.progeo.luaflasher.data.SettingsRepository
import com.progeo.luaflasher.data.SyncRepository
import com.progeo.luaflasher.data.SyncWorker
import com.progeo.luaflasher.flash.BoardConfigReader
import com.progeo.luaflasher.flash.FlashSession
import com.progeo.luaflasher.m5.M5FirmwareStore
import com.progeo.luaflasher.m5.M5ProfileStore
import com.progeo.luaflasher.m5.M5Session
import com.progeo.luaflasher.router.RouterBackend
import com.progeo.luaflasher.router.RouterController
import com.progeo.luaflasher.router.RouterFirmwareStore
import com.progeo.luaflasher.router.RouterStore
import com.progeo.luaflasher.router.RouterSync
import com.progeo.luaflasher.usb.UsbSerialManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Manual dependency container; one per process. */
class AppGraph(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context)
    val publicKeys: PublicKeys = PublicKeys.parse(context.assets.open("firmware-signing.pub.json").use { String(it.readBytes()) })

    /**
     * LUA_WLAN bundles and router firmware: every key except the M5 pipeline keys (`mstack-*`). Those sit
     * in the m5-stack CI, so they may only ever vouch for M5 releases.
     */
    val verifier = SignatureVerifier(PublicKeys(publicKeys.keys.filterKeys { !it.startsWith(M5_KEY_PREFIX) }))

    /** M5 releases (mstack channel): every key. */
    private val m5Verifier = SignatureVerifier(publicKeys)

    // No CookieJar: the backend authenticates with the bearer header only (API.md).
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Template for the profile editor when no bundle is on the phone yet. */
    val fallbackTemplate: String = context.assets.open("device_config.lua.template").use { String(it.readBytes()) }
    private val fallback = ConfigTemplate.parse(fallbackTemplate)

    /** Field types from the built-in template, to read old dotted-path profiles. */
    val fieldKind: (String) -> FieldKind? = { path -> fallback.field(path)?.kind }

    val bundles = BundleStore(File(context.filesDir, "bundles"))
    val channels = ChannelClient(http, verifier, File(context.filesDir, "channels"))
    val sync = SyncRepository(settings, channels, bundles, File(context.filesDir, "downloads"), BuildConfig.VERSION_CODE)
    // Same key alias as before the move to progeo-core: existing profiles stay readable.
    val profiles = ProfileStore(File(context.filesDir, "profiles.json"), SecretCipher("profile-secrets"), fieldKind)
    val usb = UsbSerialManager(context)
    val flash = FlashSession(context, bundles, usb, settings)
    val boardReader = BoardConfigReader(usb, flash)

    // M5Stack with the MicroPython firmware: profiles from its config QR code, releases from the mstack channel.
    val m5Profiles = M5ProfileStore(File(context.filesDir, "m5-profiles.json"), SecretCipher("profile-secrets"))
    val m5Firmware = M5FirmwareStore(http, m5Verifier, File(context.filesDir, "m5-firmware"), BuildConfig.VERSION_CODE)
    val m5 = M5Session(appScope, usb, flash, m5Firmware, m5Profiles).also { session -> flash.portBusy = { session.isActive } }

    /** Syncs the M5 releases with the current firmware base URL. */
    suspend fun syncM5(): Result<Unit> = m5Firmware.sync(settings.current().baseUrl)

    @Volatile
    var backendUrl: String = AppSettings.DEFAULT_BACKEND_URL
        private set
    val backend = BackendClient(
        baseUrl = { backendUrl },
        tokens = EncryptedTokenStore(File(context.noBackupFilesDir, "backend-tokens.bin"), SecretCipher("backend-tokens")),
        base = http,
    )

    // Routers (DRAFT.md): records and outbox encrypted on the phone, firmware from the release server.
    private val routerCipher = SecretCipher("router-secrets")
    val routerStore = RouterStore(File(context.noBackupFilesDir, "routers.bin"), routerCipher)
    val routerSync = RouterSync(routerStore, RouterBackend(backend))
    val router = RouterController(
        context = context,
        scope = appScope,
        store = routerStore,
        sync = routerSync,
        backend = RouterBackend(backend),
        firmware = RouterFirmwareStore(http, verifier, File(context.filesDir, "router-firmware")),
        settings = settings,
        profiles = profiles,
        http = http,
        cipher = routerCipher,
        backupDir = File(context.noBackupFilesDir, "router-backups"),
    )

    init {
        appScope.launch { settings.settings.collect { backendUrl = it.backendUrl } }
    }

    companion object {
        /** Key IDs of the M5 release pipeline (keys/firmware-signing.pub.json). */
        const val M5_KEY_PREFIX = "mstack-"
    }

    /** The template of the newest bundle on the phone, else the built-in copy. */
    suspend fun currentTemplate(): ConfigTemplate {
        val newest = bundles.bundles.value.maxByOrNull { it.created ?: "" }
        val source = newest?.let { runCatching { bundles.load(it.id).template }.getOrNull() } ?: fallbackTemplate
        return ConfigTemplate.parse(source)
    }
}

class LuaFlasherApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.appScope.launch {
            graph.profiles.load()
            graph.m5Profiles.load()
            graph.m5Firmware.loadCached()
            graph.routerStore.load()
            graph.router.firmware.loadCached()
            graph.sync.loadCached()
            // Sync on app start; offline this just fails quietly and the cache stays in use.
            graph.sync.sync()
            graph.syncM5()
        }
        SyncWorker.schedule(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as LuaFlasherApp).graph
