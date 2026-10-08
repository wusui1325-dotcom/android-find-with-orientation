package com.wusper.findorientation.model

enum class BearingSource { NONE, SPIN, UWB }

data class PeerSighting(
    val id: String,
    val name: String,
    val rssi: Int,
    val estimatedMeters: Float?,
    val uwbMeters: Float?,
    val azimuthDeg: Float?,
    val elevationDeg: Float?,
    val bearingSource: BearingSource,
    val uwbCapable: Boolean,
    val lastSeenElapsedMs: Long,
    val bleAddress: String
) {
    val meters: Float? get() = uwbMeters ?: estimatedMeters
    val withinTenMeters: Boolean get() = (meters ?: 99f) <= 10f
}

data class FindState(
    val displayName: String = "未命名裝置",
    val shortId: String = "----",
    val seeking: Boolean = false,
    val visible: Boolean = true,
    val uwbHardware: Boolean = false,
    val headingDeg: Float = 0f,
    val peers: List<PeerSighting> = emptyList(),
    val status: String = "尚未開始"
)
