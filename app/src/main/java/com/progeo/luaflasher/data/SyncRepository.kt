package com.progeo.luaflasher.data

import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.bundle.BundleFormat
import com.progeo.luaflasher.core.bundle.ChannelBundle
import com.progeo.luaflasher.core.bundle.ChannelFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class SyncState(
    val channelName: String = "",
    /** Last verified channel file (from the server or the cache). */
    val channel: ChannelFile? = null,
    /** The server has nothing in this channel yet. */
    val notPublished: Boolean = false,
    val running: Boolean = false,
    val downloading: String? = null,
    val downloadProgress: Float = 0f,
    val lastSuccess: Long? = null,
    val error: String? = null,
    val appTooOld: Boolean = false,
)

/** Sync (PLAN.md section 4, step 1): channel → latest bundle → store → prune. */
class SyncRepository(
    private val settings: SettingsRepository,
    private val client: ChannelClient,
    private val store: BundleStore,
    private val downloadDir: File,
    private val appVersionCode: Int,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Shows the cached channel right away, before any network. */
    suspend fun loadCached() {
        val s = settings.current()
        val cached = client.cached(s.channel)
        _state.update { it.copy(channelName = s.channel, channel = cached, appTooOld = cached.tooNew()) }
    }

    private fun ChannelFile?.tooNew() = this != null && minAppVersion > appVersionCode

    /** Fetches the channel and downloads `latest` if it is not on the phone yet. */
    suspend fun sync(): Result<Unit> = mutex.withLock {
        val s = settings.current()
        _state.update { it.copy(running = true, error = null, channelName = s.channel) }
        try {
            when (val result = client.fetch(s.baseUrl, s.channel)) {
                ChannelResult.NotPublished -> {
                    client.clearCache(s.channel)
                    _state.update { it.copy(channel = null, notPublished = true, lastSuccess = System.currentTimeMillis()) }
                }
                is ChannelResult.Published -> {
                    val channel = result.channel
                    _state.update {
                        it.copy(channel = channel, notPublished = false, appTooOld = channel.tooNew())
                    }
                    val latest = channel.latestBundle
                    if (latest != null && !channel.tooNew() && latest.configSchema <= BundleFormat.SUPPORTED_CONFIG_SCHEMA && store.get(latest.id) == null) {
                        download(s.baseUrl, latest)
                    }
                    store.prune(latest?.id)
                    _state.update { it.copy(lastSuccess = System.currentTimeMillis()) }
                }
            }
            Result.success(Unit)
        } catch (e: FlasherException) {
            _state.update { it.copy(error = e.userMessage) }
            Result.failure(e)
        } finally {
            _state.update { it.copy(running = false, downloading = null) }
        }
    }

    /** Downloads an older listed bundle on demand. */
    suspend fun downloadOnDemand(entry: ChannelBundle): Result<Unit> = mutex.withLock {
        val s = settings.current()
        _state.update { it.copy(error = null) }
        try {
            download(s.baseUrl, entry)
            Result.success(Unit)
        } catch (e: FlasherException) {
            _state.update { it.copy(error = e.userMessage) }
            Result.failure(e)
        } finally {
            _state.update { it.copy(downloading = null) }
        }
    }

    private suspend fun download(baseUrl: String, entry: ChannelBundle) {
        _state.update { it.copy(downloading = entry.id, downloadProgress = 0f) }
        val target = File(downloadDir, "${entry.id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.zip.part")
        val keyId = client.downloadBundle(baseUrl, entry, target) { done, total ->
            _state.update { it.copy(downloadProgress = done.toFloat() / total.coerceAtLeast(1)) }
        }
        try {
            store.add(target, signed = true, keyId = keyId, notes = entry.notes, expectedId = entry.id)
        } finally {
            target.delete()
        }
    }
}
