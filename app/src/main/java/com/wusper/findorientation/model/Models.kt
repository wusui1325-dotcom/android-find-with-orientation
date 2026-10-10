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
    val worldBearingDeg: Float? = null  // absolute bearing for real-time relative update
) {
    val meters: Float? get() = uwbMeters ?: geoMeters ?: estimatedMeters
    val withinTenMeters: Boolean get() = (meters ?: 99f) <= 10f
}

data class HardwareSupport(
    val uwb: Boolean = false,
    val wifiRtt: Boolean = false,
    val bleChannelSounding: Boolean = false,
    val gps: Boolean = false,
    val rotationSensor: Boolean = false,
    val irBlaster: Boolean = false,
    val ultrasonicCapable: Boolean = true,  // mic + speaker almost always present
    val camera: Boolean = true
)

data class FindState(
    val displayName: String = "未命名裝置",
    val shortId: String = "----",
    val seeking: Boolean = false,
    val visible: Boolean = true,
    val resident: Boolean = false,
    val geoFallback: Boolean = true,
    val uwbHardware: Boolean = false,
    val headingDeg: Float = 0f,
    val peers: List<PeerSighting> = emptyList(),
    val status: String = "尚未開始",
    val hardware: HardwareSupport = HardwareSupport()
)
