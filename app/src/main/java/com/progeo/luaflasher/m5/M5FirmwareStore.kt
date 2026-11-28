package com.progeo.luaflasher.m5

import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.SignatureException
import com.progeo.luaflasher.core.bundle.SignatureVerifier
import com.progeo.luaflasher.core.io.sha256Hex
import com.progeo.luaflasher.core.m5.M5Bundle
import com.progeo.luaflasher.core.m5.M5Bundles
import com.progeo.luaflasher.core.m5.M5Channel
import com.progeo.luaflasher.core.m5.M5ChannelBundle
import com.progeo.luaflasher.data.NetworkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

data class M5FirmwareState(
    /** Last verified channel (server or cache). */
    val channel: M5Channel? = null,
    /** Bundle IDs on the phone. */
    val downloaded: Set<String> = emptySet(),
    val notPublished: Boolean = false,
    val running: Boolean = false,
    val downloading: String? = null,
    val progress: Float = 0f,
    val error: String? = null,
    /** The channel needs a newer app. */
    val appTooOld: Boolean = false,
)

/**
 * M5Stack firmware releases from the release server: `versions/mstack` + `.sig` under the firmware
 * base URL (published by the m5-stack CI, same signing keys as the LUA_WLAN channels). Each release
 * zip is kept with its signature; both are checked again whenever a release is opened, so a file on
 * the phone is never trusted on its own. Keeps the latest and two more.
 */
class M5FirmwareStore(
    private val http: OkHttpClient,
    private val verifier: SignatureVerifier,
    private val dir: File,
    private val appVersionCode: Int,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(M5FirmwareState())
    val state: StateFlow<M5FirmwareState> = _state.asStateFlow()

    init {
        dir.mkdirs()
    }

    private val channelFile get() = File(dir, "channel.json")
    private val channelSig get() = File(dir, "channel.json.sig")
    private fun zip(id: String) = File(dir, safe(id) + ".zip")
    private fun sig(id: String) = File(dir, safe(id) + ".zip.sig")
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun loadCached() {
        val channel = runCatching {
            val body = channelFile.readBytes()
            verifier.verify(body, channelSig.readText(), "M5 channel")
            M5Bundles.parseChannel(body)
        }.getOrNull()
        _state.update { it.copy(channel = channel, downloaded = onPhone(), appTooOld = channel.tooNew()) }
    }

    private fun M5Channel?.tooNew() = this != null && minAppVersion > appVersionCode

    private fun onPhone(): Set<String> =
        dir.listFiles { f -> f.name.endsWith(".zip") }.orEmpty()
            .filter { File(dir, it.name + ".sig").isFile }
            .map { it.name.removeSuffix(".zip") }.toSet()

    fun isDownloaded(id: String) = zip(id).isFile && sig(id).isFile

    /** Fetches the channel and downloads `latest` if it is missing. Failures land in [state]. */
    suspend fun sync(baseUrl: String): Result<Unit> = mutex.withLock {
        _state.update { it.copy(running = true, error = null) }
        try {
            val body = get(baseUrl, "versions/${M5Bundles.CHANNEL}")
            if (body == null) {
                channelFile.delete()
                channelSig.delete()
                _state.update { it.copy(channel = null, notPublished = true) }
                return@withLock Result.success(Unit)
            }
            val sigText = get(baseUrl, "versions/${M5Bundles.CHANNEL}.sig") ?: throw SignatureException("M5 channel has no signature")
            verifier.verify(body, String(sigText, Charsets.UTF_8), "M5 channel")
            val channel = M5Bundles.parseChannel(body)
            if (channel.channel != M5Bundles.CHANNEL) throw SignatureException("asked for ${M5Bundles.CHANNEL}, got ${channel.channel}")
            channelFile.writeBytes(body)
            channelSig.writeBytes(sigText)
            _state.update { it.copy(channel = channel, notPublished = false, appTooOld = channel.tooNew()) }
            val latest = channel.latestBundle
            if (latest != null && !channel.tooNew() && !isDownloaded(latest.id)) fetch(baseUrl, latest)
            prune(channel)
            Result.success(Unit)
        } catch (e: FlasherException) {
            _state.update { it.copy(error = e.userMessage) }
            Result.failure(e)
        } finally {
            _state.update { it.copy(running = false, downloading = null, downloaded = onPhone()) }
        }
    }

    /** Downloads an older listed release on demand. */
    suspend fun download(baseUrl: String, entry: M5ChannelBundle): Result<Unit> = mutex.withLock {
        _state.update { it.copy(error = null) }
        try {
            fetch(baseUrl, entry)
            Result.success(Unit)
        } catch (e: FlasherException) {
            _state.update { it.copy(error = e.userMessage) }
            Result.failure(e)
        } finally {
            _state.update { it.copy(downloading = null, downloaded = onPhone()) }
        }
    }

    /** Opens a release on the phone: signature and contents are checked again. */
    suspend fun open(id: String): M5Bundle = withContext(Dispatchers.IO) {
        val data = zip(id).takeIf { it.isFile }?.readBytes()
            ?: throw FlasherException("Firmware $id is not on the phone. Connect to the internet and download it.")
        verifier.verify(data, sig(id).readText(), "M5 release $id")
        M5Bundles.open(data).also {
            if (it.manifest.bundleId != id) throw SignatureException("zip $id contains ${it.manifest.bundleId}")
        }
    }

    private suspend fun fetch(baseUrl: String, entry: M5ChannelBundle) = withContext(Dispatchers.IO) {
        _state.update { it.copy(downloading = entry.id, progress = 0f) }
        val url = baseUrl.toHttpUrl().resolve(entry.url) ?: throw NetworkException("bad URL ${entry.url}")
        val part = File(dir, safe(entry.id) + ".zip.part")
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw NetworkException("GET ${entry.url}: HTTP ${r.code}")
                part.outputStream().use { out ->
                    r.body!!.byteStream().use { input ->
                        val buf = ByteArray(32 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > entry.size) throw SignatureException("M5 release ${entry.id}: larger than announced")
                            out.write(buf, 0, n)
                            _state.update { it.copy(progress = done.toFloat() / entry.size.coerceAtLeast(1)) }
                        }
                    }
                }
            }
            val data = part.readBytes()
            if (data.size.toLong() != entry.size) throw SignatureException("M5 release ${entry.id}: size ${data.size}, expected ${entry.size}")
            if (!sha256Hex(data).equals(entry.sha256, ignoreCase = true)) throw SignatureException("M5 release ${entry.id}: SHA-256 mismatch")
            val sigBytes = get(baseUrl, entry.sigUrl) ?: throw SignatureException("M5 release ${entry.id}: no signature on the server")
            val sigText = String(sigBytes, Charsets.UTF_8)
            verifier.verify(data, sigText, "M5 release ${entry.id}")
            M5Bundles.open(data) // contents match the manifest
            sig(entry.id).writeText(sigText)
            zip(entry.id).delete()
            check(part.renameTo(zip(entry.id))) { "cannot store ${entry.id}" }
        } catch (e: IOException) {
            throw NetworkException("download ${entry.url}: ${e.message}", e)
        } finally {
            part.delete()
        }
    }

    /** Keeps the channel's latest and the two newest others that are still listed. */
    private fun prune(channel: M5Channel) {
        val listed = channel.bundles.map { it.id }
        val keep = (listOfNotNull(channel.latest) + listed.filter { it != channel.latest && isDownloaded(it) }.take(2)).toSet()
        for (id in onPhone()) {
            if (id !in keep) {
                zip(id).delete()
                sig(id).delete()
            }
        }
    }

    private fun get(baseUrl: String, path: String): ByteArray? {
        val url = baseUrl.toHttpUrl().resolve(path) ?: throw NetworkException("bad URL $baseUrl + $path")
        try {
            http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
                if (r.code == 404) return null
                if (!r.isSuccessful) throw NetworkException("GET $path: HTTP ${r.code}")
                return r.body!!.bytes()
            }
        } catch (e: IOException) {
            throw NetworkException("GET $path: ${e.message}", e)
        }
    }
}
