package com.progeo.teltonika.firmware

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A RutOS version such as `RUT2M_R_00.07.06.1`: family (`RUT2M`), type (`R` release) and the
 * numbers. A bare `00.07.06.1` or `7.6.1` has no family.
 */
data class FirmwareVersion(val family: String?, val type: String?, val numbers: List<Int>, val text: String) :
    Comparable<FirmwareVersion> {

    override fun compareTo(other: FirmwareVersion): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val c = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** `07.06.1`: the part people talk about. */
    val short: String
        get() = numbers.dropWhile { it == 0 }.mapIndexed { i, n -> if (i < 2) "%02d".format(n) else "$n" }.joinToString(".").ifEmpty { text }

    override fun toString() = text

    companion object {
        private val FULL = Regex("^([A-Z0-9]+)_([A-Z]+)_([0-9.]+)", RegexOption.IGNORE_CASE)
        private val BARE = Regex("^[0-9]+(\\.[0-9]+)+$")

        fun parse(text: String): FirmwareVersion? {
            val t = text.trim()
            FULL.find(t)?.let { m ->
                val numbers = m.groupValues[3].trim('.').split('.').mapNotNull { it.toIntOrNull() }
                if (numbers.isNotEmpty()) return FirmwareVersion(m.groupValues[1].uppercase(), m.groupValues[2].uppercase(), numbers, t)
            }
            if (BARE.matches(t)) return FirmwareVersion(null, null, t.split('.').map { it.toInt() }, t)
            return null
        }
    }
}

/**
 * The router firmware channel from our backend (`routers/firmware.json` + `.sig`, signed like
 * the bundle channel). Lists the images we deliver and which one is approved for each model.
 */
@Serializable
data class FirmwareChannel(
    val format: Int = 1,
    val updated: String? = null,
    val images: List<FirmwareImage> = emptyList(),
    val approved: List<Approval> = emptyList(),
) {
    fun image(id: String) = images.firstOrNull { it.id == id }

    /** The approved image for [model] (e.g. `RUT241`), preferring an entry for [productCode]'s variant. */
    fun approvedFor(model: String, productCode: String? = null): FirmwareImage? {
        val forModel = approved.filter { it.model.equals(model, ignoreCase = true) }
        val specific = productCode?.let { code -> forModel.firstOrNull { a -> a.productCodePrefix?.let { code.startsWith(it, ignoreCase = true) } == true } }
        return (specific ?: forModel.firstOrNull { it.productCodePrefix == null })?.let { image(it.image) }
    }

    /**
     * For a router that does not report its model: the approved image of its current firmware
     * family (`RUT2M` from `RUT2M_R_00.07.06.1`). Null when no image or several different ones
     * are approved for that family; variant approvals (product code prefix) are left out.
     */
    fun approvedForFamily(family: String): FirmwareImage? =
        approved.filter { it.productCodePrefix == null }
            .mapNotNull { image(it.image) }
            .filter { it.family.equals(family, ignoreCase = true) }
            .distinctBy { it.id }
            .singleOrNull()

    companion object {
        const val SUPPORTED_FORMAT = 1
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun parse(bytes: ByteArray): FirmwareChannel {
            val channel = json.decodeFromString<FirmwareChannel>(String(bytes, Charsets.UTF_8))
            require(channel.format <= SUPPORTED_FORMAT) { "firmware channel format ${channel.format} is newer than this app" }
            return channel
        }
    }
}

@Serializable
data class FirmwareImage(
    val id: String,
    /** `RUT2M_R_00.07.18`, as the router reports it after the upgrade. */
    val version: String,
    /** `RUT2M`, `RUT9M`, …: must match the family of the router's current firmware. */
    val family: String,
    /** Models this image is for (`RUT241`, …). */
    val models: List<String>,
    /** Relative to the firmware base URL. */
    val url: String,
    val size: Long,
    val sha256: String,
    /** The oldest version that may upgrade straight to this one. */
    @SerialName("min_from") val minFrom: String? = null,
    val notes: String = "",
) {
    val parsedVersion: FirmwareVersion? get() = FirmwareVersion.parse(version)
}

@Serializable
data class Approval(
    val model: String,
    val image: String,
    /** Only for product codes starting with this (e.g. a US carrier variant of the RUT241). */
    @SerialName("product_code_prefix") val productCodePrefix: String? = null,
)

/** Result of checking an image against a router before uploading it. */
sealed interface FirmwareCheck {
    data object AlreadyInstalled : FirmwareCheck

    /** Fine to install; [downgrade] asks for a confirmation. */
    data class Ok(val downgrade: Boolean) : FirmwareCheck

    data class Refused(val reason: String) : FirmwareCheck
}

object FirmwarePolicy {
    /**
     * Checks [image] against the router ([model], [current] firmware). The family must match, the
     * model must be listed, the current version must be at least `min_from`. Without a [model] the
     * family of the current firmware decides: it names the hardware platform the image is built for.
     */
    fun check(image: FirmwareImage, model: String?, current: FirmwareVersion?): FirmwareCheck {
        if (model != null && image.models.none { it.equals(model, ignoreCase = true) }) {
            return FirmwareCheck.Refused("This firmware is not for the $model.")
        }
        if (current == null) return FirmwareCheck.Refused("The router did not report its firmware version.")
        if (model == null && current.family == null) {
            return FirmwareCheck.Refused("The router reported neither its model nor its firmware family.")
        }
        if (current.family != null && !current.family.equals(image.family, ignoreCase = true)) {
            return FirmwareCheck.Refused("This firmware is for ${image.family}, the router runs ${current.family}.")
        }
        val target = image.parsedVersion ?: return FirmwareCheck.Refused("The firmware version '${image.version}' is not readable.")
        if (target.compareTo(current) == 0) return FirmwareCheck.AlreadyInstalled
        image.minFrom?.let(FirmwareVersion::parse)?.let { min ->
            if (current < min) return FirmwareCheck.Refused("Update to ${min.short} first; ${current.short} cannot go straight to ${target.short}.")
        }
        return FirmwareCheck.Ok(downgrade = target < current)
    }
}
