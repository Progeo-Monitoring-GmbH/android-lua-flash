package com.progeo.luaflasher.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.bundle.BundleFormat
import com.progeo.luaflasher.core.config.ConfigField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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

/**
 * A project profile (PLAN.md section 7): the device_config.lua values shared by all boards
 * of a project. `device.device_hash` is entered per device at flash time and never stored here.
 */
@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Dotted template paths to values; secrets included in clear text in memory only. */
    val values: Map<String, String> = emptyMap(),
)

/** Profiles on disk; secret fields encrypted with an Android Keystore key. */
class ProfileStore(private val file: File, private val cipher: SecretCipher) {

    @Serializable
    private data class StoredProfile(
        val id: String,
        val name: String,
        val values: Map<String, String>,
        /** path -> base64(iv + ciphertext) */
        val secrets: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class StoredFile(val format: Int = 1, val profiles: List<StoredProfile>)

    private val mutex = Mutex()
    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _profiles.value = if (file.isFile) {
                val stored = BundleFormat.json.decodeFromString<StoredFile>(file.readText())
                stored.profiles.map { p ->
                    Profile(p.id, p.name, p.values + p.secrets.mapValues { (_, v) -> cipher.decrypt(v) })
                }.sortedBy { it.name.lowercase() }
            } else {
                emptyList()
            }
        }
    }

    suspend fun save(profile: Profile) = write { list -> list.filter { it.id != profile.id } + profile }

    suspend fun delete(id: String) = write { list -> list.filter { it.id != id } }

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
                    val values = p.values.filterValues { it.isNotEmpty() } - DEVICE_HASH
                    StoredProfile(
                        id = p.id,
                        name = p.name,
                        values = values.filterKeys { it !in ConfigField.SECRET_PATHS },
                        secrets = values.filterKeys { it in ConfigField.SECRET_PATHS }.mapValues { (_, v) -> cipher.encrypt(v) },
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
        const val DEVICE_HASH = "device.device_hash"
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
        val format: Int = 1,
        val kdf: String = "PBKDF2WithHmacSHA256",
        val iterations: Int,
        val salt: String,
        val iv: String,
        val data: String,
    )

    @Serializable
    private data class Payload(val profiles: List<Profile>)

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
        val plain = BundleFormat.json.encodeToString(Payload.serializer(), Payload(profiles.map { it.copy(values = it.values - ProfileStore.DEVICE_HASH) }))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        val envelope = Envelope(iterations = ITERATIONS, salt = b64.encodeToString(salt), iv = b64.encodeToString(iv), data = b64.encodeToString(ct))
        return BundleFormat.json.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
    }

    fun import(bytes: ByteArray, passphrase: CharArray): List<Profile> {
        val envelope = try {
            BundleFormat.json.decodeFromString<Envelope>(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ProfileImportException("not a profile export: ${e.message}")
        }
        if (envelope.format != 1 || envelope.kdf != "PBKDF2WithHmacSHA256") throw ProfileImportException("unsupported format")
        val b64 = Base64.getDecoder()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(passphrase, b64.decode(envelope.salt), envelope.iterations), GCMParameterSpec(128, b64.decode(envelope.iv)))
            val plain = String(cipher.doFinal(b64.decode(envelope.data)), Charsets.UTF_8)
            BundleFormat.json.decodeFromString<Payload>(plain).profiles
        } catch (e: Exception) {
            throw ProfileImportException("decryption failed: ${e.message}")
        }
    }
}
