package com.wusper.findorientation.nearby

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.wusper.findorientation.model.BearingSource
import com.wusper.findorientation.model.FindState
import com.wusper.findorientation.model.HardwareSupport
import com.wusper.findorientation.model.IdentityStore
import com.wusper.findorientation.model.IncomingPing
import com.wusper.findorientation.model.LastSeenPeer
import com.wusper.findorientation.model.PeerSighting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import kotlin.math.abs

class FindRepository(context: Context) : SensorEventListener {
    private val app = context.applicationContext
    private val identity = IdentityStore(app)
    private val sensors = app.getSystemService(SensorManager::class.java)
    private val locations = app.getSystemService(LocationManager::class.java)
    private val alarmer = PeerAlarmer(app)
    private var ownFix: Proto.GeoFix? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val peers = linkedMapOf<String, PeerSighting>()
    private val lastSeenMap = linkedMapOf<String, LastSeenPeer>()
    private val addressToId = hashMapOf<String, String>()
    private val idToUwb = hashMapOf<String, ByteArray>()
    private val uwbKeyToId = hashMapOf<String, String>()
    private val spin = hashMapOf<String, ArrayDeque<Pair<Float, Int>>>()
    private val offered = hashSetOf<String>()
    private val pendingPing = hashSetOf<String>()
    private var heading = 0f
    private var running = false
    private var radarActive = false
    private var sensorsOn = false
    private var locationOn = false
    private var loop: Job? = null
    private var cueJob: Job? = null
    private var controllerReady = false
    private var lastListPublish = 0L

    @Volatile
    private var radarPeers: List<PeerSighting> = emptyList()

    private fun detectHardware(): HardwareSupport {
        val pm = app.packageManager
        val uwb = pm.hasSystemFeature(PackageManager.FEATURE_UWB)
        val wifiRtt = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        val bleCs = if (Build.VERSION.SDK_INT >= 36) {
            pm.hasSystemFeature("android.hardware.bluetooth_le.channel_sounding")
        } else false
        val gps = runCatching {
            locations.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS))
        val rot = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
            sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null ||
            sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) != null
        val ir = runCatching {
            val irMgr = app.getSystemService(ConsumerIrManager::class.java)
            irMgr?.hasIrEmitter() == true
        }.getOrDefault(false)
        val mic = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        val speaker = app.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.isNotEmpty() == true
        return HardwareSupport(
            uwb = uwb,
            wifiRtt = wifiRtt,
            bleChannelSounding = bleCs,
            gps = gps,
            rotationSensor = rot,
            irBlaster = ir,
            microphone = mic,
            speaker = speaker,
            camera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ||
                pm.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        )
    }

    private val uwb = UwbRanger(app, scope, ::onUwbFix) { setStatus(it) }
    private val ble = BleStack(
        app,
        onSighting = ::onSighting,
        onIdentity = ::onIdentity,
        onLocation = ::onPeerLocation,
        onSessionCommand = ::onSessionCommand,
        onReady = ::onGattReady,
        onLog = ::setStatus
    )

    private val _state = MutableStateFlow(
        FindState(
            displayName = identity.displayName,
            shortId = Proto.hex(identity.idBytes().copyOfRange(0, 4)),
            resident = identity.resident,
            geoFallback = identity.geoFallback,
            headingCue = identity.headingCue,
            uwbHardware = uwb.hardware,
            hardware = detectHardware()
        )
    )
    val state: StateFlow<FindState> = _state

    fun latestRadarPeers(): List<PeerSighting> = radarPeers

    fun setName(name: String) {
        identity.displayName = name
        _state.update { it.copy(displayName = identity.displayName) }
        if (running) {
            ble.updateIdentity(identityPayload())
            ble.updateAdvert(advertPayload(), aggressive = _state.value.seeking)
        }
    }

    fun setVisible(value: Boolean) {
        _state.update { it.copy(visible = value) }
        if (running) restartRadio()
    }

    fun setSeeking(value: Boolean) {
        _state.update { it.copy(seeking = value) }
        if (running) {
            restartRadio()
            syncSensors()
            syncLocation()
            syncCueLoop()
        }
    }

    fun setResident(value: Boolean) {
        identity.resident = value
        _state.update { it.copy(resident = value, visible = if (value) true else it.visible) }
        if (running) restartRadio()
    }

    fun setGeoFallback(value: Boolean) {
        identity.geoFallback = value
        _state.update { it.copy(geoFallback = value) }
        if (running) syncLocation()
    }

    fun setHeadingCue(value: Boolean) {
        identity.headingCue = value
        _state.update { it.copy(headingCue = value) }
        syncCueLoop()
    }

    fun setSelectedPeer(id: String?) {
        _state.update { it.copy(selectedPeerId = id) }
    }

    fun setRadarActive(active: Boolean) {
        radarActive = active
        syncSensors()
        syncCueLoop()
    }

    fun start() {
        if (running) return
        running = true
        if (identity.resident) _state.update { it.copy(resident = true, visible = true, running = true) }
        else _state.update { it.copy(running = true) }
        syncSensors()
        syncLocation()
        restartRadio()
        loop = scope.launch {
            while (isActive && running) {
                prune()
                publishOwnLocation()
                publish(force = true)
                val seeking = _state.value.seeking
                delay(if (seeking) 1000L else 2500L)
            }
        }
        syncCueLoop()
        setStatus(
            if (uwb.hardware) "尋找中。此機支援 UWB 角度"
            else "尋找中。無 UWB 時用融合定位或旋轉估計"
        )
    }

    fun stop() {
        running = false
        loop?.cancel()
        cueJob?.cancel()
        alarmer.release()
        sensors.unregisterListener(this)
        sensorsOn = false
        stopLocation()
        ble.stop()
        uwb.stop()
        peers.clear()
        lastSeenMap.clear()
        offered.clear()
        pendingPing.clear()
        controllerReady = false
        radarPeers = emptyList()
        _state.update {
            it.copy(
                running = false,
                seeking = false,
                peers = emptyList(),
                lastSeen = emptyList(),
                incomingPing = null,
                outgoingPingTo = null
            )
        }
        publish(force = true)
        setStatus("已停止")
    }

    private fun restartRadio() {
        val snap = _state.value
        if (!snap.visible && !snap.seeking) {
            ble.stopRadio()
            uwb.stop()
            offered.clear()
            controllerReady = false
            setStatus("廣播與尋找都已關閉")
            return
        }
        ble.startRadio(
            identity = identityPayload(),
            advert = advertPayload(),
            scan = snap.seeking,
            aggressive = snap.seeking
        )
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
        val spinBearing = SpinEstimator.bearing(samples.toList(), heading)
        val locked = existing?.bearingSource == BearingSource.UWB ||
            (existing?.bearingSource == BearingSource.GEO && existing.geoMeters != null)
        val updated = PeerSighting(
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
            lastSeenElapsedMs = SystemClock.elapsedRealtime(),
            bleAddress = sighting.address,
            worldBearingDeg = existing?.worldBearingDeg
        )
        peers[short] = updated
        remember(updated)
        addressToId[sighting.address] = short
        if (_state.value.seeking) ble.connect(sighting.address)
        publish()
    }

    private fun onIdentity(address: String, remote: Proto.Identity) {
        val id = Proto.hex(remote.id)
        val short = Proto.hex(remote.id.copyOfRange(0, 4))
        addressToId[address] = short
        val prev = peers[short]
        val updated = (prev ?: blank(short, address)).copy(
            id = id,
            name = remote.name,
            uwbCapable = remote.uwb,
            lastSeenElapsedMs = SystemClock.elapsedRealtime()
        )
        peers[short] = updated
        remember(updated)
        uwb.offer?.let { ble.writeSession(address, Proto.offer(it)) }
        maybeOpenController()
        publish(force = true)
    }

    private fun onGattReady(address: String) {
        if (pendingPing.remove(address)) {
            ble.writeSession(address, Proto.ping(15))
            val short = addressToId[address]
            val name = short?.let { peers[it]?.name } ?: "對端"
            _state.update { it.copy(outgoingPingTo = short) }
            setStatus("已向 $name 發出呼叫")
        }
    }

    private fun onSessionCommand(address: String, parsed: Any) {
        when (parsed) {
            is Proto.SessionOffer -> onOffer(address, parsed)
            is Proto.SessionAccept -> onAccept(address, parsed)
            is Proto.PingCommand -> onPing(address, parsed)
        }
    }

    private fun onPing(address: String, command: Proto.PingCommand) {
        if (!command.start) {
            stopIncomingPing()
            return
        }
        val short = addressToId[address]
        val name = short?.let { peers[it]?.name } ?: "附近的裝置"
        val started = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(incomingPing = IncomingPing(address, name, command.timeoutSec, started))
        }
        alarmer.start(scope, command.timeoutSec * 1000L) {
            stopIncomingPing()
        }
        setStatus("$name 正在呼叫這部手機")
    }

    fun stopIncomingPing() {
        alarmer.stop()
        if (_state.value.incomingPing != null) {
            _state.update { it.copy(incomingPing = null) }
            setStatus("已停止鈴聲")
        }
    }

    fun pingPeer(peerId: String) {
        val peer = peers.values.find { it.id == peerId || it.id.startsWith(peerId) } ?: run {
            setStatus("找不到這台裝置，請等它再次出現")
            return
        }
        val address = peer.bleAddress
        _state.update { it.copy(selectedPeerId = peer.id, outgoingPingTo = peer.id) }
        if (ble.isConnected(address)) {
            ble.writeSession(address, Proto.ping(15))
            setStatus("已向 ${peer.name} 發出呼叫")
        } else {
            pendingPing.add(address)
            if (_state.value.seeking) ble.connect(address)
            setStatus("正在連線以呼叫 ${peer.name}…")
        }
    }

    fun stopOutgoingPing(peerId: String? = _state.value.outgoingPingTo) {
        val peer = peers.values.find { it.id == peerId || it.id.startsWith(peerId ?: "") }
        if (peer != null && ble.isConnected(peer.bleAddress)) {
            ble.writeSession(peer.bleAddress, Proto.pingStop())
        }
        pendingPing.clear()
        _state.update { it.copy(outgoingPingTo = null) }
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
        val updated = prev.copy(
            uwbMeters = fix.meters,
            azimuthDeg = fix.azimuthDeg ?: prev.azimuthDeg,
            elevationDeg = fix.elevationDeg,
            bearingSource = if (fix.azimuthDeg != null) BearingSource.UWB else prev.bearingSource,
            lastSeenElapsedMs = SystemClock.elapsedRealtime()
        )
        peers[short] = updated
        remember(updated)
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
            peers.values.map { it.bleAddress }.distinct().forEach { ble.writeSession(it, payload) }
        }
    }

    private fun prune() {
        val now = SystemClock.elapsedRealtime()
        val stale = peers.entries.filter { now - it.value.lastSeenElapsedMs > 5_000 }.map { it.key }
        stale.forEach { key ->
            peers[key]?.let { remember(it) }
            peers.remove(key)
        }
    }

    private fun remember(peer: PeerSighting) {
        lastSeenMap[peer.id] = LastSeenPeer(
            id = peer.id,
            name = peer.name,
            lastMeters = peer.meters,
            scheme = peer.bearingSource,
            lastSeenElapsedMs = peer.lastSeenElapsedMs
        )
        // Keep insertion order but cap the session list.
        if (lastSeenMap.size > 20) {
            val first = lastSeenMap.keys.first()
            lastSeenMap.remove(first)
        }
    }

    private fun publish(force: Boolean = false) {
        val visible = peers.values.filter { it.withinTenMeters }.sortedBy { it.meters ?: 99f }
        radarPeers = visible
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastListPublish < 200) return
        lastListPublish = now
        val liveIds = visible.map { it.id }.toSet()
        val history = lastSeenMap.values
            .filter { it.id !in liveIds }
            .sortedByDescending { it.lastSeenElapsedMs }
        _state.update {
            it.copy(
                running = running,
                peers = visible,
                lastSeen = history
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
        lastSeenElapsedMs = SystemClock.elapsedRealtime(),
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

    private fun syncLocation() {
        val want = running && _state.value.geoFallback && (_state.value.seeking || _state.value.visible)
        if (!want) {
            stopLocation()
            return
        }
        val fine = PackageManager.PERMISSION_GRANTED ==
            app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine) return
        stopLocation()
        val minTime = if (_state.value.seeking) 1_000L else 5_000L
        val minDist = if (_state.value.seeking) 1f else 5f
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            if (locations.isProviderEnabled(provider)) {
                runCatching { locations.requestLocationUpdates(provider, minTime, minDist, locationListener) }
            }
        }
        locationOn = true
    }

    private fun stopLocation() {
        if (!locationOn) {
            runCatching { locations.removeUpdates(locationListener) }
            return
        }
        runCatching { locations.removeUpdates(locationListener) }
        locationOn = false
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
        val updated = prev.copy(
            geoMeters = distance,
            geoAccuracy = maxOf(mine.accuracy, fix.accuracy),
            azimuthDeg = relative,
            worldBearingDeg = worldBearing,
            bearingSource = BearingSource.GEO,
            lastSeenElapsedMs = SystemClock.elapsedRealtime()
        )
        peers[short] = updated
        remember(updated)
        publish()
    }

    private fun syncSensors() {
        val want = running && _state.value.seeking && radarActive
        if (want && sensorsOn) return
        if (!want) {
            if (sensorsOn) {
                sensors.unregisterListener(this)
                sensorsOn = false
            }
            return
        }
        val rot = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (rot != null) {
            sensors.registerListener(this, rot, SensorManager.SENSOR_DELAY_GAME)
            sensorsOn = true
        }
    }

    private fun syncCueLoop() {
        cueJob?.cancel()
        val want = running && radarActive && _state.value.seeking && _state.value.headingCue
        if (!want) return
        cueJob = scope.launch {
            var lastTick = 0L
            while (isActive) {
                delay(50)
                if (alarmer.active) continue
                val snap = _state.value
                val live = radarPeers
                val target = live.find { it.id == snap.selectedPeerId }
                    ?: live.filter { it.azimuthDeg != null }.minByOrNull { it.meters ?: 99f }
                    ?: continue
                val err = abs(target.azimuthDeg ?: continue)
                if (!HeadingCue.locked(err)) continue
                val now = SystemClock.elapsedRealtime()
                val meters = target.meters ?: 10f
                if (now - lastTick < HeadingCue.intervalMs(err, meters)) continue
                lastTick = now
                alarmer.pulse(HeadingCue.amplitude(err, meters), HeadingCue.pulseMs(err, meters), tick = true)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val rot = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        val ori = FloatArray(3)
        SensorManager.getOrientation(rot, ori)
        val newHeading = Math.toDegrees(ori[0].toDouble()).toFloat()
        val delta = Angles.wrap180(newHeading - heading)
        if (abs(delta) <= 0.1f) return
        heading = newHeading
        // Only GEO bearings are world-absolute and must be recomputed from heading.
        // UWB and rotation-estimate azimuths are already relative to the phone top.
        peers.keys.toList().forEach { key ->
            val p = peers[key] ?: return@forEach
            if (p.worldBearingDeg != null) {
                val newAz = Angles.wrap180(p.worldBearingDeg - heading)
                if (newAz != p.azimuthDeg) peers[key] = p.copy(azimuthDeg = newAz)
            }
        }
        radarPeers = peers.values.filter { it.withinTenMeters }.sortedBy { it.meters ?: 99f }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
