package com.progeo.luaflasher.data

import com.progeo.luaflasher.core.BundleException
import com.progeo.luaflasher.core.SignatureException
import com.progeo.luaflasher.core.bundle.ChannelBundle
import com.progeo.luaflasher.core.bundle.ChannelFile
import com.progeo.luaflasher.core.bundle.ChannelParser
import com.progeo.luaflasher.core.bundle.SignatureVerifier
import com.progeo.luaflasher.core.io.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class NetworkException(detail: String, cause: Throwable? = null) :
    com.progeo.luaflasher.core.FlasherException("The release server cannot be reached. Check the network and retry.", detail, cause)

sealed interface ChannelResult {
    data class Published(val channel: ChannelFile) : ChannelResult

    /** The server answers 404: nothing released into this channel yet. */
    data object NotPublished : ChannelResult
}

/** Read-only access to `https://api.progeo.com/firmware/` (PLAN.md section 3). */
class ChannelClient(
    private val http: OkHttpClient,
    private val verifier: SignatureVerifier,
    private val cacheDir: File,
) {
    init {
        cacheDir.mkdirs()
    }

    private fun url(baseUrl: String, path: String) = baseUrl.toHttpUrl().resolve(path)
        ?: throw NetworkException("bad URL $baseUrl + $path")

    /** Fetches and verifies the channel file **before** parsing it; caches the verified bytes. */
    suspend fun fetch(baseUrl: String, channel: String): ChannelResult = withContext(Dispatchers.IO) {
        val body = get(baseUrl, "versions/$channel") ?: return@withContext ChannelResult.NotPublished
        val sig = get(baseUrl, "versions/$channel.sig") ?: throw SignatureException("channel $channel has no signature")
        verifier.verify(body, String(sig, Charsets.UTF_8), "channel $channel")
        val parsed = ChannelParser.parse(body)
        if (parsed.channel != channel) throw BundleException("The release list is damaged.", "asked for $channel, got ${parsed.channel}")
        File(cacheDir, "$channel.json").writeBytes(body)
        File(cacheDir, "$channel.json.sig").writeBytes(sig)
        ChannelResult.Published(parsed)
    }

    /** The last verified channel file (checked again), for offline use. */
    fun cached(channel: String): ChannelFile? = runCatching {
        val body = File(cacheDir, "$channel.json").readBytes()
        verifier.verify(body, File(cacheDir, "$channel.json.sig").readText(), "cached channel")
        ChannelParser.parse(body)
    }.getOrNull()

    fun clearCache(channel: String) {
        File(cacheDir, "$channel.json").delete()
        File(cacheDir, "$channel.json.sig").delete()
    }

    /** Body, or null on 404. */
    private fun get(baseUrl: String, path: String): ByteArray? {
        val request = Request.Builder().url(url(baseUrl, path)).header("Cache-Control", "no-cache").build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 404) return null
                if (!response.isSuccessful) throw NetworkException("GET $path: HTTP ${response.code}")
                return response.body!!.bytes()
            }
        } catch (e: IOException) {
            throw NetworkException("GET $path: ${e.message}", e)
        }
    }

    /**
     * Downloads a bundle into [target] with resume (`Range`), then checks size and SHA-256
     * from the channel and the zip signature. Any failure deletes the download.
     * Returns the signing key ID.
     */
    suspend fun downloadBundle(
        baseUrl: String,
        entry: ChannelBundle,
        target: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        if (target.length() > entry.size) target.delete()
        try {
            if (target.length() < entry.size) fetchWithResume(url(baseUrl, entry.url).toString(), target, entry.size, onProgress)
            if (target.length() != entry.size) throw SignatureException("bundle ${entry.id}: size ${target.length()}, expected ${entry.size}")
            val sha = sha256Hex(target.readBytes())
            if (!sha.equals(entry.sha256, ignoreCase = true)) throw SignatureException("bundle ${entry.id}: SHA-256 mismatch")
            val sig = get(baseUrl, entry.sigUrl) ?: throw SignatureException("bundle ${entry.id}: no signature on the server")
            verifier.verify(target.readBytes(), String(sig, Charsets.UTF_8), "bundle ${entry.id}")
        } catch (e: NetworkException) {
            throw e // keep the partial file for resume
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    private fun fetchWithResume(url: String, target: File, expected: Long, onProgress: (Long, Long) -> Unit) {
        val have = target.length()
        val request = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        try {
            http.newCall(request).execute().use { response ->
                val append = response.code == 206
                if (!response.isSuccessful) throw NetworkException("GET $url: HTTP ${response.code}")
                RandomAccessFile(target, "rw").use { out ->
                    if (append) out.seek(have) else out.setLength(0)
                    var done = if (append) have else 0L
                    val buf = ByteArray(32 * 1024)
                    response.body!!.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done > expected) throw SignatureException("download larger than announced")
                            onProgress(done, expected)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw NetworkException("download: ${e.message}", e)
        }
    }
}
