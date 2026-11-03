package com.progeo.luaflasher.usb

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.progeo.luaflasher.core.io.SerialPort
import com.progeo.luaflasher.core.io.SerialPortClosedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [SerialPort] on usb-serial-for-android. A reader thread feeds a channel, so protocol code
 * waits with coroutine timeouts and never blocks the USB thread.
 */
class AndroidSerialPort(private val port: UsbSerialPort) : SerialPort, AutoCloseable {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val running = AtomicBoolean(true)

    @Volatile
    private var failure: IOException? = null

    private val reader = Thread({
        val buf = ByteArray(maxOf(port.readEndpoint?.maxPacketSize ?: 64, 64) * 4)
        try {
            while (running.get()) {
                val n = port.read(buf, READ_POLL_MS)
                if (n > 0) incoming.trySend(buf.copyOf(n))
            }
        } catch (e: IOException) {
            if (running.get()) failure = e
        } catch (e: RuntimeException) {
            if (running.get()) failure = IOException(e)
        } finally {
            incoming.close()
        }
    }, "usb-serial-reader").apply {
        isDaemon = true
        start()
    }

    val isOpen: Boolean get() = running.get() && failure == null

    private fun closedException(): SerialPortClosedException =
        SerialPortClosedException("USB connection lost: ${failure?.message ?: "closed"}", failure)

    override suspend fun write(data: ByteArray) {
        if (!isOpen) throw closedException()
        withContext(Dispatchers.IO) {
            try {
                port.write(data, WRITE_TIMEOUT_MS)
            } catch (e: IOException) {
                failure = e
                throw closedException()
            }
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray {
        val first = try {
            withTimeoutOrNull(timeoutMs) { incoming.receive() } ?: return ByteArray(0)
        } catch (e: ClosedReceiveChannelException) {
            throw closedException()
        }
        // Hand out everything that is already there in one piece.
        var next = incoming.tryReceive().getOrNull() ?: return first
        val out = ByteArrayOutputStream()
        out.write(first)
        while (true) {
            out.write(next)
            next = incoming.tryReceive().getOrNull() ?: break
        }
        return out.toByteArray()
    }

    override suspend fun setBaudRate(baud: Int) = withContext(Dispatchers.IO) {
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }

    override suspend fun setDtr(value: Boolean) = withContext(Dispatchers.IO) { port.dtr = value }

    override suspend fun setRts(value: Boolean) = withContext(Dispatchers.IO) { port.rts = value }

    override suspend fun purge() {
        withContext(Dispatchers.IO) { runCatching { port.purgeHwBuffers(false, true) } }
        while (incoming.tryReceive().isSuccess) Unit
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        runCatching { port.close() }
        reader.join(500)
    }

    companion object {
        private const val READ_POLL_MS = 100
        private const val WRITE_TIMEOUT_MS = 2_000
    }
}
