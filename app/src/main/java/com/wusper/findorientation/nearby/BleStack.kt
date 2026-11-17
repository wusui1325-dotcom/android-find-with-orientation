package com.wusper.findorientation.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.ArrayDeque
import java.util.UUID

data class Sighting(
    val address: String,
    val rssi: Int,
    val advert: Proto.Advert
)

class BleStack(
    private val context: Context,
    private val onSighting: (Sighting) -> Unit,
    private val onIdentity: (address: String, Proto.Identity) -> Unit,
    private val onLocation: (address: String, Proto.GeoFix) -> Unit,
    private val onSessionCommand: (address: String, Any) -> Unit,
    private val onReady: (address: String) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val scanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner
    private val advertiser = adapter?.bluetoothLeAdvertiser
    private var gattServer: BluetoothGattServer? = null
    private val clients = linkedMapOf<String, BluetoothGatt>()
    private val ready = hashSetOf<String>()
    private val centrals = linkedMapOf<String, BluetoothDevice>()
    private val retries = hashMapOf<String, Int>()
    private val writes = ArrayDeque<Pair<String, ByteArray>>()
    private var writing = false
    private var identityPayload = ByteArray(0)
    private var advertPayload = ByteArray(0)
    private var acceptPayload: ByteArray? = null
    private var locationPayload = ByteArray(0)
    private var scanning = false
    private var advertising = false
    private var wantConnections = false
    private val parcel = ParcelUuid(Proto.SERVICE)
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getServiceData(parcel) ?: return
            val advert = Proto.parseAdvert(data) ?: return
            onMain { onSighting(Sighting(result.device.address, result.rssi, advert)) }
        }

        override fun onScanFailed(errorCode: Int) {
            onMain { onLog("掃描失敗 $errorCode") }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            advertising = false
            onMain { onLog("廣播失敗 $errorCode") }
        }

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized(lock) { centrals[device.address] = device }
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(lock) { centrals.remove(device.address) }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val payload = when (characteristic.uuid) {
                Proto.IDENTITY -> identityPayload
                Proto.SESSION -> acceptPayload ?: byteArrayOf()
                Proto.LOCATION -> locationPayload
                else -> byteArrayOf()
            }
            val slice = if (offset >= payload.size) byteArrayOf() else payload.copyOfRange(offset, payload.size)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            val parsed = Proto.parseSession(value) ?: return
            onMain { onSessionCommand(device.address, parsed) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    fun updateIdentity(payload: ByteArray) {
        identityPayload = payload
    }

    @SuppressLint("MissingPermission")
    fun publishLocation(payload: ByteArray) {
        locationPayload = payload
        val ch = gattServer?.getService(Proto.SERVICE)?.getCharacteristic(Proto.LOCATION) ?: return
        notifyAll(ch, payload)
    }

    @SuppressLint("MissingPermission")
    fun publishAccept(payload: ByteArray) {
        acceptPayload = payload
        val ch = gattServer?.getService(Proto.SERVICE)?.getCharacteristic(Proto.SESSION) ?: return
        notifyAll(ch, payload)
    }

    @SuppressLint("MissingPermission")
    private fun notifyAll(ch: BluetoothGattCharacteristic, payload: ByteArray) {
        val devices = synchronized(lock) { centrals.values.toList() }
        devices.forEach { device ->
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    gattServer?.notifyCharacteristicChanged(device, ch, false, payload)
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = payload
                    @Suppress("DEPRECATION")
                    gattServer?.notifyCharacteristicChanged(device, ch, false)
                }
            }
        }
    }

    /**
     * Start or refresh advertising / scanning without tearing down the GATT server or
     * existing client connections.
     */
    @SuppressLint("MissingPermission")
    fun startRadio(identity: ByteArray, advert: ByteArray, scan: Boolean, aggressive: Boolean) {
        identityPayload = identity
        advertPayload = advert
        wantConnections = scan
        openServer()
        restartAdvert(advert, aggressive)
        if (scan) startScan(aggressive) else stopScan()
        onMain {
            onLog(
                if (scan) {
                    if (aggressive) "藍牙廣播與掃描已開始（尋找模式）" else "藍牙廣播與掃描已開始"
                } else {
                    "常駐廣播已開始，等待其他客戶端尋找"
                }
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun updateAdvert(advert: ByteArray, aggressive: Boolean) {
        advertPayload = advert
        restartAdvert(advert, aggressive)
    }

    @SuppressLint("MissingPermission")
    private fun restartAdvert(advert: ByteArray, aggressive: Boolean) {
        stopAdvert()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                if (aggressive) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                else AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
            )
            .setTxPowerLevel(
                if (aggressive) AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                else AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
            )
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(parcel).setIncludeDeviceName(false).build()
        val scanResponse = AdvertiseData.Builder().addServiceData(parcel, advert).build()
        runCatching { advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback) }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(aggressive: Boolean) {
        stopScan()
        val filters = listOf(ScanFilter.Builder().setServiceUuid(parcel).build())
        val scanSettings = ScanSettings.Builder()
            .setScanMode(
                if (aggressive) ScanSettings.SCAN_MODE_LOW_LATENCY
                else ScanSettings.SCAN_MODE_BALANCED
            )
            .build()
        runCatching { scanner?.startScan(filters, scanSettings, scanCallback) }
        scanning = true
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) {
            runCatching { scanner?.stopScan(scanCallback) }
            return
        }
        runCatching { scanner?.stopScan(scanCallback) }
        scanning = false
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvert() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
    }

    fun isConnected(address: String): Boolean = synchronized(lock) { address in ready }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        synchronized(lock) {
            if (clients.containsKey(address)) return
        }
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    synchronized(lock) { retries[address] = 0 }
                    g.discoverServices()
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    synchronized(lock) {
                        clients.remove(address)
                        ready.remove(address)
                    }
                    runCatching { g.close() }
                    maybeRetry(address)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val identity = g.getService(Proto.SERVICE)?.getCharacteristic(Proto.IDENTITY) ?: return
                @Suppress("DEPRECATION")
                g.readCharacteristic(identity)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (characteristic.uuid == Proto.IDENTITY && status == BluetoothGatt.GATT_SUCCESS) {
                    val value = characteristic.value ?: return
                    Proto.parseIdentity(value)?.let { parsed ->
                        onMain { onIdentity(address, parsed) }
                    }
                    val session = g.getService(Proto.SERVICE)?.getCharacteristic(Proto.SESSION) ?: return
                    g.setCharacteristicNotification(session, true)
                    val location = g.getService(Proto.SERVICE)?.getCharacteristic(Proto.LOCATION)
                    if (location != null) g.setCharacteristicNotification(location, true)
                    session.getDescriptor(cccd)?.let { desc ->
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(desc)
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                val service = g.getService(Proto.SERVICE) ?: return
                val location = service.getCharacteristic(Proto.LOCATION)
                if (descriptor.characteristic.uuid == Proto.SESSION && location != null) {
                    location.getDescriptor(cccd)?.let { desc ->
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(desc)
                        return
                    }
                }
                markReady(address)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                onMain { drainWrite() }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value ?: return
                when (characteristic.uuid) {
                    Proto.SESSION -> {
                        val parsed = Proto.parseSession(value) ?: return
                        onMain { onSessionCommand(address, parsed) }
                    }
                    Proto.LOCATION -> Proto.parseGeo(value)?.let { fix ->
                        onMain { onLocation(address, fix) }
                    }
                }
            }
        }
        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        synchronized(lock) { clients[address] = gatt }
    }

    private fun markReady(address: String) {
        val first = synchronized(lock) { ready.add(address) }
        if (first) onMain {
            onReady(address)
            drainWrite()
        }
    }

    private fun maybeRetry(address: String) {
        if (!wantConnections) return
        val attempt = synchronized(lock) { retries[address] ?: 0 }
        if (attempt >= 3) return
        synchronized(lock) { retries[address] = attempt + 1 }
        val delayMs = 1000L shl attempt
        main.postDelayed({ connect(address) }, delayMs)
    }

    fun writeSession(address: String, payload: ByteArray) {
        onMain {
            writes.addLast(address to payload)
            drainWrite()
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainWrite() {
        if (writing) return
        val next = writes.pollFirst() ?: return
        val (address, payload) = next
        val gatt = synchronized(lock) { clients[address] }
        if (gatt == null || address !in synchronized(lock) { ready }) {
            // Put it back and wait for the connection to become ready, unless we have no client at all.
            if (gatt != null) writes.addFirst(address to payload)
            return
        }
        val ch = gatt.getService(Proto.SERVICE)?.getCharacteristic(Proto.SESSION) ?: run {
            writes.addFirst(address to payload)
            return
        }
        writing = true
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = payload
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(ch)
            }
        }.getOrDefault(false)
        if (!ok) {
            writing = false
            drainWrite()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopRadio() {
        wantConnections = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        stopAdvert()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        stopRadio()
        synchronized(lock) {
            clients.values.forEach { runCatching { it.close() } }
            clients.clear()
            ready.clear()
            centrals.clear()
            retries.clear()
            writes.clear()
            writing = false
        }
        runCatching { gattServer?.close() }
        gattServer = null
    }

    @SuppressLint("MissingPermission")
    private fun openServer() {
        if (gattServer != null) return
        val server = manager?.openGattServer(context, serverCallback) ?: return
        val service = BluetoothGattService(Proto.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val identity = BluetoothGattCharacteristic(
            Proto.IDENTITY,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val session = BluetoothGattCharacteristic(
            Proto.SESSION,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )
        session.addDescriptor(
            BluetoothGattDescriptor(
                cccd,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(identity)
        service.addCharacteristic(session)
        val location = BluetoothGattCharacteristic(
            Proto.LOCATION,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        location.addDescriptor(
            BluetoothGattDescriptor(
                cccd,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(location)
        server.addService(service)
        gattServer = server
    }
}
