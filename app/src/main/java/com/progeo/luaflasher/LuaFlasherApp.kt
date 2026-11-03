package com.progeo.luaflasher

import android.app.Application
import android.content.Context
import com.progeo.luaflasher.core.bundle.PublicKeys
import com.progeo.luaflasher.core.bundle.SignatureVerifier
import com.progeo.luaflasher.core.config.ConfigTemplate
import com.progeo.luaflasher.data.BundleStore
import com.progeo.luaflasher.data.ChannelClient
import com.progeo.luaflasher.data.ProfileStore
import com.progeo.luaflasher.data.SecretCipher
import com.progeo.luaflasher.data.SettingsRepository
import com.progeo.luaflasher.data.SyncRepository
import com.progeo.luaflasher.data.SyncWorker
import com.progeo.luaflasher.flash.FlashSession
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
    val verifier = SignatureVerifier(publicKeys)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val bundles = BundleStore(File(context.filesDir, "bundles"))
    val channels = ChannelClient(http, verifier, File(context.filesDir, "channels"))
    val sync = SyncRepository(settings, channels, bundles, File(context.filesDir, "downloads"), BuildConfig.VERSION_CODE)
    val profiles = ProfileStore(File(context.filesDir, "profiles.json"), SecretCipher())
    val usb = UsbSerialManager(context)
    val flash = FlashSession(context, bundles, usb, settings)

    /** Template for the profile editor when no bundle is on the phone yet. */
    val fallbackTemplate: String = context.assets.open("device_config.lua.template").use { String(it.readBytes()) }

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
            graph.sync.loadCached()
            // Sync on app start; offline this just fails quietly and the cache stays in use.
            graph.sync.sync()
        }
        SyncWorker.schedule(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as LuaFlasherApp).graph
