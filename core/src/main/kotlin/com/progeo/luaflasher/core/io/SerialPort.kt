package com.progeo.luaflasher.core.io

/**
 * The serial line to the board. Implemented with usb-serial-for-android in the app and by
 * simulators in the tests, so the protocol code never sees Android types.
 *
 * All calls are cancellable; timeouts are enforced by the callers with coroutine timeouts.
 */
interface SerialPort {
    /** Writes all bytes. */
    suspend fun write(data: ByteArray)

    /**
     * Returns the bytes that arrived, as soon as there are any, or an empty array after
     * [timeoutMs] without data.
     */
    suspend fun read(timeoutMs: Long): ByteArray

    suspend fun setBaudRate(baud: Int)

    suspend fun setDtr(value: Boolean)

    suspend fun setRts(value: Boolean)

    /** Drops everything received but not yet read. */
    suspend fun purge()
}

/** The port went away (cable pulled, permission revoked, closed). */
class SerialPortClosedException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
