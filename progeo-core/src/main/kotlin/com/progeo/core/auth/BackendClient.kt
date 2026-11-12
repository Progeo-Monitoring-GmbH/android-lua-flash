package com.progeo.core.auth

import com.progeo.core.UserFacingException
import com.progeo.core.json.flag
import com.progeo.core.json.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

open class BackendException(userMessage: String, detail: String? = null) : UserFacingException(userMessage, detail)

class NotLoggedInException : BackendException("Please log in to the ProGeo server.")

class BackendUnreachableException(detail: String?) :
    BackendException("The ProGeo server cannot be reached. Check the network and retry.", detail)

sealed interface AuthState {
    data object LoggedOut : AuthState
    data class LoggedIn(val claims: Claims?) : AuthState {
        val username: String? get() = claims?.username
    }
}

sealed interface LoginResult {
    data object Success : LoginResult
    /** Password was right; ask for the two-factor code and send everything again. */
    data object OtpRequired : LoginResult
    data object OtpInvalid : LoginResult
    data class Failed(val message: String) : LoginResult
}

/**
 * The ProGeo backend with JWT bearer tokens (API.md): login with optional OTP, a bearer
 * header on every call, one refresh at a time with rotation, logout. No cookies are kept.
 */
class BackendClient(
    private val baseUrl: () -> String,
    private val tokens: TokenStore,
    base: OkHttpClient,
) {
    private val refreshLock = Any()
    private val _state = MutableStateFlow(stateOf(tokens.load()))
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** For the token endpoints: no bearer header, no authenticator. */
    private val plain = base.newBuilder().build()

    private val api = base.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val current = tokens.load()
            if (current == null || request.header(AUTHORIZATION) != null) {
                chain.proceed(request)
            } else {
                chain.proceed(request.newBuilder().header(AUTHORIZATION, "Bearer ${current.access}").build())
            }
        }
        .authenticator { _, response -> refreshAndRetry(response) }
        .build()

    private fun stateOf(t: Tokens?): AuthState = if (t == null) AuthState.LoggedOut else AuthState.LoggedIn(Claims.of(t.access))

    fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = (baseUrl().toHttpUrl().resolve(path) ?: throw BackendException("Invalid server address.", path)).newBuilder()
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    // --- login / logout ---

    suspend fun login(username: String, password: String, otp: String?): LoginResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("username", username.trim())
            put("password", password)
            if (!otp.isNullOrBlank()) put("otp", otp.trim())
        }
        try {
            plain.newCall(post(url("api/token/"), body)).execute().use { r ->
                val json = parse(r)
                when {
                    r.code == 200 -> {
                        val t = tokensFrom(json) ?: return@use LoginResult.Failed("Unexpected answer from the server.")
                        tokens.save(t)
                        _state.value = stateOf(t)
                        LoginResult.Success
                    }
                    // Branch on "reason", never on the (translatable) "detail" text.
                    r.code == 401 && json.str("reason") == "otp_required" -> LoginResult.OtpRequired
                    r.code == 401 && json.str("reason") == "otp_invalid" -> LoginResult.OtpInvalid
                    r.code == 401 -> LoginResult.Failed("Login failed. Check user name and password, or try again in 15 minutes.")
                    r.code == 400 -> LoginResult.Failed("Enter user name and password.")
                    else -> LoginResult.Failed("Server error (HTTP ${r.code}).")
                }
            }
        } catch (e: IOException) {
            LoginResult.Failed("The ProGeo server cannot be reached. Check the network and retry.")
        }
    }

    /**
     * Logs in with a scanned [LoginCode]. A login token may ask for the two-factor code
     * ([LoginResult.OtpRequired]); it stays valid until it is used, so send it again with [otp].
     */
    suspend fun login(code: LoginCode, otp: String? = null): LoginResult = withContext(Dispatchers.IO) {
        val (path, body) = when (code) {
            is LoginCode.LoginToken -> "api/auth-support/login-link/exchange/" to buildJsonObject {
                put("token", code.token)
                if (!otp.isNullOrBlank()) put("otp", otp.trim())
            }
            is LoginCode.RefreshToken -> "api/token/refresh/" to buildJsonObject { put("refresh", code.token) }
        }
        try {
            plain.newCall(post(url(path), body)).execute().use { r ->
                val json = parse(r)
                when {
                    r.code == 200 -> {
                        val t = tokensFrom(json) ?: return@use LoginResult.Failed("Unexpected answer from the server.")
                        tokens.save(t)
                        _state.value = stateOf(t)
                        LoginResult.Success
                    }
                    json.str("reason") == "otp_required" -> LoginResult.OtpRequired
                    json.str("reason") == "otp_invalid" -> LoginResult.OtpInvalid
                    r.code == 429 -> LoginResult.Failed("Too many attempts. Wait a while and try again.")
                    r.code == 400 || r.code == 401 -> LoginResult.Failed(
                        "This login code is invalid, expired or was already used. Create a new one in the portal.",
                    )
                    else -> LoginResult.Failed("Server error (HTTP ${r.code}).")
                }
            }
        } catch (e: IOException) {
            LoginResult.Failed("The ProGeo server cannot be reached. Check the network and retry.")
        }
    }

    /** The portal modules the logged-in account may use (`api/auth-support/module-permissions/`). */
    suspend fun modulePermissions(): ModulePermissions = ModulePermissions.parse(get("api/auth-support/module-permissions/"))

    /** Blacklists the refresh token on the server (best effort) and forgets both tokens. */
    suspend fun logout() = withContext(Dispatchers.IO) {
        val current = tokens.load()
        tokens.clear()
        _state.value = AuthState.LoggedOut
        if (current != null) {
            runCatching {
                plain.newCall(post(url("api/token/logout/"), buildJsonObject { put("refresh", current.refresh) })).execute().close()
            }
        }
    }

    // --- authenticated calls ---

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url(path, query)).get().build())
    }

    suspend fun post(path: String, query: Map<String, String> = emptyMap(), body: JsonObject = JsonObject(emptyMap())): JsonElement =
        withContext(Dispatchers.IO) { execute(post(url(path, query), body)) }

    private fun execute(request: Request): JsonElement {
        if (tokens.load() == null) {
            _state.value = AuthState.LoggedOut
            throw NotLoggedInException()
        }
        try {
            api.newCall(request).execute().use { r ->
                val json = parse(r)
                when {
                    r.isSuccessful -> return json ?: throw BackendException("Unexpected answer from the server.", "HTTP ${r.code}, no JSON")
                    r.code == 401 -> {
                        // The authenticator could not refresh: the session is over.
                        tokens.clear()
                        _state.value = AuthState.LoggedOut
                        throw NotLoggedInException()
                    }
                    r.code == 403 -> throw forbidden(json)
                    r.code == 400 -> throw BackendException(json.str("reason") ?: "The server rejected the request.", json?.toString())
                    else -> throw BackendException("Server error (HTTP ${r.code}).", json?.toString())
                }
            }
        } catch (e: IOException) {
            throw BackendUnreachableException(e.message)
        }
    }

    private fun forbidden(json: JsonElement?): BackendException = when {
        json.flag("pending_review") -> BackendException("Your access request is still being reviewed.")
        json.flag("two_factor_setup_required") -> BackendException("Set up two-factor authentication in the web portal (profile page), then log in again.")
        json.flag("demo_read_only") -> BackendException("Demo account: changes are not possible.")
        json is JsonObject && json["missing_permissions"] != null -> {
            val missing = runCatching { json.getValue("missing_permissions").jsonArray.joinToString { it.jsonPrimitive.content } }
                .getOrElse { json["missing_permissions"].toString() }
            BackendException("Your account lacks the permission: $missing")
        }
        else -> BackendException("Access denied.", json?.toString())
    }

    // --- refresh (OkHttp authenticator, called on 401) ---

    private fun refreshAndRetry(response: Response): Request? {
        if (response.priorResponse != null) return null // already retried once: give up
        synchronized(refreshLock) {
            val current = tokens.load() ?: return null
            val sent = response.request.header(AUTHORIZATION)?.removePrefix("Bearer ")
            if (sent != current.access) {
                // Another request refreshed while this one waited.
                return response.request.newBuilder().header(AUTHORIZATION, "Bearer ${current.access}").build()
            }
            val refreshed = plain.newCall(post(url("api/token/refresh/"), buildJsonObject { put("refresh", current.refresh) }))
                .execute().use { r -> if (r.code == 200) tokensFrom(parse(r)) else null }
            if (refreshed == null) {
                tokens.clear()
                _state.value = AuthState.LoggedOut
                return null
            }
            // Store both first: the old refresh token is blacklisted now.
            tokens.save(refreshed)
            _state.value = stateOf(refreshed)
            return response.request.newBuilder().header(AUTHORIZATION, "Bearer ${refreshed.access}").build()
        }
    }

    // --- helpers ---

    private fun post(url: HttpUrl, body: JsonObject) =
        Request.Builder().url(url).post(body.toString().toRequestBody(JSON_TYPE)).build()

    private fun parse(r: Response): JsonElement? = runCatching { Json.parseToJsonElement(r.body!!.string()) }.getOrNull()

    private fun tokensFrom(json: JsonElement?): Tokens? {
        val access = json.str("access") ?: return null
        val refresh = json.str("refresh") ?: return null
        return Tokens(access, refresh)
    }

    companion object {
        private const val AUTHORIZATION = "Authorization"
        private val JSON_TYPE = "application/json".toMediaType()
    }
}
