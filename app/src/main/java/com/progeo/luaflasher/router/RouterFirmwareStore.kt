package com.progeo.luaflasher.router

import com.progeo.luaflasher.core.SignatureException
import com.progeo.luaflasher.core.bundle.SignatureVerifier
import com.progeo.luaflasher.data.NetworkException
import com.progeo.teltonika.firmware.FirmwareChannel
import com.progeo.teltonika.firmware.FirmwareImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Router firmware from our release server: `routers/channel.json` + `.sig` under the firmware
 * base URL, signed with the same keys as the bundle channel. The signature covers each image's
 * size and SHA-256, so a downloaded image is genuine when both match. Images are kept for
 * offline use; only approved ones are kept after a sync.
 */
class RouterFirmwareStore(
    private val http: OkHttpClient,
    private val verifier: SignatureVerifier,
    private val dir: File,
) {
    private val _channel = MutableStateFlow<FirmwareChannel?>(null)
    val channel: StateFlow<FirmwareChannel?> = _channel.asStateFlow()

    init {
        dir.mkdirs()
    }

    private val channelFile get() = File(dir, "channel.json")
    private val sigFile get() = File(dir, "channel.json.sig")

    /** The last verified channel (checked again). */
    fun loadCached() {
        _channel.value = runCatching {
            val body = channelFile.readBytes()
            verifier.verify(body, sigFile.readText(), "router firmware channel")
            FirmwareChannel.parse(body)
        }.getOrNull()
    }

    /** Fetches and verifies the channel; null if nothing is published (404). */
    suspend fun sync(baseUrl: String): FirmwareChannel? = withContext(Dispatchers.IO) {
        val body = get(baseUrl, CHANNEL) ?: return@withContext null
        val sig = get(baseUrl, "$CHANNEL.sig") ?: throw SignatureException("router firmware channel has no signature")
        verifier.verify(body, String(sig, Charsets.UTF_8), "router firmware channel")
        val parsed = FirmwareChannel.parse(body)
        channelFile.writeBytes(body)
        sigFile.writeBytes(sig)
        _channel.value = parsed
        parsed
    }

    fun file(image: FirmwareImage) = File(dir, image.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".bin")

    fun isDownloaded(image: FirmwareImage) = file(image).let { it.isFile && it.length() == image.size }

    /** Downloads [image] (unless it is here already) and checks size and SHA-256. */
    suspend fun download(baseUrl: String, image: FirmwareImage, onProgress: (Long, Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            val target = file(image)
            if (isDownloaded(image) && sha256(target).equals(image.sha256, ignoreCase = true)) return@withContext target
            val part = File(dir, target.name + ".part")
            val url = baseUrl.toHttpUrl().resolve(image.url) ?: throw NetworkException("bad URL ${image.url}")
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw NetworkException("GET ${image.url}: HTTP ${r.code}")
                    part.outputStream().use { out ->
                        r.body!!.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                done += n
                                if (done > image.size) throw SignatureException("firmware ${image.id}: larger than announced")
                                out.write(buf, 0, n)
                                onProgress(done, image.size)
                            }
                        }
                    }
                }
                if (part.length() != image.size) throw SignatureException("firmware ${image.id}: size ${part.length()}, expected ${image.size}")
                if (!sha256(part).equals(image.sha256, ignoreCase = true)) throw SignatureException("firmware ${image.id}: SHA-256 mismatch")
                target.delete()
                check(part.renameTo(target)) { "cannot store firmware ${image.id}" }
                target
            } catch (e: IOException) {
                part.delete()
                throw NetworkException("download ${image.url}: ${e.message}", e)
            } catch (e: Exception) {
                part.delete()
                throw e
            }
        }

    /** Deletes images that are no longer approved for any model. */
    fun prune() {
        val keep = _channel.value?.approved?.map { it.image }?.toSet() ?: return
        val images = _channel.value?.images.orEmpty().filter { it.id in keep }.map { file(it).name }.toSet()
        dir.listFiles { f -> f.name.endsWith(".bin") && f.name !in images }?.forEach { it.delete() }
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        const val CHANNEL = "routers/channel.json"
    }
}
