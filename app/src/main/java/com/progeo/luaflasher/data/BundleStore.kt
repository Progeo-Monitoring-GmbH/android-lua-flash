package com.progeo.luaflasher.data

import com.progeo.luaflasher.core.BundleException
import com.progeo.luaflasher.core.bundle.Bundle
import com.progeo.luaflasher.core.bundle.BundleFormat
import com.progeo.luaflasher.core.bundle.BundleValidator
import com.progeo.luaflasher.core.io.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** A complete, verified bundle on the phone. */
@Serializable
data class StoredBundle(
    val id: String,
    val release: String,
    val firmware: String,
    val configSchema: Int,
    val sha256: String,
    val size: Long,
    /** Signature checked against the app's keys; false = developer import. */
    val signed: Boolean,
    val keyId: String? = null,
    val created: String? = null,
    val notes: String? = null,
    val storedAt: Long,
    val pinned: Boolean = false,
    val lfsModules: List<String> = emptyList(),
)

/**
 * `filesDir/bundles/<id>/` with `bundle.zip` and `meta.json` (PLAN.md section 4, step 1).
 * Bundles arrive through [add] only after their signature (or developer import) was checked.
 */
class BundleStore(private val root: File) {
    private val mutex = Mutex()
    private val _bundles = MutableStateFlow<List<StoredBundle>>(emptyList())
    val bundles: StateFlow<List<StoredBundle>> = _bundles.asStateFlow()

    init {
        root.mkdirs()
        _bundles.value = scan()
    }

    private fun scan(): List<StoredBundle> = root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .filter { dir ->
            // Left over from an import that was interrupted before the atomic rename.
            if (dir.name.startsWith(".tmp-")) dir.deleteRecursively()
            !dir.name.startsWith(".tmp-")
        }
        .mapNotNull { dir ->
            val meta = File(dir, META)
            val zip = File(dir, ZIP)
            if (!meta.isFile || !zip.isFile) {
                dir.deleteRecursively() // half-finished import
                return@mapNotNull null
            }
            runCatching { BundleFormat.json.decodeFromString<StoredBundle>(meta.readText()) }.getOrNull()
        }
        .sortedByDescending { it.created ?: "" }

    fun get(id: String): StoredBundle? = _bundles.value.firstOrNull { it.id == id }

    /**
     * Validates [zip] (contents against bundle.json) and moves it into the store atomically.
     * The caller has already checked size, hash and signature of a download.
     */
    suspend fun add(zip: File, signed: Boolean, keyId: String?, notes: String?, expectedId: String? = null): StoredBundle =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val bytes = zip.readBytes()
                val bundle = BundleValidator.validate(bytes)
                val manifest = bundle.manifest
                if (expectedId != null && manifest.bundleId != expectedId) {
                    throw BundleException("The bundle is damaged.", "bundle.json says ${manifest.bundleId}, channel says $expectedId")
                }
                if (!SAFE_ID.matches(manifest.bundleId)) throw BundleException("The bundle is damaged.", "bad bundle id ${manifest.bundleId}")
                val existing = get(manifest.bundleId)
                val stored = StoredBundle(
                    id = manifest.bundleId,
                    release = manifest.release,
                    firmware = manifest.firmware.version,
                    configSchema = manifest.configSchema,
                    sha256 = sha256Hex(bytes),
                    size = bytes.size.toLong(),
                    signed = signed,
                    keyId = keyId,
                    created = manifest.created,
                    notes = notes?.takeIf { it.isNotBlank() },
                    storedAt = System.currentTimeMillis(),
                    pinned = existing?.pinned ?: false,
                    lfsModules = manifest.lfs.modules,
                )
                val tmp = File(root, ".tmp-${manifest.bundleId}")
                tmp.deleteRecursively()
                tmp.mkdirs()
                zip.copyTo(File(tmp, ZIP), overwrite = true)
                File(tmp, META).writeText(BundleFormat.json.encodeToString(StoredBundle.serializer(), stored))
                val dir = File(root, manifest.bundleId)
                dir.deleteRecursively()
                if (!tmp.renameTo(dir)) throw BundleException("The bundle could not be saved.", "rename failed")
                _bundles.value = scan()
                stored
            }
        }

    /** Loads a stored bundle for flashing: re-checks the zip's SHA-256, then its contents. */
    suspend fun load(id: String): Bundle = withContext(Dispatchers.IO) {
        val meta = get(id) ?: throw BundleException("The bundle is no longer on the phone.", id)
        val bytes = File(File(root, id), ZIP).readBytes()
        if (sha256Hex(bytes) != meta.sha256) {
            throw BundleException("The stored bundle is damaged. Delete it and download it again.", "SHA-256 of $id changed")
        }
        BundleValidator.validate(bytes)
    }

    suspend fun setPinned(id: String, pinned: Boolean) = update(id) { it.copy(pinned = pinned) }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            File(root, id).deleteRecursively()
            _bundles.value = scan()
        }
    }

    /** Keeps [latestId], pinned bundles and the [keepOthers] most recent others (PLAN.md section 4). */
    suspend fun prune(latestId: String?, keepOthers: Int = 2) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val others = _bundles.value.filter { it.id != latestId && !it.pinned }.sortedByDescending { it.created ?: "" }
            others.drop(keepOthers).forEach { File(root, it.id).deleteRecursively() }
            _bundles.value = scan()
        }
    }

    private suspend fun update(id: String, change: (StoredBundle) -> StoredBundle) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val meta = get(id) ?: return@withLock
            File(File(root, id), META).writeText(BundleFormat.json.encodeToString(StoredBundle.serializer(), change(meta)))
            _bundles.value = scan()
        }
    }

    companion object {
        private const val ZIP = "bundle.zip"
        private const val META = "meta.json"
        private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,100}")
    }
}
