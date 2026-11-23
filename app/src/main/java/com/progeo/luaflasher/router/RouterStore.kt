package com.progeo.luaflasher.router

import com.progeo.core.security.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import java.io.File

/** How the phone reaches a router: its Wi-Fi and its LAN address. */
@Serializable
data class RouterAccess(val ssid: String? = null, val wifiKey: String? = null, val lanIp: String? = null) {
    override fun toString() = "RouterAccess($ssid, ${if (wifiKey == null) "-" else "***"}, $lanIp)"
}

/**
 * Everything we know about one router, keyed by its serial number. Kept on the phone (encrypted)
 * and on the backend (`api/routers/<serial>/`). Secrets: the passwords and Wi-Fi keys.
 */
@Serializable
data class RouterRecord(
    val serial: String,
    val model: String? = null,
    val productCode: String? = null,
    val imei: String? = null,
    val mac: String? = null,
    val batch: String? = null,
    val firmware: String? = null,
    /** SHA-256 of the router's certificate, pinned on first contact. */
    val certFingerprint: String? = null,
    val username: String = "admin",
    /** The confirmed password. Null: the router still has its factory password. */
    val password: String? = null,
    /** Written before a password change and kept until the new password is confirmed. */
    val pendingPassword: String? = null,
    val factoryPassword: String? = null,
    val access: RouterAccess? = null,
    /** Access a template is about to set; promoted to [access] after the first connection with it. */
    val pendingAccess: RouterAccess? = null,
    val factoryAccess: RouterAccess? = null,
    /** Value of the `{wifi_key}` template placeholder, generated once per router. */
    val templateWifiKey: String? = null,
    val templateId: String? = null,
    val templateVersion: Int? = null,
    /** Router-specific template values: endpoint → key → value. */
    val overrides: Map<String, Map<String, JsonElement>> = emptyMap(),
    val lastSeen: String? = null,
    val note: String? = null,
) {
    val onFactoryPassword: Boolean get() = password == null || password == factoryPassword
}

/** Work for the backend that waits until it can be reached, in order. */
@Serializable
sealed interface OutboxOp {
    val serial: String

    /** Send the router's record (without secrets) as it is when the queue is flushed. */
    @Serializable
    @SerialName("record")
    data class SaveRecord(override val serial: String) : OutboxOp

    /** Send the router's secrets as they are when the queue is flushed. */
    @Serializable
    @SerialName("credentials")
    data class SaveCredentials(override val serial: String) : OutboxOp

    @Serializable
    @SerialName("backup")
    data class Backup(override val serial: String, val created: String, val path: String) : OutboxOp

    @Serializable
    @SerialName("report")
    data class Report(override val serial: String, val report: JsonObject) : OutboxOp
}

/**
 * The routers on the phone and the outbox, encrypted as a whole with a Keystore key. Every change
 * is written to disk (atomically) before it returns: a pending password must survive a crash.
 */
class RouterStore(private val file: File, private val cipher: SecretCipher) {

    @Serializable
    private data class Stored(val format: Int = 1, val routers: List<RouterRecord> = emptyList(), val outbox: List<OutboxOp> = emptyList())

    private val mutex = Mutex()
    private val _routers = MutableStateFlow<List<RouterRecord>>(emptyList())
    val routers: StateFlow<List<RouterRecord>> = _routers.asStateFlow()
    private val _outbox = MutableStateFlow<List<OutboxOp>>(emptyList())
    val outbox: StateFlow<List<OutboxOp>> = _outbox.asStateFlow()

    fun get(serial: String): RouterRecord? = _routers.value.firstOrNull { it.serial == serial }

    fun hasPending(serial: String) = _outbox.value.any { it.serial == serial }

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.isFile) return@withLock
            val stored = runCatching { json.decodeFromString<Stored>(cipher.decrypt(file.readText())) }.getOrNull() ?: return@withLock
            _routers.value = stored.routers.sortedBy { it.serial }
            _outbox.value = stored.outbox
        }
    }

    /** Changes (or creates) a record and queues [ops] in one write. Returns the new record. */
    suspend fun update(serial: String, vararg ops: OutboxOp, change: (RouterRecord?) -> RouterRecord): RouterRecord =
        write { routers, outbox ->
            val updated = change(routers.firstOrNull { it.serial == serial })
            require(updated.serial == serial)
            val list = routers.filter { it.serial != serial } + updated
            Triple(list, outbox + ops.filter { it !in outbox }, updated)
        }

    /** Replaces a record with the backend's copy (no outbox entries). */
    suspend fun put(record: RouterRecord) = update(record.serial) { record }

    suspend fun enqueue(op: OutboxOp) = write { routers, outbox -> Triple(routers, if (op in outbox) outbox else outbox + op, Unit) }

    suspend fun done(op: OutboxOp) = write { routers, outbox -> Triple(routers, outbox - op, Unit) }

    suspend fun delete(serial: String) =
        write { routers, outbox -> Triple(routers.filter { it.serial != serial }, outbox.filter { it.serial != serial }, Unit) }

    private suspend fun <T> write(change: (List<RouterRecord>, List<OutboxOp>) -> Triple<List<RouterRecord>, List<OutboxOp>, T>): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val (routers, outbox, result) = change(_routers.value, _outbox.value)
                val sorted = routers.sortedBy { it.serial }
                val text = cipher.encrypt(json.encodeToString(Stored.serializer(), Stored(routers = sorted, outbox = outbox)))
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.outputStream().use { out ->
                    out.write(text.toByteArray())
                    out.fd.sync()
                }
                if (!tmp.renameTo(file)) {
                    file.delete()
                    check(tmp.renameTo(file)) { "cannot write ${file.name}" }
                }
                _routers.value = sorted
                _outbox.value = outbox
                result
            }
        }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            namingStrategy = JsonNamingStrategy.SnakeCase
            classDiscriminator = "op"
        }
    }
}
