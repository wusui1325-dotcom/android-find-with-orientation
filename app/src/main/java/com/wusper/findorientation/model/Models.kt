package com.wusper.findorientation.model

enum class BearingSource { NONE, SPIN, GEO, UWB, WIFI_RTT, BLE_CS, ULTRASONIC, IR }

data class PeerSighting(
    val id: String,
    val name: String,
    val rssi: Int,
    val estimatedMeters: Float?,
    val geoMeters: Float?,
    val geoAccuracy: Float?,
    val uwbMeters: Float?,
    val azimuthDeg: Float?,
    val elevationDeg: Float?,
    val bearingSource: BearingSource,
    val uwbCapable: Boolean,
    val lastSeenElapsedMs: Long,
    val bleAddress: String,
    val worldBearingDeg: Float? = null
) {
    val meters: Float? get() = uwbMeters ?: geoMeters ?: estimatedMeters
    val withinTenMeters: Boolean get() = (meters ?: 99f) <= 10f
}

data class LastSeenPeer(
    val id: String,
    val name: String,
    val lastMeters: Float?,
    val scheme: BearingSource,
    val lastSeenElapsedMs: Long
)

data class HardwareSupport(
    val uwb: Boolean = false,
    val wifiRtt: Boolean = false,
    val bleChannelSounding: Boolean = false,
    val gps: Boolean = false,
    val rotationSensor: Boolean = false,
    val irBlaster: Boolean = false,
    val microphone: Boolean = false,
    val speaker: Boolean = false,
    val camera: Boolean = false
)

data class IncomingPing(
    val fromAddress: String,
    val fromName: String,
    val timeoutSec: Int,
    val startedElapsedMs: Long
)

data class FindState(
    val displayName: String = "未命名裝置",
    val shortId: String = "----",
    val running: Boolean = false,
    val seeking: Boolean = false,
    val visible: Boolean = true,
    val resident: Boolean = false,
    val geoFallback: Boolean = true,
    val headingCue: Boolean = false,
    val uwbHardware: Boolean = false,
    val peers: List<PeerSighting> = emptyList(),
    val lastSeen: List<LastSeenPeer> = emptyList(),
    val selectedPeerId: String? = null,
    val incomingPing: IncomingPing? = null,
    val outgoingPingTo: String? = null,
    val status: String = "尚未開始",
    val hardware: HardwareSupport = HardwareSupport()
)
