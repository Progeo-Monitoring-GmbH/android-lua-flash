package com.progeo.luaflasher.router

import com.progeo.core.auth.BackendException
import com.progeo.core.auth.BackendUnreachableException
import com.progeo.core.auth.NotLoggedInException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class RouterSyncState(val running: Boolean = false, val lastError: String? = null, val lastSuccess: Long? = null)

/**
 * Brings the phone and the backend together: sends the outbox in order and looks routers up.
 * Offline, everything keeps working from the phone's copy; the outbox waits.
 */
class RouterSync(
    private val store: RouterStore,
    private val backend: RouterBackend,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(RouterSyncState())
    val state: StateFlow<RouterSyncState> = _state.asStateFlow()

    /**
     * Sends queued work, oldest first. Stops at the first failure so the order holds (a pending
     * password is always on the server before its confirmation). Returns true when the queue is empty.
     */
    suspend fun flush(): Boolean = mutex.withLock {
        _state.value = _state.value.copy(running = true)
        try {
            for (op in store.outbox.value) {
                val record = store.get(op.serial)
                when (op) {
                    is OutboxOp.SaveRecord -> record?.let { backend.saveRecord(it) }
                    is OutboxOp.SaveCredentials -> record?.let { backend.saveCredentials(it) }
                    is OutboxOp.Backup -> {
                        val file = File(op.path)
                        if (file.isFile) backend.uploadBackup(op.serial, op.created, file.name, file.readBytes())
                    }
                    is OutboxOp.Report -> backend.sendReport(op.serial, op.report)
                }
                store.done(op)
            }
            _state.value = RouterSyncState(lastSuccess = System.currentTimeMillis())
            true
        } catch (e: BackendException) {
            _state.value = _state.value.copy(lastError = e.userMessage)
            false
        } finally {
            _state.value = _state.value.copy(running = false)
        }
    }

    /**
     * The router's record: the backend's copy, unless the phone has unsent changes for it (then the
     * phone's copy is newer). Offline or not logged in: the phone's copy.
     */
    suspend fun lookup(serial: String): RouterRecord? {
        flush()
        if (store.hasPending(serial)) return store.get(serial)
        return try {
            backend.get(serial)?.let { remote ->
                // Keep what only the phone knows (e.g. secrets the account may not read back).
                val local = store.get(serial)
                val merged = if (local == null) remote else remote.copy(
                    password = remote.password ?: local.password,
                    pendingPassword = remote.pendingPassword ?: local.pendingPassword,
                    factoryPassword = remote.factoryPassword ?: local.factoryPassword,
                    templateWifiKey = remote.templateWifiKey ?: local.templateWifiKey,
                    access = remote.access?.let { it.copy(wifiKey = it.wifiKey ?: local.access?.wifiKey) } ?: local.access,
                    pendingAccess = remote.pendingAccess ?: local.pendingAccess,
                    factoryAccess = remote.factoryAccess?.let { it.copy(wifiKey = it.wifiKey ?: local.factoryAccess?.wifiKey) } ?: local.factoryAccess,
                    certFingerprint = remote.certFingerprint ?: local.certFingerprint,
                )
                store.put(merged)
            } ?: store.get(serial)
        } catch (e: BackendUnreachableException) {
            store.get(serial)
        } catch (e: NotLoggedInException) {
            store.get(serial)
        }
    }

    /** Refreshes every router on the phone, e.g. before driving to a site without network. */
    suspend fun refreshAll(): Int {
        var n = 0
        for (r in store.routers.value) {
            if (lookup(r.serial) != null) n++
        }
        return n
    }
}
