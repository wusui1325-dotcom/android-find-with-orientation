package com.wusper.findorientation.nearby

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

object RssiModel {
    private const val RSSI_AT_ONE_METER = -59.0
    private const val PATH_LOSS = 2.2

    fun meters(rssi: Int): Float {
        val exponent = (RSSI_AT_ONE_METER - rssi) / (10.0 * PATH_LOSS)
        return 10.0.pow(exponent).toFloat().coerceIn(0.3f, 40f)
    }
}

object Angles {
    const val POINTER_TAU_SEC = 0.11f
    const val HEADING_ALPHA_MIN = 0.12f
    const val HEADING_ALPHA_MAX = 0.92f
    const val HEADING_FAST_DEG = 12f

    fun wrap180(deg: Float): Float {
        var v = deg % 360f
        if (v > 180f) v -= 360f
        if (v < -180f) v += 360f
        return v
    }

    fun shortestDelta(fromDeg: Float, toDeg: Float): Float = wrap180(toDeg - fromDeg)

    fun lerp(from: Float, to: Float, t: Float): Float {
        val a = t.coerceIn(0f, 1f)
        return from + (to - from) * a
    }

    /** Shortest-arc circular interpolation, result wrapped to (-180, 180]. */
    fun lerpAngle(fromDeg: Float, toDeg: Float, t: Float): Float =
        wrap180(fromDeg + shortestDelta(fromDeg, toDeg) * t.coerceIn(0f, 1f))

    fun frameAlpha(dtSec: Float, tauSec: Float = POINTER_TAU_SEC): Float {
        if (dtSec <= 0f || tauSec <= 0f) return 0f
        return (1f - exp(-dtSec / tauSec)).coerceIn(0f, 1f)
    }

    /**
     * Complementary-style gain: nearly follow a quick turn, heavily damp
     * sub-degree jitter when the phone is still.
     */
    fun headingSmoothAlpha(deltaDeg: Float): Float {
        val d = abs(wrap180(deltaDeg))
        val t = ((d - 0.3f) / HEADING_FAST_DEG).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t)
        return HEADING_ALPHA_MIN + s * (HEADING_ALPHA_MAX - HEADING_ALPHA_MIN)
    }

    fun filterHeading(previousDeg: Float, sampleDeg: Float): Float {
        val alpha = headingSmoothAlpha(shortestDelta(previousDeg, sampleDeg))
        return lerpAngle(previousDeg, sampleDeg, alpha)
    }

    fun relativeAzimuth(worldBearingDeg: Float, headingDeg: Float): Float =
        wrap180(worldBearingDeg - headingDeg)

    fun worldBearing(headingDeg: Float, relativeAzimuthDeg: Float): Float =
        wrap180(headingDeg + relativeAzimuthDeg)

    fun weightedCircularMean(samples: List<Pair<Float, Float>>): Float? {
        if (samples.isEmpty()) return null
        var x = 0.0
        var y = 0.0
        for ((deg, weight) in samples) {
            val rad = deg * PI / 180.0
            x += cos(rad) * weight
            y += sin(rad) * weight
        }
        if (x == 0.0 && y == 0.0) return null
        return (kotlin.math.atan2(y, x) * 180.0 / PI).toFloat()
    }
}
