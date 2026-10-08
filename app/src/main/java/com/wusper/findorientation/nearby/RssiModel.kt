package com.wusper.findorientation.nearby

import kotlin.math.PI
import kotlin.math.cos
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
    fun wrap180(deg: Float): Float {
        var v = deg % 360f
        if (v > 180f) v -= 360f
        if (v < -180f) v += 360f
        return v
    }

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
