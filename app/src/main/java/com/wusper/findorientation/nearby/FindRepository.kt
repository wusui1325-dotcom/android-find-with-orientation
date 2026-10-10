package com.wusper.findorientation.nearby

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.wusper.findorientation.model.BearingSource
import com.wusper.findorientation.model.FindState
import com.wusper.findorientation.model.IdentityStore
import com.wusper.findorientation.model.PeerSighting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import kotlin.math.pow

class FindRepository(context: Context) : SensorEventListener {
    private val app = context.applicationContext
    private val identity = IdentityStore(app)
    private val sensors = app.getSystemService(SensorManager::class.java)
    private val locations = app.getSystemService(LocationManager::class.java)
    private var ownFix: Proto.GeoFix? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val peers = linkedMapOf<String, PeerSighting>()
    private val addressToId = hashMapOf<String, String>()
    private val idToUwb = hashMapOf<String, ByteArray>()
    private val uwbKeyToId = hashMapOf<String, String>()
    private val spin = hashMapOf<String, ArrayDeque<Pair<Float, Int>>>()
    private val offered = hashSetOf<String>()
    private var heading = 0f
    private var running = false
    private var loop: Job? = null
    private var controllerReady = false

    private fun detectHardware(): com.wusper.findorientation.model.HardwareSupport {
        val pm = app.packageManager
        val uwb = pm.hasSystemFeature(PackageManager.FEATURE_UWB)
        val wifiRtt = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        val bleCs = if (Build.VERSION.SDK_INT >= 36) {
            pm.hasSystemFeature("android.hardware.bluetooth_le.channel_sounding")
        } else false
        val gps = locations.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                  locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val rot = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
        val ir = runCatching {
            val irMgr = app.getSystemService(ConsumerIrManager::class.java)
            irMgr?.hasIrEmitter() == true
        }.getOrDefault(false)
        return com.wusper.findorientation.model.HardwareSupport(
            uwb = uwb,
            wifiRtt = wifiRtt,
            bleChannelSounding = bleCs,
            gps = gps,
            rotationSensor = rot,
            irBlaster = ir,
            ultrasonicCapable = true,
            camera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        )
    }

    private val uwb = UwbRanger(app, scope, ::onUwbFix) { setStatus(it) }
    private val ble = BleStack(
        app,
        onSighting = ::onSighting,
        onIdentity = ::onIdentity,
        onAccept = ::onAccept,
        onOffer = ::onOffer,
        onLocation = ::onPeerLocation,
        onLog = ::setStatus
    )

    private val _state = MutableStateFlow(
        FindState(
            displayName = identity.displayName,
            shortId = Proto.hex(identity.idBytes().copyOfRange(0, 4)),
            resident = identity.resident,
            geoFallback = identity.geoFallback,
            uwbHardware = uwb.hardware,
            hardware = detectHardware()
        )
    )
    val state: StateFlow<FindState> = _state

    fun setName(name: String) {
        identity.displayName = name
        publishIdentity()
        _state.update { it.copy(displayName = identity.displayName) }
    }

    fun setVisible(value: Boolean) {
        _state.update { it.copy(visible = value) }
        if (running) restartRadio()
    }

    fun setSeeking(value: Boolean) {
        _state.update { it.copy(seeking = value) }
        if (running) restartRadio()
    }

    fun setResident(value: Boolean) {
        identity.resident = value
        _state.update { it.copy(resident = value, visible = if (value) true else it.visible) }
        if (running) restartRadio()
    }

    fun setGeoFallback(value: Boolean) {
        identity.geoFallback = value
        _state.update { it.copy(geoFallback = value) }
    }

    fun start() {
        if (running) return
        running = true
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (identity.resident) _state.update { it.copy(resident = true, visible = true) }
        listenLocation()
        restartRadio()
        loop = scope.launch {
            while (running) {
                prune()
                publishOwnLocation()
                publish()
                delay(33)  // ~30 Hz for real-time direction
            }
        }
        setStatus(if (uwb.hardware) "尋找中。此機支援 UWB 角度" else "尋找中。無 UWB 時用融合定位，室內再靠旋轉估計")
    }

    fun stop() {
        running = false
        loop?.cancel()
        sensors.unregisterListener(this)
        runCatching { locations.removeUpdates(locationListener) }
        ble.stop()
        uwb.stop()
        peers.clear()
        offered.clear()
        controllerReady = false
        publish()
        setStatus("已停止")
    }

    private fun restartRadio() {
        ble.stop()
        uwb.stop()
        offered.clear()
        controllerReady = false
        val snap = _state.value
        if (!snap.visible && !snap.seeking) {
            setStatus("廣播與尋找都已關閉")
            return
        }
        publishIdentity()
        ble.start(identityPayload(), advertPayload(), snap.seeking)
    }

    private fun publishIdentity() {
        ble.updateIdentity(identityPayload())
    }

    private fun identityPayload(): ByteArray {
        return Proto.identity(identity.idBytes(), identity.displayName, uwb.hardware)
    }

    private fun advertPayload(): ByteArray {
        val flags = if (uwb.hardware) Proto.FLAG_UWB else 0
        return Proto.advert(flags, identity.idBytes(), identity.displayName)
    }

    private fun onSighting(sighting: Sighting) {
        val short = Proto.hex(sighting.advert.shortId)
        if (short == _state.value.shortId) return
        val meters = RssiModel.meters(sighting.rssi)
        val existing = peers[short]
        val samples = spin.getOrPut(short) { ArrayDeque() }
        samples.addLast(heading to sighting.rssi)
        while (samples.size > 40) samples.removeFirst()
        val spinBearing = spinBearing(samples)
        val locked = existing?.bearingSource == BearingSource.UWB ||
            (existing?.bearingSource == BearingSource.GEO && existing.geoMeters != null)
        peers[short] = PeerSighting(
            id = existing?.id ?: short,
            name = existing?.name ?: sighting.advert.name.ifBlank { "客戶端 $short" },
            rssi = sighting.rssi,
            estimatedMeters = meters,
            geoMeters = existing?.geoMeters,
            geoAccuracy = existing?.geoAccuracy,
            uwbMeters = existing?.uwbMeters,
            azimuthDeg = if (locked) existing?.azimuthDeg else spinBearing,
            elevationDeg = existing?.elevationDeg,
            bearingSource = when {
                existing?.bearingSource == BearingSource.UWB -> BearingSource.UWB
                existing?.bearingSource == BearingSource.GEO && existing.geoMeters != null -> BearingSource.GEO
                spinBearing != null -> BearingSource.SPIN
                else -> BearingSource.NONE
            },
            uwbCapable = existing?.uwbCapable == true || sighting.advert.flags and Proto.FLAG_UWB != 0,
            lastSeenElapsedMs = android.os.SystemClock.elapsedRealtime(),
            bleAddress = sighting.address
        )
        addressToId[sighting.address] = short
        if (_state.value.seeking) ble.connect(sighting.address)
        publish()
    }

    private fun onIdentity(address: String, identity: Proto.Identity) {
        val id = Proto.hex(identity.id)
        val short = Proto.hex(identity.id.copyOfRange(0, 4))
        addressToId[address] = short
        val prev = peers[short]
        peers[short] = (prev ?: blank(short, address)).copy(
            id = id,
            name = identity.name,
            uwbCapable = identity.uwb,
            lastSeenElapsedMs = android.os.SystemClock.elapsedRealtime()
        )
        uwb.offer?.let { ble.writeOffer(address, Proto.offer(it)) }
        maybeOpenController()
        publish()
    }

    private fun onOffer(address: String, offer: Proto.SessionOffer) {
        if (!uwb.hardware) return
        scope.launch {
            val mine = uwb.beginControlee(offer) ?: return@launch
            val id = identity.idBytes()
            ble.publishAccept(Proto.accept(Proto.SessionAccept(id, mine)))
            setStatus("已接受對端測距")
        }
        addressToId[address]?.let { offered.add(it) }
    }

    private fun onAccept(address: String, accept: Proto.SessionAccept) {
        val short = Proto.hex(accept.id.copyOfRange(0, 4))
        idToUwb[short] = accept.controleeAddress
        uwbKeyToId[Proto.hex(accept.controleeAddress)] = short
        val peersNow = idToUwb.values.toList()
        uwb.startWithPeers(peersNow)
        setStatus("已鎖定 ${peersNow.size} 台 UWB 客戶端")
    }

    private fun onUwbFix(fix: UwbFix) {
        val short = uwbKeyToId[fix.peerKey] ?: return
        val prev = peers[short] ?: return
        peers[short] = prev.copy(
            uwbMeters = fix.meters,
            azimuthDeg = fix.azimuthDeg ?: prev.azimuthDeg,
            elevationDeg = fix.elevationDeg,
            bearingSource = if (fix.azimuthDeg != null) BearingSource.UWB else prev.bearingSource,
            lastSeenElapsedMs = android.os.SystemClock.elapsedRealtime()
        )
        publish()
    }

    private fun maybeOpenController() {
        if (!_state.value.seeking || !uwb.hardware || controllerReady) return
        val mine = identity.idBytes()
        val candidates = peers.values.filter { it.uwbCapable && it.id.length == 32 }
        val iAmController = candidates.all { it.id >= Proto.hex(mine) }
        if (!iAmController) {
            setStatus("對端識別碼較小，本機等待加入測距")
            return
        }
        controllerReady = true
        scope.launch {
            val offer = uwb.openController()
            if (offer == null) {
                controllerReady = false
                return@launch
            }
            val payload = Proto.offer(offer)
            peers.values.map { it.bleAddress }.distinct().forEach { ble.writeOffer(it, payload) }
        }
    }

    private fun spinBearing(samples: ArrayDeque<Pair<Float, Int>>): Float? {
        if (samples.size < 10) return null
        val headings = samples.map { it.first }
        val span = circularSpan(headings)
        if (span < 35f) return null
        val maxRssi = samples.maxOf { it.second }
        val weighted = samples.map { (deg, rssi) ->
            deg to 10f.pow((rssi - maxRssi) / 8f)
        }
        val peak = Angles.weightedCircularMean(weighted) ?: return null
        return Angles.wrap180(peak - heading).coerceIn(-90f, 90f)
    }

    private fun circularSpan(values: List<Float>): Float {
        val xs = values.map { kotlin.math.cos(it * Math.PI / 180.0) }.average()
        val ys = values.map { kotlin.math.sin(it * Math.PI / 180.0) }.average()
        val r = kotlin.math.sqrt(xs * xs + ys * ys)
        return (kotlin.math.acos(r.coerceIn(-1.0, 1.0)) * 2 * 180.0 / Math.PI).toFloat()
    }

    private fun prune() {
        val now = android.os.SystemClock.elapsedRealtime()
        peers.entries.removeIf { now - it.value.lastSeenElapsedMs > 5000 }
    }

    private fun publish() {
        _state.update {
            it.copy(
                headingDeg = heading,
                peers = peers.values.sortedBy { peer -> peer.meters ?: 99f }
            )
        }
    }

    private fun setStatus(text: String) {
        _state.update { it.copy(status = text) }
    }

    private fun blank(short: String, address: String) = PeerSighting(
        id = short,
        name = "客戶端 $short",
        rssi = -100,
        estimatedMeters = null,
        geoMeters = null,
        geoAccuracy = null,
        uwbMeters = null,
        azimuthDeg = null,
        elevationDeg = null,
        bearingSource = BearingSource.NONE,
        uwbCapable = false,
        lastSeenElapsedMs = android.os.SystemClock.elapsedRealtime(),
        bleAddress = address
    )

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!_state.value.geoFallback) return
            if (location.accuracy > 30f) return
            ownFix = Proto.GeoFix(location.latitude, location.longitude, location.accuracy, heading)
            publishOwnLocation()
        }

        @Deprecated("legacy")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private fun listenLocation() {
        val fine = android.content.pm.PackageManager.PERMISSION_GRANTED ==
            app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine) return
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            if (locations.isProviderEnabled(provider)) {
                runCatching { locations.requestLocationUpdates(provider, 1000L, 0f, locationListener) }
            }
        }
    }

    private fun publishOwnLocation() {
        val fix = ownFix ?: return
        if (!_state.value.geoFallback) return
        ble.publishLocation(Proto.geo(fix.copy(heading = heading)))
    }

    private fun onPeerLocation(address: String, fix: Proto.GeoFix) {
        if (!_state.value.geoFallback || fix.accuracy > 30f) return
        val short = addressToId[address] ?: return
        val prev = peers[short] ?: return
        if (prev.bearingSource == BearingSource.UWB) return
        val mine = ownFix ?: return
        val distance = GeoMath.meters(mine.lat, mine.lng, fix.lat, fix.lng)
        val worldBearing = GeoMath.bearing(mine.lat, mine.lng, fix.lat, fix.lng)
        val relative = Angles.wrap180(worldBearing - heading)
        peers[short] = prev.copy(
            geoMeters = distance,
            geoAccuracy = maxOf(mine.accuracy, fix.accuracy),
            azimuthDeg = relative,
            worldBearingDeg = worldBearing,
            bearingSource = BearingSource.GEO,
            lastSeenElapsedMs = android.os.SystemClock.elapsedRealtime()
        )
        publish()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val rot = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        val ori = FloatArray(3)
        SensorManager.getOrientation(rot, ori)
        val newHeading = Math.toDegrees(ori[0].toDouble()).toFloat()
        if (kotlin.math.abs(newHeading - heading) > 0.5f || true) {
            heading = newHeading
            // Recompute relative azimuth from world bearing for real-time pointing
            peers.keys.toList().forEach { key ->
                val p = peers[key] ?: return@forEach
                val world = p.worldBearingDeg
                if (world != null) {
                    val relative = Angles.wrap180(world - heading)
                    peers[key] = p.copy(azimuthDeg = relative)
                }
            }
            publish()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
