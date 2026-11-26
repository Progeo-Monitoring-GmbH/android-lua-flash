package com.progeo.teltonika.api

import com.progeo.teltonika.label.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** A failure the user can act on: [userMessage] is shown as is, [message] carries the detail. */
open class RouterException(val userMessage: String, detail: String? = null, cause: Throwable? = null) :
    Exception(detail ?: userMessage, cause)

class RouterUnreachableException(detail: String?, cause: Throwable? = null) : RouterException(
    "The router does not answer. Is the phone connected to its network?",
    detail,
    cause,
)

/** User name or password were rejected. */
class RouterLoginException(detail: String?) : RouterException("The router rejected the login.", detail)

/** Too many failed logins: RutOS blocks further attempts for a while. */
class RouterLockedOutException(detail: String?) : RouterException(
    "The router blocks logins after too many failed attempts. Wait a few minutes and retry.",
    detail,
)

/** The router answered with `success: false` or an HTTP error. [codes] are RutOS error codes. */
class RouterApiException(
    val httpCode: Int,
    val codes: List<Int>,
    val errors: List<String>,
    path: String,
) : RouterException(
    "The router refused the request: ${errors.firstOrNull() ?: "HTTP $httpCode"}",
    "$path: HTTP $httpCode, codes $codes, $errors",
) {
    /** The firmware does not offer this endpoint (RutOS error 122, or 404). */
    val notImplemented: Boolean get() = httpCode == 404 || ENDPOINT_NOT_IMPLEMENTED in codes

    companion object {
        const val ENDPOINT_NOT_IMPLEMENTED = 122
    }
}

/**
 * The RutOS web API (`/api/...`, RutOS 07.06 and later): `POST /api/login` gives a bearer token
 * that expires after about 5 minutes. The client keeps the credentials of the last successful
 * login and logs in again by itself: before a call when the token is about to expire, and once
 * after a 401. Responses are `{"success": true, "data": …}` or `{"success": false, "errors": […]}`;
 * the calls return `data`.
 *
 * [http] decides how the router is reached (the app binds it to the router's network and pins its
 * certificate, see [com.progeo.teltonika.tls.PinningTrustManager]).
 */
class RutosClient(
    baseUrl: String,
    http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val baseUrl: HttpUrl = baseUrl.toHttpUrl()
    private val http = http.newBuilder().followRedirects(false).build()

    /** Uploads and upgrades take longer than ordinary calls. */
    private val slow = this.http.newBuilder().writeTimeout(10, TimeUnit.MINUTES).readTimeout(5, TimeUnit.MINUTES).build()

    private class Session(val token: String, val expiresAt: Long)

    private val loginLock = Mutex()

    @Volatile
    private var credentials: Credentials? = null

    @Volatile
    private var session: Session? = null

    /** The user of the current session. */
    val username: String? get() = credentials?.username

    /** The password of the current session (the router asks for it when it is changed). */
    val password: String? get() = credentials?.password

    /** Logs in and keeps [credentials] for later re-logins. Throws [RouterLoginException] if rejected. */
    suspend fun login(credentials: Credentials) = loginLock.withLock {
        session = createSession(credentials)
        this.credentials = credentials
    }

    /** Replaces the remembered password (after it was changed on the router) without a new login. */
    fun rememberPassword(password: String) {
        credentials = credentials?.copy(password = password)
    }

    /** Ends the session on the router (best effort) and forgets the credentials. */
    suspend fun logout() {
        val current = session
        session = null
        credentials = null
        if (current != null) {
            runCatching {
                withContext(Dispatchers.IO) {
                    http.newCall(request("/api/logout", current.token).post(EMPTY_JSON).build()).execute().close()
                }
            }
        }
    }

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement =
        call(path) { request(path, it, query).get() }

    suspend fun post(path: String, data: JsonElement? = null): JsonElement =
        call(path) { request(path, it).post(envelope(data)) }

    suspend fun put(path: String, data: JsonElement): JsonElement =
        call(path) { request(path, it).put(envelope(data)) }

    suspend fun delete(path: String): JsonElement = call(path) { request(path, it).delete() }

    /** Uploads [file] as multipart field [field], reporting bytes sent through [onProgress]. */
    suspend fun upload(
        path: String,
        file: File,
        field: String = "file",
        contentType: MediaType = OCTET_STREAM,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): JsonElement = call(path, slow, freshFor = UPLOAD_TOKEN_MARGIN_MS) { token ->
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(field, file.name, ProgressBody(file, contentType, onProgress))
            .build()
        request(path, token).post(body)
    }

    /** A call that answers with a file (e.g. a config backup) instead of JSON. */
    suspend fun download(path: String, data: JsonElement? = null, post: Boolean = data != null): ByteArray =
        withContext(Dispatchers.IO) {
            var retried = false
            while (true) {
                val token = token(DEFAULT_TOKEN_MARGIN_MS)
                val builder = request(path, token).let { if (post) it.post(envelope(data)) else it.get() }
                val result = execute(slow, builder.build(), path) { r ->
                    when {
                        r.code == 401 && !retried -> null
                        r.isSuccessful && r.header("Content-Type")?.contains("json") != true -> r.body!!.bytes()
                        else -> {
                            parse(r, path)
                            throw RouterApiException(r.code, emptyList(), listOf("no file in the answer"), path)
                        }
                    }
                }
                if (result != null) return@withContext result
                retried = true
                session = null
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    // --- internals ---

    private suspend fun call(
        path: String,
        client: OkHttpClient = http,
        freshFor: Long = DEFAULT_TOKEN_MARGIN_MS,
        build: (token: String) -> Request.Builder,
    ): JsonElement = withContext(Dispatchers.IO) {
        var retried = false
        while (true) {
            val token = token(freshFor)
            val result = execute(client, build(token).build(), path) { r ->
                if (r.code == 401 && !retried) null else parse(r, path)
            }
            if (result != null) return@withContext result
            // The token expired early (reboot, session limit): log in again once.
            retried = true
            session = null
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun token(freshFor: Long): String {
        session?.takeIf { it.expiresAt - clock() > freshFor }?.let { return it.token }
        return loginLock.withLock {
            session?.takeIf { it.expiresAt - clock() > freshFor }?.let { return@withLock it.token }
            val creds = credentials ?: throw RouterLoginException("not logged in")
            createSession(creds).also { session = it }.token
        }
    }

    private suspend fun createSession(credentials: Credentials): Session = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("username", credentials.username)
            put("password", credentials.password)
        }
        val req = request(LOGIN, token = null).post(body.toString().toRequestBody(JSON)).build()
        execute(http, req, LOGIN) { r ->
            val json = runCatching { Json.parseToJsonElement(r.body!!.string()) }.getOrNull()
            val errors = errorTexts(json)
            when {
                r.code == 429 || errors.any { it.contains("block", true) || it.contains("too many", true) } ->
                    throw RouterLockedOutException(errors.joinToString())
                r.code == 401 || r.code == 403 || (json as? JsonObject)?.get("success")?.let { (it as? JsonPrimitive)?.content } == "false" ->
                    throw RouterLoginException("HTTP ${r.code}: ${errors.joinToString()}")
                !r.isSuccessful -> throw RouterApiException(r.code, errorCodes(json), errors, LOGIN)
            }
            val data = (json as? JsonObject)?.get("data") as? JsonObject
                ?: throw RouterApiException(r.code, emptyList(), listOf("unexpected login answer"), LOGIN)
            val token = data.str("token") ?: throw RouterApiException(r.code, emptyList(), listOf("no token in the login answer"), LOGIN)
            val expires = data.str("expires")?.toLongOrNull() ?: DEFAULT_SESSION_SECONDS
            Session(token, clock() + expires * 1000)
        }
    }

    private fun request(path: String, token: String?, query: Map<String, String> = emptyMap()): Request.Builder {
        val url = baseUrl.newBuilder().encodedPath(if (path.startsWith("/")) path else "/$path").apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return Request.Builder().url(url).apply { if (token != null) header("Authorization", "Bearer $token") }
    }

    private fun envelope(data: JsonElement?): RequestBody =
        (if (data == null) EMPTY_JSON_TEXT else buildJsonObject { put("data", data) }.toString()).toRequestBody(JSON)

    private fun <T> execute(client: OkHttpClient, request: Request, path: String, handle: (okhttp3.Response) -> T): T = try {
        client.newCall(request).execute().use(handle)
    } catch (e: SSLException) {
        // A changed certificate surfaces as an SSLException that wraps the pinning failure.
        throw (generateSequence(e as Throwable) { it.cause }.filterIsInstance<RouterException>().firstOrNull()
            ?: RouterUnreachableException("$path: TLS: ${e.message}", e))
    } catch (e: IOException) {
        throw RouterUnreachableException("$path: ${e.message}", e)
    }

    private fun parse(r: okhttp3.Response, path: String): JsonElement {
        val text = r.body?.string().orEmpty()
        val json = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        val ok = r.isSuccessful && (json as? JsonObject)?.get("success")?.let { (it as? JsonPrimitive)?.content } != "false"
        if (!ok) throw RouterApiException(r.code, errorCodes(json), errorTexts(json), path)
        if (json == null) throw RouterApiException(r.code, emptyList(), listOf("answer is not JSON"), path)
        return (json as? JsonObject)?.get("data") ?: JsonNull
    }

    private fun errorList(json: JsonElement?): List<JsonElement> = ((json as? JsonObject)?.get("errors") as? JsonArray).orEmpty()

    private fun errorCodes(json: JsonElement?) = errorList(json).mapNotNull { (it as? JsonObject)?.str("code")?.toIntOrNull() }

    private fun errorTexts(json: JsonElement?) = errorList(json).mapNotNull { e ->
        (e as? JsonObject)?.let { o -> listOfNotNull(o.str("error"), o.str("source")?.let { "($it)" }).joinToString(" ") }
            ?: (e as? JsonPrimitive)?.content
    }

    private class ProgressBody(
        private val file: File,
        private val type: MediaType,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            val total = file.length()
            var sent = 0L
            file.source().use { source ->
                val buffer = okio.Buffer()
                while (true) {
                    val n = source.read(buffer, 64 * 1024L)
                    if (n < 0) break
                    sink.write(buffer, n)
                    sent += n
                    onProgress(sent, total)
                }
            }
        }
    }

    companion object {
        const val LOGIN = "/api/login"

        /** RutOS sessions last 5 minutes when the login answer does not say. */
        const val DEFAULT_SESSION_SECONDS = 299L
        private const val DEFAULT_TOKEN_MARGIN_MS = 20_000L

        /** An upload must not run into the token's end: start it with a fresh one. */
        private const val UPLOAD_TOKEN_MARGIN_MS = 240_000L
        private val JSON = "application/json".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private const val EMPTY_JSON_TEXT = "{}"
        private val EMPTY_JSON = EMPTY_JSON_TEXT.toRequestBody(JSON)
    }
}
