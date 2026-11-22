package com.progeo.luaflasher.data

import com.progeo.core.security.SecretCipher
import com.progeo.luaflasher.core.bundle.BundleFormat
import com.progeo.luaflasher.core.config.DeviceConfigBuilder
import com.progeo.luaflasher.core.config.FieldKind
import com.progeo.luaflasher.core.config.LuaJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

/** Where a profile came from, when it was fetched from a controller (API2.md). */
@Serializable
data class RemoteSource(
    val locationId: String,
    val deviceId: Long,
    val rawHash: String? = null,
    val fetchedAt: String? = null,
    val commandId: Long? = null,
)

/**
 * A device configuration: the content of `device_config.lua` as JSON, so every value can be
 * edited. Fetched profiles are named after their `device.device_hash`. When flashing, the
 * bundle's template supplies defaults for anything the profile does not set.
 */
@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val config: JsonObject = JsonObject(emptyMap()),
    val source: RemoteSource? = null,
    /** When the config was read from a board over USB (ISO time), if it was. */
    val boardReadAt: String? = null,
) {
    val deviceHash: String?
        get() = (LuaJson.get(config, DeviceConfigBuilder.DEVICE_HASH) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}

/**
 * Profiles on disk. The config of each profile is encrypted as a whole with an Android
 * Keystore key: a fetched config contains the Wi-Fi password and the server token.
 */
class ProfileStore(
    private val file: File,
    private val cipher: SecretCipher,
    /** Field types from the template, to migrate format-1 profiles. */
    private val kindOf: (String) -> FieldKind? = { null },
) {

    @Serializable
    private data class StoredProfile(
        val id: String,
        val name: String,
        /** base64(iv + AES-GCM(config JSON)) */
        val config: String? = null,
        val source: RemoteSource? = null,
        val boardReadAt: String? = null,
        // format 1: dotted paths, secrets separately
        val values: Map<String, String> = emptyMap(),
        val secrets: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class StoredFile(val format: Int = 2, val profiles: List<StoredProfile>)

    private val mutex = Mutex()
    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    fun get(id: String?): Profile? = _profiles.value.firstOrNull { it.id == id }

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _profiles.value = if (file.isFile) {
                val stored = BundleFormat.json.decodeFromString<StoredFile>(file.readText())
                stored.profiles.map { p ->
                    val config = p.config?.let { BundleFormat.json.decodeFromString<JsonObject>(cipher.decrypt(it)) }
                        ?: fromDotted(p.values + p.secrets.mapValues { (_, v) -> cipher.decrypt(v) }, kindOf)
                    Profile(p.id, p.name, config, p.source, p.boardReadAt)
                }.sortedBy { it.name.lowercase() }
            } else {
                emptyList()
            }
        }
    }

    suspend fun save(profile: Profile) = write { list -> list.filter { it.id != profile.id } + profile }

    suspend fun delete(id: String) = write { list -> list.filter { it.id != id } }

    /**
     * Stores a config fetched from a controller, named after its device hash. An existing
     * profile of the same controller (or with the same name) is updated in place.
     */
    suspend fun saveFetched(config: JsonObject, source: RemoteSource): Profile {
        val hash = (LuaJson.get(config, DeviceConfigBuilder.DEVICE_HASH) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val name = hash ?: source.rawHash ?: "device ${source.deviceId}"
        val existing = _profiles.value.firstOrNull {
            it.source?.deviceId == source.deviceId && it.source.locationId == source.locationId
        } ?: _profiles.value.firstOrNull { it.name == name }
        val profile = Profile(id = existing?.id ?: UUID.randomUUID().toString(), name = name, config = config, source = source)
        save(profile)
        return profile
    }

    /**
     * Stores a config read from a board over USB, named after its device hash. A profile with
     * that name is updated in place.
     */
    suspend fun saveFromBoard(config: JsonObject): Profile {
        val hash = (LuaJson.get(config, DeviceConfigBuilder.DEVICE_HASH) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val now = java.time.OffsetDateTime.now().withNano(0).toString()
        val name = hash ?: "board "
        val existing = _profiles.value.firstOrNull { it.name == name }
        val profile = Profile(id = existing?.id ?: UUID.randomUUID().toString(), name = name, config = config, boardReadAt = now)
        save(profile)
        return profile
    }

    private suspend fun write(change: (List<Profile>) -> List<Profile>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = change(_profiles.value).sortedBy { it.name.lowercase() }
            val stored = StoredFile(
                profiles = updated.map { p ->
                    StoredProfile(
                        id = p.id,
                        name = p.name,
                        config = cipher.encrypt(BundleFormat.json.encodeToString(JsonObject.serializer(), p.config)),
                        source = p.source,
                        boardReadAt = p.boardReadAt,
                    )
                },
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

    companion object {
        const val DEVICE_HASH = DeviceConfigBuilder.DEVICE_HASH

        /** Format-1 profiles kept dotted paths as text; [kindOf] (the template) gives each value its type. */
        fun fromDotted(values: Map<String, String>, kindOf: (String) -> FieldKind?): JsonObject {
            var config = JsonObject(emptyMap())
            for ((path, text) in values) {
                if (text.isEmpty()) continue
                val value = LuaJson.typed(text, kindOf(path) ?: FieldKind.STRING) ?: JsonPrimitive(text)
                config = LuaJson.with(config, path, value)
            }
            return config
        }
    }
}
