package com.progeo.luaflasher.core.bundle

import com.google.crypto.tink.subtle.Ed25519Verify
import com.progeo.luaflasher.core.SignatureException
import kotlinx.serialization.Serializable
import java.security.GeneralSecurityException
import java.util.Base64

/** `keys/firmware-signing.pub.json`: `{"keys":[{"id":"main","ed25519":"<base64 raw 32 bytes>"}]}`. */
class PublicKeys(val keys: Map<String, ByteArray>) {

    @Serializable
    private data class KeyFile(val keys: List<Key>)

    @Serializable
    private data class Key(val id: String, val ed25519: String)

    companion object {
        fun parse(json: String): PublicKeys {
            val file = BundleFormat.json.decodeFromString<KeyFile>(json)
            val keys = file.keys.associate { key ->
                val raw = Base64.getDecoder().decode(key.ed25519)
                require(raw.size == 32) { "key ${key.id}: not a 32-byte Ed25519 key" }
                key.id to raw
            }
            require(keys.isNotEmpty()) { "no public keys" }
            return PublicKeys(keys)
        }
    }
}

/**
 * Checks `.sig` files: `<key id>:<base64 of the 64-byte Ed25519 signature>` over the exact
 * bytes of the file (PLAN.md section 3). Unknown key IDs are rejected.
 */
class SignatureVerifier(private val keys: PublicKeys) {

    /** Returns the key ID that signed [data]; throws [SignatureException] otherwise. */
    fun verify(data: ByteArray, sigFile: String, what: String = "file"): String {
        val text = sigFile.trim()
        val colon = text.indexOf(':')
        if (colon <= 0) throw SignatureException("$what: malformed signature file")
        val keyId = text.substring(0, colon)
        val key = keys.keys[keyId] ?: throw SignatureException("$what: unknown signing key '$keyId'")
        val signature = try {
            Base64.getDecoder().decode(text.substring(colon + 1))
        } catch (e: IllegalArgumentException) {
            throw SignatureException("$what: signature is not base64")
        }
        if (signature.size != 64) throw SignatureException("$what: signature has ${signature.size} bytes, expected 64")
        try {
            Ed25519Verify(key).verify(signature, data)
        } catch (e: GeneralSecurityException) {
            throw SignatureException("$what: signature invalid (key $keyId)")
        }
        return keyId
    }
}
