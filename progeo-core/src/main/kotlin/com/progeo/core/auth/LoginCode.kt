package com.progeo.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * What a login QR code carries. Both kinds are single use: the server marks a login token
 * used and blacklists a refresh token when it rotates it, so a photographed code is worthless
 * once the app has logged in with it.
 */
sealed interface LoginCode {
    /**
     * A one-time login token of the portal ("magic link", `helper/login_links.py`), exchanged
     * at `api/auth-support/login-link/exchange/`. Needs the two-factor code if the account has 2FA.
     */
    data class LoginToken(val token: String) : LoginCode {
        override fun toString() = "LoginToken(…)" // never log the secret
    }

    /** A JWT refresh token, exchanged at `api/token/refresh/` for a fresh pair. */
    data class RefreshToken(val token: String) : LoginCode {
        override fun toString() = "RefreshToken(…)"
    }

    companion object {
        /** `secrets.token_urlsafe(32)` gives 43 characters; allow some room either way. */
        private val TOKEN = Regex("[A-Za-z0-9_-]{32,128}")
        private val LINK_PATH = Regex("/login/link/([A-Za-z0-9_-]+)/?$")

        /**
         * Reads a scanned code; null if it is no ProGeo login code. Accepted:
         * - the login link of the portal, `https://<portal>/login/link/<token>`
         * - a URL or `progeo://login` link with `?token=<login token>` or `?refresh=<jwt>`
         * - JSON: `{"token": "<login token>"}` or `{"refresh": "<jwt>"}`
         * - the bare login token or refresh JWT
         *
         * The code never chooses the server: the token is only ever sent to the server the app
         * is configured for, so a code from elsewhere cannot redirect the login.
         */
        fun parse(text: String): LoginCode? {
            val s = text.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("{")) return fromJson(s)
            if (s.contains("://")) return fromUri(s)
            return fromBare(s)
        }

        private fun fromJson(s: String): LoginCode? {
            val o = runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull() ?: return null
            fun field(name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            field("refresh")?.let { return refresh(it) }
            return (field("token") ?: field("login_token"))?.let(::loginToken)
        }

        private fun fromUri(s: String): LoginCode? {
            val uri = runCatching { URI(s) }.getOrNull() ?: return null
            val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }.toMap()
            query["refresh"]?.let { return refresh(it) }
            query["token"]?.let { return loginToken(it) }
            val path = uri.rawPath ?: return null
            return LINK_PATH.find(path)?.groupValues?.get(1)?.let(::loginToken)
        }

        private fun fromBare(s: String): LoginCode? = when {
            s.count { it == '.' } == 2 -> refresh(s)
            else -> loginToken(s)
        }

        private fun loginToken(token: String) = token.takeIf { TOKEN.matches(it) }?.let(::LoginToken)

        /** Only refresh tokens: an access token would log in for an hour and could not be renewed. */
        private fun refresh(jwt: String): LoginCode? =
            jwt.takeIf { jwtType(it) == "refresh" }?.let(::RefreshToken)

        /** The `token_type` claim of a simplejwt token; null if [jwt] is not a JWT. */
        internal fun jwtType(jwt: String): String? = runCatching {
            val parts = jwt.split('.')
            if (parts.size != 3) return null
            val payload = parts[1]
            val json = Json.parseToJsonElement(
                String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '='))),
            ) as JsonObject
            (json["token_type"] as? JsonPrimitive)?.content
        }.getOrNull()
    }
}
