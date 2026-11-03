package com.progeo.luaflasher.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.progeo.luaflasher.core.FlasherException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class NoUsbDeviceException : FlasherException("No USB serial adapter found. Use an OTG adapter and a data cable.")

class UsbPermissionDeniedException : FlasherException("USB access was denied. Unplug, plug in again, allow access.")

class UsbOpenException(detail: String) : FlasherException(
    "The USB adapter could not be opened. Unplug it, plug it in again and retry.",
    detail,
)

/** A USB-UART adapter the app can use. */
data class SerialDevice(val driver: UsbSerialDriver) {
    val device: UsbDevice get() = driver.device
    val label: String
        get() {
            val chip = when (device.vendorId) {
                0x10C4 -> "CP210x"
                0x1A86 -> "CH340"
                0x0403 -> "FTDI"
                0x067B -> "PL2303"
                else -> driver.javaClass.simpleName.removeSuffix("SerialDriver")
            }
            return "$chip (%04X:%04X)".format(device.vendorId, device.productId)
        }
}

/** Finds adapters, asks for permission and opens ports (PLAN.md section 4, step 4). */
class UsbSerialManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _devices = MutableStateFlow<List<SerialDevice>>(emptyList())

    /** Currently attached adapters; refreshed on attach/detach. */
    val devices: StateFlow<List<SerialDevice>> = _devices.asStateFlow()

    init {
        refresh()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = refresh()
            },
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun refresh() {
        val known = setOf(0x10C4, 0x1A86, 0x0403, 0x067B)
        _devices.value = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .map { SerialDevice(it) }
            .sortedBy { if (it.device.vendorId in known) 0 else 1 }
    }

    fun firstDevice(): SerialDevice = devices.value.firstOrNull() ?: run {
        refresh()
        devices.value.firstOrNull() ?: throw NoUsbDeviceException()
    }

    fun hasPermission(device: SerialDevice) = usbManager.hasPermission(device.device)

    /** Asks the user for access; throws [UsbPermissionDeniedException] if refused. */
    suspend fun requestPermission(device: SerialDevice) {
        if (usbManager.hasPermission(device.device)) return
        val granted = suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != ACTION_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
            // The system fills in EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED, so the intent must be mutable.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(device.device, PendingIntent.getBroadcast(context, 0, intent, flags))
        }
        if (!granted && !usbManager.hasPermission(device.device)) throw UsbPermissionDeniedException()
    }

    /** Opens the first port of [device] at 115200 8N1. */
    fun open(device: SerialDevice, baud: Int = 115200): AndroidSerialPort {
        if (!usbManager.hasPermission(device.device)) throw UsbPermissionDeniedException()
        val connection = usbManager.openDevice(device.device) ?: throw UsbOpenException("openDevice returned null")
        val port = device.driver.ports.firstOrNull() ?: throw UsbOpenException("adapter has no serial port")
        try {
            port.open(connection)
            port.setParameters(baud, 8, com.hoho.android.usbserial.driver.UsbSerialPort.STOPBITS_1, com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)
            // No auto-reset on our boards; keep both lines idle so other boards are not held in reset.
            runCatching { port.dtr = false }
            runCatching { port.rts = false }
        } catch (e: Exception) {
            runCatching { port.close() }
            throw UsbOpenException(e.message ?: e.javaClass.simpleName)
        }
        return AndroidSerialPort(port)
    }

    companion object {
        private const val ACTION_PERMISSION = "com.progeo.luaflasher.USB_PERMISSION"
    }
}
