package com.progeo.luaflasher.core.m5

import com.progeo.luaflasher.core.BundleException
import com.progeo.luaflasher.core.bundle.BundleFormat
import com.progeo.luaflasher.core.bundle.ZipSafety
import com.progeo.luaflasher.core.io.sha256Hex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Releases of the M5Stack MicroPython firmware, published by the m5-stack CI
 * (`.github/workflows/release.yml`, `mPy/tools/release_bundle.py`) into the channel
 * [M5Bundles.CHANNEL] next to the LUA_WLAN channels: `versions/mstack` + `.sig`, signed with the
 * same Ed25519 keys. Not the LUA_WLAN bundle format: no LFS, no ROM flashing; a release is the set
 * of `.py` files and fonts the firmware runs from, copied over the MicroPython REPL.
 */
@Serializable
data class M5Channel(
    val format: Int,
    val channel: String,
    val device: String? = null,
    val updated: String? = null,
    @SerialName("min_app_version") val minAppVersion: Int = 1,
    val latest: String? = null,
    val bundles: List<M5ChannelBundle> = emptyList(),
) {
    val latestBundle: M5ChannelBundle? get() = bundles.firstOrNull { it.id == latest }
}

@Serializable
data class M5ChannelBundle(
    /** `m5-ain4-mpy-<firmware>`, what the device reports as BUNDLE. */
    val id: String,
    /** Git tag, `v<firmware>`. */
    val release: String,
    val firmware: String,
    val url: String,
    val size: Long,
    val sha256: String,
    @SerialName("sig_url") val sigUrl: String,
    val created: String? = null,
    val notes: String? = null,
)

/** `bundle.json` inside an M5 release zip. */
@Serializable
data class M5Manifest(
    val format: Int,
    val device: String,
    val runtime: String? = null,
    @SerialName("bundle_id") val bundleId: String,
    val release: String,
    val firmware: String,
    val commit: String? = null,
    val created: String? = null,
    @SerialName("app_dir") val appDir: String,
    @SerialName("config_path") val configPath: String,
    /** Always written. */
    val files: List<M5File>,
    /** `/flash/main.py` that starts the firmware at boot; written only when asked. */
    val autostart: M5File? = null,
    /** Python that starts the firmware in the foreground. */
    val run: String? = null,
)

@Serializable
data class M5File(
    /** Path on the device. */
    val path: String,
    /** Path inside the zip. */
    val file: String,
    val size: Long,
    val sha256: String,
)

/** A checked release zip: every file is present with its size and SHA-256. */
class M5Bundle(val manifest: M5Manifest, private val entries: Map<String, ByteArray>) {
    fun bytes(file: M5File): ByteArray = entries.getValue(file.file)

    val totalBytes: Long get() = manifest.files.sumOf { it.size }
}

object M5Bundles {
    const val CHANNEL = "mstack"
    const val DEVICE = "m5-ain4"
    const val FORMAT = 1

    /** Device paths a release may write: the firmware's directories on the flash file system. */
    private val ALLOWED_PREFIXES = listOf("/flash/progeo/", "/flash/res/")
    private const val AUTOSTART_PATH = "/flash/main.py"
    private val SAFE_PATH = Regex("^/[A-Za-z0-9._/-]+$")

    fun parseChannel(bytes: ByteArray): M5Channel {
        val channel = try {
            BundleFormat.json.decodeFromString<M5Channel>(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BundleException("The M5 release list is damaged.", e.message)
        }
        if (channel.format != FORMAT) throw BundleException("The M5 release list needs a newer app.", "channel format ${channel.format}")
        if (channel.device != null && channel.device != DEVICE) {
            throw BundleException("The M5 release list is for another device.", "device ${channel.device}")
        }
        return channel
    }

    /** Opens and checks a release zip (already checked against the channel's size, hash and signature). */
    fun open(zip: ByteArray): M5Bundle {
        val entries = ZipSafety.readEntries(zip)
        val json = entries["bundle.json"] ?: throw BundleException("This is not an M5 release.", "bundle.json missing")
        val manifest = try {
            BundleFormat.json.decodeFromString<M5Manifest>(String(json, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BundleException("The M5 release is damaged.", "bundle.json: ${e.message}")
        }
        if (manifest.format != FORMAT) throw BundleException("This M5 release needs a newer app.", "bundle format ${manifest.format}")
        if (manifest.device != DEVICE) throw BundleException("This release is not for the M5 AIN4.", "device ${manifest.device}")
        if (manifest.files.isEmpty()) throw BundleException("The M5 release is empty.")
        if (manifest.configPath != M5Config.DEVICE_PATH) {
            throw BundleException("This M5 release needs a newer app.", "config_path ${manifest.configPath}")
        }
        val all = manifest.files + listOfNotNull(manifest.autostart)
        for (f in all) {
            checkPath(f.path, isAutostart = f === manifest.autostart)
            val data = entries[f.file] ?: throw BundleException("The M5 release is incomplete.", "missing ${f.file}")
            if (data.size.toLong() != f.size || !sha256Hex(data).equals(f.sha256, ignoreCase = true)) {
                throw BundleException("The M5 release is damaged.", "${f.file}: size or SHA-256 mismatch")
            }
        }
        if (all.map { it.path }.toSet().size != all.size) throw BundleException("The M5 release is damaged.", "duplicate device paths")
        if (manifest.files.any { it.path == M5Config.DEVICE_PATH }) {
            throw BundleException("The M5 release is damaged.", "a release must never overwrite config.py")
        }
        return M5Bundle(manifest, entries)
    }

    private fun checkPath(path: String, isAutostart: Boolean) {
        val ok = SAFE_PATH.matches(path) && ".." !in path && "//" !in path &&
            if (isAutostart) path == AUTOSTART_PATH else ALLOWED_PREFIXES.any { path.startsWith(it) }
        if (!ok) throw BundleException("The M5 release is damaged.", "device path not allowed: $path")
    }
}
