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
import android.os.ParcelUuid
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
    private val onAccept: (address: String, Proto.SessionAccept) -> Unit,
    private val onOffer: (address: String, Proto.SessionOffer) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val scanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner
    private val advertiser = adapter?.bluetoothLeAdvertiser
    private var gattServer: BluetoothGattServer? = null
    private val clients = linkedMapOf<String, BluetoothGatt>()
    private val centrals = linkedMapOf<String, BluetoothDevice>()
    private var identityPayload = ByteArray(0)
    private var acceptPayload: ByteArray? = null
    private val parcel = ParcelUuid(Proto.SERVICE)
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getServiceData(parcel) ?: return
            val advert = Proto.parseAdvert(data) ?: return
            onSighting(Sighting(result.device.address, result.rssi, advert))
        }

        override fun onScanFailed(errorCode: Int) {
            onLog("掃描失敗 $errorCode")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            onLog("廣播失敗 $errorCode")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) centrals[device.address] = device
            if (newState == BluetoothProfile.STATE_DISCONNECTED) centrals.remove(device.address)
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
            if (parsed is Proto.SessionOffer) onOffer(device.address, parsed)
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
    fun publishAccept(payload: ByteArray) {
        acceptPayload = payload
        val service = gattServer?.getService(Proto.SERVICE) ?: return
        val ch = service.getCharacteristic(Proto.SESSION) ?: return
        @Suppress("DEPRECATION")
        ch.value = payload
        centrals.values.forEach { device ->
            runCatching { gattServer?.notifyCharacteristicChanged(device, ch, false) }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(identity: ByteArray, advert: ByteArray) {
        identityPayload = identity
        openServer()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(parcel).setIncludeDeviceName(false).build()
        val scanResponse = AdvertiseData.Builder().addServiceData(parcel, advert).build()
        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        val filters = listOf(ScanFilter.Builder().setServiceUuid(parcel).build())
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(filters, scanSettings, scanCallback)
        onLog("藍牙廣播與掃描已開始")
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (clients.containsKey(address)) return
        val device = adapter?.getRemoteDevice(address) ?: return
        val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    clients.remove(address)
                    runCatching { g.close() }
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
                    Proto.parseIdentity(value)?.let { onIdentity(address, it) }
                    val session = g.getService(Proto.SERVICE)?.getCharacteristic(Proto.SESSION) ?: return
                    g.setCharacteristicNotification(session, true)
                    session.getDescriptor(cccd)?.let { desc ->
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(desc)
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value ?: return
                val parsed = Proto.parseSession(value) ?: return
                if (parsed is Proto.SessionAccept) onAccept(address, parsed)
            }
        }, BluetoothDevice.TRANSPORT_LE)
        clients[address] = gatt
    }

    @SuppressLint("MissingPermission")
    fun writeOffer(address: String, payload: ByteArray) {
        val gatt = clients[address] ?: return
        val ch = gatt.getService(Proto.SERVICE)?.getCharacteristic(Proto.SESSION) ?: return
        @Suppress("DEPRECATION")
        ch.value = payload
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        gatt.writeCharacteristic(ch)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        centrals.clear()
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
        server.addService(service)
        gattServer = server
    }

}
