package com.progeo.luaflasher.core.bundle

import com.progeo.luaflasher.core.AppTooOldException
import com.progeo.luaflasher.core.BundleException
import com.progeo.luaflasher.core.io.sha256Hex
import kotlinx.serialization.SerializationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** A bundle whose zip was read and whose contents match bundle.json. */
class Bundle(
    val manifest: BundleManifest,
    private val entries: Map<String, ByteArray>,
) {
    fun bytes(zipPath: String): ByteArray = entries[zipPath] ?: throw BundleException("The bundle is incomplete.", "missing $zipPath")

    val template: String get() = String(bytes(manifest.deviceConfigTemplate), Charsets.UTF_8)
}

object ZipSafety {
    /** Entry names allowed in a bundle: relative, forward slashes, no `..`, no drive letters. */
    fun checkEntryName(name: String) {
        val bad = name.isEmpty() ||
            name.startsWith("/") || name.startsWith("\\") ||
            '\\' in name || '\u0000' in name ||
            Regex("^[A-Za-z]:").containsMatchIn(name) ||
            name.split('/').any { it == ".." || it == "." }
        if (bad) throw BundleException("The bundle contains an unsafe file name.", "zip entry '$name'")
    }

    /** Reads all entries into memory, rejecting unsafe names, duplicates and oversize content. */
    fun readEntries(zip: ByteArray, maxTotal: Long = MAX_UNCOMPRESSED): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val entry = zin.nextEntry ?: break
                    checkEntryName(entry.name)
                    if (entry.isDirectory) continue
                    if (entry.name in entries) throw BundleException("The bundle is damaged.", "duplicate zip entry ${entry.name}")
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxTotal) throw BundleException("The bundle is too large.", "more than $maxTotal bytes uncompressed")
                        out.write(buf, 0, n)
                    }
                    entries[entry.name] = out.toByteArray()
                }
            }
        } catch (e: ZipException) {
            throw BundleException("The bundle is damaged.", "zip: ${e.message}")
        }
        if (entries.isEmpty()) throw BundleException("The bundle is damaged.", "empty or not a zip")
        return entries
    }

    const val MAX_UNCOMPRESSED = 32L * 1024 * 1024
}

/** Checks a bundle zip against its bundle.json (PLAN.md section 3, "Bundle zip"). */
object BundleValidator {

    fun validate(zip: ByteArray): Bundle {
        val entries = ZipSafety.readEntries(zip)
        val manifestBytes = entries["bundle.json"] ?: throw BundleException("The bundle is damaged.", "bundle.json missing")
        val manifest = parseManifest(String(manifestBytes, Charsets.UTF_8))

        fun check(file: String, size: Long, sha256: String) {
            ZipSafety.checkEntryName(file)
            val data = entries[file] ?: throw BundleException("The bundle is incomplete.", "missing $file")
            if (data.size.toLong() != size) throw BundleException("The bundle is damaged.", "$file: size ${data.size}, expected $size")
            if (!sha256Hex(data).equals(sha256, ignoreCase = true)) throw BundleException("The bundle is damaged.", "$file: SHA-256 mismatch")
        }

        if (manifest.firmware.segments.isEmpty()) throw BundleException("The bundle is damaged.", "no firmware segments")
        var lastEnd = -1L
        for (segment in manifest.firmware.segments.sortedBy { addressOf(it) }) {
            check(segment.file, segment.size, segment.sha256)
            val start = addressOf(segment)
            if (start % 0x1000 != 0L) throw BundleException("The bundle is damaged.", "segment ${segment.address} not sector aligned")
            if (start < lastEnd) throw BundleException("The bundle is damaged.", "segments overlap at ${segment.address}")
            lastEnd = start + segment.size
        }
        check(manifest.lfs.file, manifest.lfs.size, manifest.lfs.sha256)
        if (manifest.lfs.modules.isEmpty()) throw BundleException("The bundle is damaged.", "lfs.modules is empty")
        for (file in manifest.files) {
            checkDevicePath(file.path)
            check(file.file, file.size, file.sha256)
        }
        checkDevicePath(manifest.init.path)
        check(manifest.init.file, manifest.init.size, manifest.init.sha256)
        ZipSafety.checkEntryName(manifest.deviceConfigTemplate)
        if (manifest.deviceConfigTemplate !in entries) throw BundleException("The bundle is incomplete.", "missing ${manifest.deviceConfigTemplate}")
        return Bundle(manifest, entries)
    }

    fun parseManifest(json: String): BundleManifest {
        val manifest = try {
            BundleFormat.json.decodeFromString<BundleManifest>(json)
        } catch (e: SerializationException) {
            throw BundleException("The bundle is damaged.", "bundle.json: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw BundleException("The bundle is damaged.", "bundle.json: ${e.message}")
        }
        if (manifest.format != BundleFormat.FORMAT) {
            throw AppTooOldException("bundle.json format ${manifest.format}")
        }
        if (manifest.configSchema > BundleFormat.SUPPORTED_CONFIG_SCHEMA) {
            throw AppTooOldException("config_schema ${manifest.configSchema} > ${BundleFormat.SUPPORTED_CONFIG_SCHEMA}")
        }
        return manifest
    }

    private fun addressOf(segment: FirmwareSegment): Long = try {
        segment.addressValue
    } catch (e: IllegalArgumentException) {
        throw BundleException("The bundle is damaged.", "bad segment address ${segment.address}")
    }

    private fun checkDevicePath(path: String) {
        if (path.isEmpty() || path.startsWith("/") || ".." in path.split('/') || path.length > 31) {
            throw BundleException("The bundle is damaged.", "bad device path '$path'")
        }
    }
}

/** Parses a channel file whose signature was already checked. */
object ChannelParser {
    fun parse(bytes: ByteArray): ChannelFile {
        val channel = try {
            BundleFormat.json.decodeFromString<ChannelFile>(String(bytes, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw BundleException("The release list is damaged.", "channel: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw BundleException("The release list is damaged.", "channel: ${e.message}")
        }
        if (channel.format != BundleFormat.FORMAT) throw AppTooOldException("channel format ${channel.format}")
        for (b in channel.bundles) {
            if (b.url.startsWith("/") || "://" in b.url || ".." in b.url.split('/') ||
                b.sigUrl.startsWith("/") || "://" in b.sigUrl || ".." in b.sigUrl.split('/')
            ) {
                throw BundleException("The release list is damaged.", "bundle ${b.id}: url must be relative to the base URL")
            }
        }
        return channel
    }
}
