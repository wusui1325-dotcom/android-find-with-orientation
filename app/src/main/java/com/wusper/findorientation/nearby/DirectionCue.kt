package com.wusper.findorientation.nearby

import com.wusper.findorientation.model.BearingSource
import com.wusper.findorientation.model.PeerSighting
import kotlin.math.abs

object FindFocus {
    /**
     * Selected peer wins. Otherwise the nearest peer that already has a bearing,
     * else the nearest peer in range.
     */
    fun focusedPeer(peers: List<PeerSighting>, selectedId: String?): PeerSighting? {
        if (peers.isEmpty()) return null
        if (selectedId != null) {
            peers.find { it.id == selectedId }?.let { return it }
        }
        return peers.filter { it.azimuthDeg != null }.minByOrNull { it.meters ?: 99f }
            ?: peers.minByOrNull { it.meters ?: 99f }
    }
}

object DirectionCue {
    const val UNLOCKED = "慢慢轉身以鎖定方向"
    const val AHEAD_DEG = 25f
    const val BEHIND_DEG = 135f

    fun direction(azimuthDeg: Float): String {
        val a = Angles.wrap180(azimuthDeg)
        val d = abs(a)
        return when {
            d <= AHEAD_DEG -> "正前方"
            d >= BEHIND_DEG -> "在後方"
            a < 0f -> "偏左"
            else -> "偏右"
        }
    }

    fun sourceLabel(source: BearingSource, geoAccuracy: Float?): String = when (source) {
        BearingSource.UWB -> "UWB"
        BearingSource.GEO -> "融合定位 ±${geoAccuracy?.toInt() ?: "?"} 米"
        BearingSource.SPIN -> "旋轉估計"
        BearingSource.NONE -> "只有距離"
        BearingSource.WIFI_RTT -> "Wi-Fi RTT"
        BearingSource.BLE_CS -> "藍牙通道探測"
        BearingSource.ULTRASONIC -> "超聲波"
        BearingSource.IR -> "紅外"
    }

    fun liveLine(peer: PeerSighting): String {
        val az = peer.azimuthDeg ?: return UNLOCKED
        val dist = peer.meters?.let { "%.1f 米".format(it) } ?: "距離未知"
        return "${direction(az)} · $dist · ${sourceLabel(peer.bearingSource, peer.geoAccuracy)}"
    }
}
