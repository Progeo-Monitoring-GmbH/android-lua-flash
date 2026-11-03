package com.progeo.luaflasher.core.bundle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `versions/<channel>` (PLAN.md section 3). */
@Serializable
data class ChannelFile(
    val format: Int,
    val channel: String,
    val updated: String? = null,
    @SerialName("min_app_version") val minAppVersion: Int = 1,
    val latest: String? = null,
    val bundles: List<ChannelBundle> = emptyList(),
) {
    val latestBundle: ChannelBundle? get() = bundles.firstOrNull { it.id == latest }
}

@Serializable
data class ChannelBundle(
    val id: String,
    val release: String,
    val firmware: String,
    @SerialName("config_schema") val configSchema: Int,
    val url: String,
    val size: Long,
    val sha256: String,
    @SerialName("sig_url") val sigUrl: String,
    val created: String? = null,
    val notes: String? = null,
)

/** `bundle.json` inside the zip. */
@Serializable
data class BundleManifest(
    val format: Int,
    @SerialName("bundle_id") val bundleId: String,
    val release: String,
    @SerialName("config_schema") val configSchema: Int,
    @SerialName("git_commit") val gitCommit: String? = null,
    val created: String? = null,
    val firmware: FirmwareInfo,
    val lfs: LfsInfo,
    val files: List<BundleFile> = emptyList(),
    val init: BundleFile,
    @SerialName("device_config_template") val deviceConfigTemplate: String,
)

@Serializable
data class FirmwareInfo(
    val version: String,
    @SerialName("lfs_size") val lfsSize: String? = null,
    val segments: List<FirmwareSegment>,
)

@Serializable
data class FirmwareSegment(
    val address: String,
    val file: String,
    val size: Long,
    val sha256: String,
) {
    val addressValue: Long get() = parseHex(address)
}

@Serializable
data class LfsInfo(
    val file: String,
    val size: Long,
    val sha256: String,
    val modules: List<String> = emptyList(),
)

@Serializable
data class BundleFile(
    /** Path on the device. */
    val path: String,
    /** Path inside the zip. */
    val file: String,
    val size: Long,
    val sha256: String,
)

internal fun parseHex(text: String): Long {
    val t = text.trim()
    require(t.startsWith("0x") || t.startsWith("0X")) { "not a hex address: $text" }
    return t.substring(2).toLong(16)
}

object BundleFormat {
    /** Formats of channel files and bundle.json this app understands. */
    const val FORMAT = 1

    /** Highest config_schema this app can write a device config for. */
    const val SUPPORTED_CONFIG_SCHEMA = 1

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}
