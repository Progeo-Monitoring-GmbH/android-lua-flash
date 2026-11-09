package com.progeo.luaflasher.backend

import com.progeo.luaflasher.data.SecretCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.Base64

/** The JWT pair of the backend (API.md). Both change on every refresh. */
@Serializable
data class Tokens(val access: String, val refresh: String)

interface TokenStore {
    fun load(): Tokens?
    fun save(tokens: Tokens)
    fun clear()
}

class InMemoryTokenStore(private var tokens: Tokens? = null) : TokenStore {
    @Synchronized override fun load() = tokens
    @Synchronized override fun save(tokens: Tokens) { this.tokens = tokens }
    @Synchronized override fun clear() { tokens = null }
}

/**
 * Tokens encrypted with an Android Keystore key, in `noBackupFilesDir` so they never go into
 * a backup (API.md "Token storage on Android"). The password is never stored.
 */
class EncryptedTokenStore(private val file: File, private val cipher: SecretCipher) : TokenStore {
    @Volatile private var cached: Tokens? = null
    @Volatile private var loaded = false

    @Synchronized
    override fun load(): Tokens? {
        if (!loaded) {
            cached = runCatching { Json.decodeFromString<Tokens>(cipher.decrypt(file.readText())) }.getOrNull()
            loaded = true
        }
        return cached
    }

    @Synchronized
    override fun save(tokens: Tokens) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(cipher.encrypt(Json.encodeToString(Tokens.serializer(), tokens)))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        cached = tokens
        loaded = true
    }

    @Synchronized
    override fun clear() {
        file.delete()
        cached = null
        loaded = true
    }
}

/** Claims of the access token, for the UI only; the server enforces everything itself. */
data class Claims(
    val username: String?,
    val exp: Long?,
    val isStaff: Boolean,
    val isDemo: Boolean,
    val isPending: Boolean,
    val twoFactorSetupRequired: Boolean,
) {
    companion object {
        fun of(accessToken: String): Claims? = runCatching {
            val payload = accessToken.split('.')[1]
            val json = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))) as JsonObject
            fun flag(name: String) = json[name]?.jsonPrimitive?.booleanOrNull ?: false
            Claims(
                username = json["username"]?.jsonPrimitive?.content,
                exp = json["exp"]?.jsonPrimitive?.longOrNull,
                isStaff = flag("is_staff"),
                isDemo = flag("is_demo"),
                isPending = flag("is_pending"),
                twoFactorSetupRequired = flag("two_factor_setup_required"),
            )
        }.getOrNull()
    }
}
