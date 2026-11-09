package com.progeo.luaflasher.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.progeo.luaflasher.core.FlasherException
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
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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

    suspend fun importAll(imported: List<Profile>) = write { list ->
        val names = list.map { it.name }.toMutableSet()
        list + imported.map { p ->
            var name = p.name
            while (name in names) name = "$name (imported)"
            names += name
            p.copy(id = UUID.randomUUID().toString(), name = name)
        }
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

/** AES-GCM with a non-exportable key in the Android Keystore. */
class SecretCipher(private val alias: String = "profile-secrets") {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + ct)
    }

    fun decrypt(encoded: String): String {
        val bytes = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }
}

class ProfileImportException(detail: String) : FlasherException("The file could not be opened. Check the passphrase.", detail)

/** Passphrase-encrypted export of profiles (PBKDF2-HMAC-SHA256 + AES-256-GCM). */
object ProfileExport {
    @Serializable
    private data class Envelope(
        val format: Int = 2,
        val kdf: String = "PBKDF2WithHmacSHA256",
        val iterations: Int,
        val salt: String,
        val iv: String,
        val data: String,
    )

    @Serializable
    private data class Payload(val profiles: List<Profile>)

    @Serializable
    private data class LegacyProfile(val name: String, val values: Map<String, String> = emptyMap())

    @Serializable
    private data class LegacyPayload(val profiles: List<LegacyProfile>)

    private const val ITERATIONS = 310_000

    private fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, "AES")
    }

    fun export(profiles: List<Profile>, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(passphrase, salt, ITERATIONS), GCMParameterSpec(128, iv))
        val plain = BundleFormat.json.encodeToString(Payload.serializer(), Payload(profiles))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        val envelope = Envelope(iterations = ITERATIONS, salt = b64.encodeToString(salt), iv = b64.encodeToString(iv), data = b64.encodeToString(ct))
        return BundleFormat.json.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
    }

    fun import(bytes: ByteArray, passphrase: CharArray, kindOf: (String) -> FieldKind? = { null }): List<Profile> {
        val envelope = try {
            BundleFormat.json.decodeFromString<Envelope>(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ProfileImportException("not a profile export: ${e.message}")
        }
        if (envelope.format !in 1..2 || envelope.kdf != "PBKDF2WithHmacSHA256") throw ProfileImportException("unsupported format")
        val b64 = Base64.getDecoder()
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(passphrase, b64.decode(envelope.salt), envelope.iterations), GCMParameterSpec(128, b64.decode(envelope.iv)))
            String(cipher.doFinal(b64.decode(envelope.data)), Charsets.UTF_8)
        } catch (e: Exception) {
            throw ProfileImportException("decryption failed: ${e.message}")
        }
        return if (envelope.format == 1) {
            BundleFormat.json.decodeFromString<LegacyPayload>(plain).profiles.map { Profile(name = it.name, config = ProfileStore.fromDotted(it.values, kindOf)) }
        } else {
            BundleFormat.json.decodeFromString<Payload>(plain).profiles
        }
    }
}
