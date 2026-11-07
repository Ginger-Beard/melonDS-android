package me.magnum.melonds.ir

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.R
import me.magnum.melonds.domain.model.IrLinkMode
import java.io.IOException
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the USB IR dongle (CDC-ACM, protocol v1 in framed mode) and connects it to the emulated IR
 * transceiver.
 *
 * The port is only open while an emulation session is running AND the IR link setting is
 * [IrLinkMode.USB_DONGLE]. The native link is attached (`MelonEmulator.irAttach()`) only once the
 * dongle has answered IDENTIFY with the expected magic and protocol version.
 *
 * Threads: state transitions run on a single control thread; USB reads come from
 * [SerialInputOutputManager]'s reader thread; IR_TX frames are written from a dedicated writer
 * thread that drains [IrBridge.txQueue]. Native attach/detach and every [MelonEmulator.irPushPacket]
 * call happen under [nativeLock] so that no packet is pushed after a detach.
 */
@Singleton
class IrDongleManager @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private const val TAG = "IrDongle"

        /** Provisional VID:PID of the dongle. Keep in sync with res/xml/ir_dongle_device_filter.xml. */
        const val USB_VENDOR_ID = 0x303A
        const val USB_PRODUCT_ID = 0x4001

        // Nominal for CDC-ACM; the dongle ignores the line coding.
        private const val BAUD_RATE = 115200
        private const val WRITE_TIMEOUT_MS = 50
        private const val IDENTIFY_TIMEOUT_MS = 500L
        private const val IDENTIFY_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 1000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }

    private val usbManager: UsbManager? = context.getSystemService()
    private val permissionAction = "${context.packageName}.IR_DONGLE_USB_PERMISSION"
    private val mainHandler = Handler(Looper.getMainLooper())

    private val scope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { Thread(it, "IrDongle-control") }.asCoroutineDispatcher()
    )
    private val writerDispatcher = Executors.newSingleThreadExecutor {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            it.run()
        }, "IrDongle-tx")
    }.asCoroutineDispatcher()

    // Control-thread state
    private var mode = IrLinkMode.OFF
    private var connection: Connection? = null
    private var receiverRegistered = false
    private var reconnectJob: Job? = null
    private var consecutiveFailures = 0
    /** Device for which we already asked (or let the system ask) for permission; avoids repeated dialogs. */
    private var permissionAskedFor: String? = null
    /** Devices that failed identification or kept failing. Cleared when they are unplugged. */
    private val rejectedDevices = mutableSetOf<String>()

    private val nativeLock = Any()
    // Guarded by nativeLock
    private var sessionActive = false
    private var nativeAttached = false

    private inner class Connection(val device: UsbDevice, val port: UsbSerialPort) {
        val identifyResponse = CompletableDeferred<DongleMessage.IdentifyResponse>()
        val decoder = CobsStreamDecoder(DongleProtocol.MAX_FRAME, { onFrame(this, it) }) { reason ->
            Log.w(TAG, "Dropped malformed frame from dongle: $reason")
        }
        var ioManager: SerialInputOutputManager? = null
        var writerJob: Job? = null
        @Volatile var identified = false
        @Volatile var closed = false
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scope.launch { onDeviceAttached(device, fromUserIntent = false) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> scope.launch { onDeviceDetached(device) }
                permissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    scope.launch { onPermissionResult(device, granted) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- public API (any thread)

    /** Call after the emulator has started running a ROM. */
    fun onEmulationStarted(irLinkMode: IrLinkMode) {
        synchronized(nativeLock) {
            sessionActive = true
        }
        scope.launch {
            mode = irLinkMode
            // A new session may ask for USB permission once more
            permissionAskedFor = null
            consecutiveFailures = 0
            reconcile()
        }
    }

    /** Call when the IR link setting may have changed during a session. */
    fun onIrLinkModeChanged(irLinkMode: IrLinkMode) {
        scope.launch {
            if (irLinkMode == mode) return@launch
            mode = irLinkMode
            permissionAskedFor = null
            consecutiveFailures = 0
            reconcile()
        }
    }

    /**
     * Call BEFORE stopping the emulator. Detaches the native link synchronously (cheap, never touches
     * USB); the port itself is closed on the control thread.
     */
    fun onEmulationStopped() {
        synchronized(nativeLock) {
            sessionActive = false
            detachNativeLocked()
        }
        scope.launch { reconcile() }
    }

    /**
     * Called when the user accepted the system's "open with" prompt for the dongle (or chose to always
     * open the app for it). The system has granted USB permission by then.
     */
    fun onDeviceAttachIntent(device: UsbDevice?) {
        scope.launch { onDeviceAttached(device, fromUserIntent = true) }
    }

    fun isEmulationSessionActive(): Boolean = isSessionActive()

    // ---------------------------------------------------------------- control thread

    private suspend fun reconcile() {
        val usbManager = usbManager
        if (usbManager == null || !isSessionActive() || mode != IrLinkMode.USB_DONGLE) {
            reconnectJob?.cancel()
            reconnectJob = null
            closeConnection()
            setReceiverRegistered(false)
            return
        }

        setReceiverRegistered(true)

        val current = connection
        if (current != null) {
            if (current.identified) attachNativeIfWanted()
            return
        }
        if (reconnectJob?.isActive == true) return

        val device = findDongle(usbManager) ?: return
        if (device.deviceName in rejectedDevices) return

        if (!usbManager.hasPermission(device)) {
            if (permissionAskedFor != device.deviceName) {
                permissionAskedFor = device.deviceName
                requestPermission(usbManager, device)
            }
            return
        }

        openConnection(usbManager, device)
    }

    private fun findDongle(usbManager: UsbManager): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull {
            it.vendorId == USB_VENDOR_ID && it.productId == USB_PRODUCT_ID
        }
    }

    private fun requestPermission(usbManager: UsbManager, device: UsbDevice) {
        // The system fills in EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED, so the intent must be mutable
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(permissionAction).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        Log.i(TAG, "Requesting USB permission for ${device.deviceName}")
        usbManager.requestPermission(device, pendingIntent)
    }

    private suspend fun openConnection(usbManager: UsbManager, device: UsbDevice) {
        val usbConnection: UsbDeviceConnection = usbManager.openDevice(device) ?: run {
            Log.w(TAG, "Could not open ${device.deviceName}")
            onOpenFailure(device)
            return
        }
        val port = CdcAcmSerialDriver(device).ports.firstOrNull()
        if (port == null) {
            Log.w(TAG, "${device.deviceName} has no CDC-ACM port")
            usbConnection.close()
            rejectDevice(device)
            return
        }

        try {
            port.open(usbConnection)
            try {
                port.setParameters(BAUD_RATE, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (_: UnsupportedOperationException) {
                // Line coding is irrelevant for the dongle
            }
            port.setDTR(true)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to open serial port on ${device.deviceName}", e)
            closePortQuietly(port)
            usbConnection.close()
            onOpenFailure(device)
            return
        }

        val newConnection = Connection(device, port)
        connection = newConnection
        newConnection.ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                newConnection.decoder.feed(data)
            }

            override fun onRunError(e: Exception) {
                if (!newConnection.closed) {
                    Log.w(TAG, "Dongle I/O error", e)
                    scope.launch { onConnectionError(newConnection) }
                }
            }
        }).apply {
            setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            start()
        }

        val response = identify(newConnection)
        if (connection !== newConnection) {
            // Closed (unplugged, session ended) while identifying
            return
        }
        when {
            response == null -> {
                Log.w(TAG, "No IDENTIFY response from ${device.deviceName}; not an IR dongle in framed mode")
                closeConnection()
                rejectDevice(device)
                showToast(context.getString(R.string.ir_dongle_unsupported))
            }
            !response.isSupported -> {
                Log.w(TAG, "Unsupported device: magic=${String(response.magic, Charsets.US_ASCII)} proto=${response.protocolVersion} caps=0x${response.capabilities.toString(16)}")
                closeConnection()
                rejectDevice(device)
                showToast(context.getString(R.string.ir_dongle_unsupported))
            }
            else -> {
                Log.i(TAG, "IR dongle identified: firmware ${response.firmwareVersion}, caps=0x${response.capabilities.toString(16)}")
                newConnection.identified = true
                consecutiveFailures = 0
                IrBridge.clearPending()
                newConnection.writerJob = scope.launch(writerDispatcher) { runWriter(newConnection) }
                attachNativeIfWanted()
                showToast(context.getString(R.string.ir_dongle_connected, response.firmwareVersion))
            }
        }
    }

    private suspend fun identify(connection: Connection): DongleMessage.IdentifyResponse? {
        repeat(IDENTIFY_ATTEMPTS) {
            if (connection.closed) return null
            try {
                // A lone 0x00 first resynchronises the dongle's frame decoder
                connection.port.write(byteArrayOf(0) + DongleProtocol.identifyFrame(), WRITE_TIMEOUT_MS)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to send IDENTIFY", e)
            }
            val response = withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) { connection.identifyResponse.await() }
            if (response != null) return response
        }
        return null
    }

    private fun onOpenFailure(device: UsbDevice) {
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            rejectDevice(device)
        } else {
            scheduleReconnect()
        }
    }

    private fun onConnectionError(failed: Connection) {
        if (connection !== failed) return
        val wasIdentified = failed.identified
        closeConnection()
        if (wasIdentified) {
            showToast(context.getString(R.string.ir_dongle_disconnected))
        }
        onOpenFailure(failed.device)
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            reconnectJob = null
            reconcile()
        }
    }

    private fun rejectDevice(device: UsbDevice) {
        rejectedDevices += device.deviceName
    }

    private suspend fun onDeviceAttached(device: UsbDevice?, fromUserIntent: Boolean) {
        if (device == null || device.vendorId != USB_VENDOR_ID || device.productId != USB_PRODUCT_ID) return
        Log.i(TAG, "Dongle attached: ${device.deviceName}")
        rejectedDevices -= device.deviceName
        consecutiveFailures = 0
        if (!fromUserIntent) {
            // The system is about to show its own "open with" prompt for this device (our device
            // filter matches). Don't stack a second permission dialog on top of it; connect once the
            // prompt is accepted (onDeviceAttachIntent) or if permission is already granted.
            permissionAskedFor = device.deviceName
        }
        reconcile()
    }

    private suspend fun onDeviceDetached(device: UsbDevice?) {
        if (device == null) return
        rejectedDevices -= device.deviceName
        if (permissionAskedFor == device.deviceName) {
            permissionAskedFor = null
        }
        val current = connection
        if (current != null && current.device.deviceName == device.deviceName) {
            Log.i(TAG, "Dongle detached: ${device.deviceName}")
            val wasIdentified = current.identified
            closeConnection()
            consecutiveFailures = 0
            if (wasIdentified) {
                showToast(context.getString(R.string.ir_dongle_disconnected))
            }
        }
        reconcile()
    }

    private suspend fun onPermissionResult(device: UsbDevice?, granted: Boolean) {
        if (!granted) {
            Log.i(TAG, "USB permission denied for ${device?.deviceName}")
            showToast(context.getString(R.string.ir_dongle_permission_denied))
            return
        }
        reconcile()
    }

    private fun closeConnection() {
        val current = connection ?: return
        connection = null
        current.closed = true
        synchronized(nativeLock) {
            detachNativeLocked()
        }
        current.writerJob?.cancel()
        current.ioManager?.stop()
        closePortQuietly(current.port)
        current.identifyResponse.cancel()
    }

    private fun closePortQuietly(port: UsbSerialPort) {
        try {
            port.close()
        } catch (_: IOException) {
            // Already closed or gone
        }
    }

    private fun setReceiverRegistered(register: Boolean) {
        if (register == receiverRegistered) return
        if (register) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(permissionAction)
            }
            ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            context.unregisterReceiver(usbReceiver)
        }
        receiverRegistered = register
    }

    private fun isSessionActive(): Boolean = synchronized(nativeLock) { sessionActive }

    private fun attachNativeIfWanted() {
        synchronized(nativeLock) {
            val current = connection
            if (!nativeAttached && sessionActive && mode == IrLinkMode.USB_DONGLE && current != null && current.identified && !current.closed) {
                MelonEmulator.irAttach()
                nativeAttached = true
                Log.i(TAG, "IR link attached")
            }
        }
    }

    /** Must hold [nativeLock]. */
    private fun detachNativeLocked() {
        if (nativeAttached) {
            MelonEmulator.irDetach()
            nativeAttached = false
            Log.i(TAG, "IR link detached")
        }
    }

    // ---------------------------------------------------------------- reader thread

    private fun onFrame(source: Connection, frame: ByteArray) {
        when (val message = DongleProtocol.parse(frame)) {
            is DongleMessage.IrRx -> {
                if (message.packet.isEmpty()) return
                synchronized(nativeLock) {
                    if (nativeAttached && !source.closed) {
                        MelonEmulator.irPushPacket(message.packet)
                    }
                }
            }
            is DongleMessage.IdentifyResponse -> source.identifyResponse.complete(message)
            is DongleMessage.Log -> Log.i(TAG, "dongle: ${message.text}")
            is DongleMessage.Error -> Log.w(TAG, "dongle error 0x%02x (%s): %s".format(message.code, DongleProtocol.errorName(message.code), message.text))
            is DongleMessage.ConfigAck -> Log.d(TAG, "CONFIG_ACK key=0x%02x status=%d".format(message.key, message.status))
            is DongleMessage.TxDone, is DongleMessage.Pong -> Unit
            is DongleMessage.Unknown -> Log.d(TAG, "Ignoring frame type 0x%02x".format(message.type))
            null -> Log.w(TAG, "Dropped truncated frame from dongle")
        }
    }

    // ---------------------------------------------------------------- writer thread

    private suspend fun runWriter(target: Connection) {
        var resync = false
        for (packet in IrBridge.txQueue) {
            if (target.closed) break
            if (packet.isEmpty() || packet.size > DongleProtocol.MAX_IR_PACKET) {
                Log.w(TAG, "Dropping IR packet of invalid size ${packet.size}")
                continue
            }
            val frame = DongleProtocol.irTxFrame(packet)
            try {
                // After a timed-out (possibly partial) write, a leading 0x00 terminates the
                // fragment so the dongle doesn't merge it with this frame.
                target.port.write(if (resync) byteArrayOf(0) + frame else frame, WRITE_TIMEOUT_MS)
                resync = false
            } catch (e: SerialTimeoutException) {
                Log.w(TAG, "IR_TX write timed out; packet dropped")
                resync = true
            } catch (e: IOException) {
                if (!target.closed) {
                    Log.w(TAG, "IR_TX write failed", e)
                    scope.launch { onConnectionError(target) }
                }
                break
            }
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
