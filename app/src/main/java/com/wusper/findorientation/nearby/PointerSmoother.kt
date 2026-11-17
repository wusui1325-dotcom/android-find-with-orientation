package com.wusper.findorientation.nearby

import com.wusper.findorientation.model.PeerSighting

data class SmoothedPointer(
    val azimuthDeg: Float?,
    val radius: Float
)

/**
 * Per-frame interpolation of a disc pointer toward the latest target.
 * Azimuth uses shortest-arc lerp; radius is linear. First appearance snaps
 * so a new peer does not have to glide in from the hub.
 */
object PointerSmoother {
    fun radiusOf(meters: Float?): Float =
        ((meters ?: 10f).coerceIn(0.4f, 10f) / 10f)

    fun step(
        current: SmoothedPointer?,
        targetAzimuthDeg: Float?,
        targetRadius: Float,
        t: Float
    ): SmoothedPointer {
        if (current == null) return SmoothedPointer(targetAzimuthDeg, targetRadius)
        val azimuth = when {
            targetAzimuthDeg == null -> null
            current.azimuthDeg == null -> targetAzimuthDeg
            else -> Angles.lerpAngle(current.azimuthDeg, targetAzimuthDeg, t)
        }
        return SmoothedPointer(azimuth, Angles.lerp(current.radius, targetRadius, t))
    }

    fun stepAll(
        current: Map<String, SmoothedPointer>,
        peers: List<PeerSighting>,
        t: Float
    ): Map<String, SmoothedPointer> {
        if (peers.isEmpty()) return emptyMap()
        val next = LinkedHashMap<String, SmoothedPointer>(peers.size)
        for (peer in peers) {
            next[peer.id] = step(current[peer.id], peer.azimuthDeg, radiusOf(peer.meters), t)
        }
        return next
    }
}
