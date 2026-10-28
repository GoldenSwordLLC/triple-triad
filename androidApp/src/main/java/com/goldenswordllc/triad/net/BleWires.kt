package com.goldenswordllc.triad.net

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.*
import java.util.UUID

object BleIds {
    val SERVICE: UUID = UUID.fromString("7d2b0000-5c1e-4b1a-9e11-2f5a3c8d9e01")
    val WRITE:  UUID = UUID.fromString("7d2b0002-5c1e-4b1a-9e11-2f5a3c8d9e01")
    val NOTIFY: UUID = UUID.fromString("7d2b0003-5c1e-4b1a-9e11-2f5a3c8d9e01")
    val CCC:    UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

private fun ByteArray.chunksOf(n: Int): List<ByteArray> =
    (0 until size step n).map { copyOfRange(it, minOf(it + n, size)) }

abstract class BaseWire : triad.net.Wire {
    protected val pendingLines = ArrayDeque<String>()
    @Volatile protected var closedFired = false
    override var onLine: ((String) -> Unit)? = null
        set(value) { field = value; if (value != null) while (pendingLines.isNotEmpty()) value(pendingLines.removeFirst()) }
    override var onClose: (() -> Unit)? = null
        set(value) { field = value; if (closedFired) value?.invoke() }
    protected fun deliver(line: String) { onLine?.invoke(line) ?: pendingLines.addLast(line) }
    protected fun fireClosed() { if (!closedFired) { closedFired = true; onClose?.invoke() } }
}

class BleServerWire(private val context: Context, private val scope: CoroutineScope) : BaseWire() {
    var onPeerConnected: (() -> Unit)? = null

    private val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advCb: AdvertiseCallback? = null

    private var peer: BluetoothDevice? = null
    @Volatile private var subscribed = false
    @Volatile private var notifySize = 20
    private val lock = Any()
    private val outbox = ArrayDeque<ByteArray>()
    private var rxBuf = ByteArray(0)
    private var done = false

    private val notifyChar = BluetoothGattCharacteristic(BleIds.NOTIFY,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply { addDescriptor(BluetoothGattDescriptor(BleIds.CCC, BluetoothGattDescriptor.PERMISSION_WRITE)) }

    private val writeChar = BluetoothGattCharacteristic(BleIds.WRITE,
        BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)

    private val callback = object : BluetoothGattServerCallback() {
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            notifySize = (mtu - 3).coerceAtLeast(20)
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int,
                                                  offset: Int, characteristic: BluetoothGattCharacteristic) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf())
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
                                              descriptor: BluetoothGattDescriptor, preparedWrite: Boolean,
                                              responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (descriptor.uuid == BleIds.CCC) {
                val on = value != null && value.isNotEmpty() && (value[0].toInt() and 0x1) == 1
                if (on && !subscribed) {
                    subscribed = true; peer = device
                    stopAdvertising()
                    onPeerConnected?.invoke()
                    synchronized(lock) { tryFlushLocked() }
                }
                if (!on && device == peer) subscribed = false
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
                                                   characteristic: BluetoothGattCharacteristic,
                                                   preparedWrite: Boolean, responseNeeded: Boolean,
                                                   offset: Int, value: ByteArray?) {
            if (characteristic.uuid != BleIds.WRITE) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            if (device != peer) peer = device
            receiveBytes(value ?: ByteArray(0))
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) synchronized(lock) { tryFlushLocked() }
        }
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED && device == peer) {
                synchronized(lock) { outbox.clear() }
                fireClosed()
            }
        }
    }

    fun start(onFail: (String) -> Unit) {
        val s = bm.openGattServer(context, callback) ?: run { onFail("openGattServer failed"); return }
        server = s
        val svc = BluetoothGattService(BleIds.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(writeChar); svc.addCharacteristic(notifyChar)
        s.addService(svc)

        advertiser = bm.adapter.bluetoothLeAdvertiser ?: run { onFail("no LE advertiser"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true).build()
        val data = AdvertiseData.Builder().setIncludeTxPowerLevel(true).build()
        val scanRsp = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleIds.SERVICE))
            .setIncludeDeviceName(true).build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) { onFail("advertise failed: $errorCode") }
        }
        advCb = cb
        advertiser?.startAdvertising(settings, data, scanRsp, cb)

        scope.launch {
            while (isActive) {
                delay(100)
                if (outbox.isNotEmpty()) synchronized(lock) { tryFlushLocked() }
            }
        }
    }

    private fun stopAdvertising() { advCb?.let { advertiser?.stopAdvertising(it) }; advCb = null }

    private fun receiveBytes(bytes: ByteArray) {
        rxBuf += bytes
        while (true) {
            val i = rxBuf.indexOf('\n'.code.toByte())
            if (i < 0) break
            val line = String(rxBuf, 0, i, Charsets.UTF_8)
            rxBuf = rxBuf.copyOfRange(i + 1, rxBuf.size)
            if (line.isNotEmpty()) deliver(line)
        }
    }

    override fun send(line: String) {
        val bytes = (line + "\n").toByteArray()
        scope.launch(Dispatchers.IO) {
            synchronized(lock) {
                bytes.chunksOf(notifySize).forEach { outbox.addLast(it) }
                tryFlushLocked()
            }
        }
    }

    private fun tryFlushLocked() {
        val s = server; val d = peer
        if (s == null || d == null || !subscribed) return
        while (outbox.isNotEmpty()) {
            if (notifyChunk(s, d, outbox.first())) outbox.removeFirst() else break
        }
    }

    private fun notifyChunk(s: BluetoothGattServer, d: BluetoothDevice, chunk: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(d, notifyChar, false, chunk)
        else { @Suppress("DEPRECATION") notifyChar.value = chunk
               @Suppress("DEPRECATION") s.notifyCharacteristicChanged(d, notifyChar, false) }
        return true
    }

    override fun close() {
        if (done) return; done = true
        stopAdvertising()
        server?.clearServices()
        server?.close()
        server = null
    }
}

class BleScanner(private val adapter: BluetoothAdapter) {
    private var cb: ScanCallback? = null
    fun start(onFound: (BluetoothDevice) -> Unit, onFail: (String) -> Unit) {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { onFail("BLE unavailable, permissions? Bluetooth on?"); return }
        val c = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onFound(result.device)
            override fun onScanFailed(errorCode: Int) = onFail("scan failed: $errorCode")
        }
        cb = c
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleIds.SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), c)
    }
    fun stop() { cb?.let { adapter.bluetoothLeScanner?.stopScan(it) }; cb = null }
}

class BleClientWire(private val device: BluetoothDevice, private val scope: CoroutineScope) : BaseWire() {
    var onFail: ((String) -> Unit)? = null
    private var readyCb: (() -> Unit)? = null

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val lock = Any()
    private val outbox = ArrayDeque<ByteArray>()
    private var writing = false
    private var mtu = 20
    private var rxBuf = ByteArray(0)
    private var done = false
    @Volatile private var ready = false

    fun connect(onReady: () -> Unit) {
        gatt = device.connectGatt(null, false, callback, BluetoothDevice.TRANSPORT_LE)
        readyCb = onReady
    }

    private fun fail(msg: String) { if (!done) { done = true; onFail?.invoke(msg); fireClosed() } }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {

                if (!ready) fail("Bluetooth connection dropped (status $status)") else fireClosed()
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("discovery: $status"); return }
            val svc = g.getService(BleIds.SERVICE) ?: run { fail("service missing"); return }
            writeChar = svc.getCharacteristic(BleIds.WRITE)
            val notify = svc.getCharacteristic(BleIds.NOTIFY)
            val ccc = notify?.getDescriptor(BleIds.CCC)
            if (writeChar == null || notify == null || ccc == null) { fail("characteristics missing"); return }
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else { @Suppress("DEPRECATION") ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                   @Suppress("DEPRECATION") g.writeDescriptor(ccc) }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != BleIds.CCC) return
            if (status == BluetoothGatt.GATT_SUCCESS) g.requestMtu(247) else fail("subscribe failed: $status")
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            this@BleClientWire.mtu = (if (status == BluetoothGatt.GATT_SUCCESS) mtu - 3 else 20).coerceAtLeast(20)
            synchronized(lock) { tryWriteLocked() }
            if (!ready) { ready = true; readyCb?.invoke() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == BleIds.NOTIFY)
                receiveBytes(characteristic.value ?: return)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                             value: ByteArray) {
            if (Build.VERSION.SDK_INT >= 33 && characteristic.uuid == BleIds.NOTIFY)
                receiveBytes(value)
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            synchronized(lock) {
                writing = false
                if (status == BluetoothGatt.GATT_SUCCESS) tryWriteLocked() else fireClosed()
            }
        }
    }

    private fun receiveBytes(bytes: ByteArray) {
        rxBuf += bytes
        while (true) {
            val i = rxBuf.indexOf('\n'.code.toByte())
            if (i < 0) break
            val line = String(rxBuf, 0, i, Charsets.UTF_8)
            rxBuf = rxBuf.copyOfRange(i + 1, rxBuf.size)
            if (line.isNotEmpty()) deliver(line)
        }
    }

    override fun send(line: String) {
        val bytes = (line + "\n").toByteArray()
        scope.launch(Dispatchers.IO) {
            synchronized(lock) {
                bytes.chunksOf(mtu).forEach { outbox.addLast(it) }
                tryWriteLocked()
            }
        }
    }

    private fun tryWriteLocked() {
        if (writing || outbox.isEmpty() || writeChar == null) return
        val g = gatt ?: return
        writing = true
        val chunk = outbox.removeFirst()
        @Suppress("DEPRECATION") writeChar!!.value = chunk
        @Suppress("DEPRECATION") g.writeCharacteristic(writeChar)
    }

    override fun close() {
        if (done) return; done = true
        runCatching { gatt?.disconnect() }; runCatching { gatt?.close() }
    }
}

object BleJoin {

    suspend fun scanAndConnect(context: Context, scope: CoroutineScope, timeoutMs: Long = 15_000): BleClientWire =
        suspendCancellableCoroutine { cont ->
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (adapter == null || !adapter.isEnabled) {
                cont.resumeWith(Result.failure(java.io.IOException(
                    "Bluetooth is off, turn it on and scan again.")))
                return@suspendCancellableCoroutine
            }
            val scanner = BleScanner(adapter)
            var finished = false
            fun resume(r: Result<BleClientWire>) { if (!finished) { finished = true; scanner.stop(); cont.resumeWith(r) } }
            val watchdog = scope.launch {
                delay(timeoutMs)
                resume(Result.failure(java.io.IOException(
                    "Couldn't find the host over Bluetooth, is the host's Bluetooth on, and are you in range?")))
            }
            scanner.start(
                onFound = { device ->
                    if (!finished) {

                        finished = true; scanner.stop(); watchdog.cancel()
                        val wire = BleClientWire(device, scope)
                        var resumed = false
                        fun done(r: Result<BleClientWire>) { if (!resumed) { resumed = true; cont.resumeWith(r) } }
                        scope.launch {
                            delay(20_000)
                            done(Result.failure(java.io.IOException("Bluetooth handshake timed out")))
                        }
                        wire.onFail = { done(Result.failure(java.io.IOException(it))) }
                        wire.connect(onReady = {
                            done(Result.success(wire))

                        })
                    }
                },
                onFail = { resume(Result.failure(java.io.IOException(it))) })
        }
}
