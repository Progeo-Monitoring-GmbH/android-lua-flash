package com.progeo.luaflasher.m5

import com.progeo.core.security.SecretCipher
import com.progeo.luaflasher.core.bundle.BundleFormat
import com.progeo.luaflasher.core.m5.M5Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

/** Where an M5 profile came from. */
enum class M5Source { QR, DEVICE }

/**
 * The config.py of one M5, named after its device hash. Comes from the backend's M5 config QR code
 * (device provisioning) or is read from an M5 over USB.
 */
data class M5Profile(
    val id: String,
    val name: String,
    val config: M5Config,
    val source: M5Source,
    /** ISO time of the scan or the read. */
    val savedAt: String,
) {
    val deviceHash: String? get() = config.deviceHash
}

/**
 * M5 profiles on disk, next to the LUA profiles but separate (another file format). Each config is
 * encrypted with an Android Keystore key: it contains the device token.
 */
class M5ProfileStore(private val file: File, private val cipher: SecretCipher) {

    @Serializable
    private data class Stored(val id: String, val name: String, val config: String, val source: String, val savedAt: String)

    @Serializable
    private data class StoredFile(val format: Int = 1, val profiles: List<Stored>)

    private val mutex = Mutex()
    private val _profiles = MutableStateFlow<List<M5Profile>>(emptyList())
    val profiles: StateFlow<List<M5Profile>> = _profiles.asStateFlow()

    fun get(id: String?): M5Profile? = _profiles.value.firstOrNull { it.id == id }

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _profiles.value = if (file.isFile) {
                BundleFormat.json.decodeFromString<StoredFile>(file.readText()).profiles.mapNotNull { p ->
                    // A profile that no longer parses is dropped rather than blocking all others.
                    runCatching {
                        M5Profile(p.id, p.name, M5Config.parse(cipher.decrypt(p.config)), M5Source.valueOf(p.source), p.savedAt)
                    }.getOrNull()
                }.sortedBy { it.name.lowercase() }
            } else {
                emptyList()
            }
        }
    }

    /**
     * Stores [config] as the profile of its device hash: a profile with that name is updated in
     * place (a new QR code or a newer read replaces the old values).
     */
    suspend fun saveConfig(config: M5Config, source: M5Source): M5Profile {
        val now = OffsetDateTime.now().withNano(0).toString()
        val name = config.deviceHash ?: "M5 $now"
        val existing = _profiles.value.firstOrNull { it.name == name }
        val profile = M5Profile(existing?.id ?: UUID.randomUUID().toString(), name, config, source, now)
        write { list -> list.filter { it.id != profile.id } + profile }
        return profile
    }

    suspend fun delete(id: String) = write { list -> list.filter { it.id != id } }

    private suspend fun write(change: (List<M5Profile>) -> List<M5Profile>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = change(_profiles.value).sortedBy { it.name.lowercase() }
            val stored = StoredFile(
                profiles = updated.map { Stored(it.id, it.name, cipher.encrypt(it.config.render()), it.source.name, it.savedAt) },
            )
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(BundleFormat.json.encodeToString(StoredFile.serializer(), stored))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            _profiles.value = updated
        }
    }
}
