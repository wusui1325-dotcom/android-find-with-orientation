package com.wusper.findorientation.nearby

import kotlin.math.pow

object SpinEstimator {
    fun circularSpan(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val xs = values.map { kotlin.math.cos(it * Math.PI / 180.0) }.average()
        val ys = values.map { kotlin.math.sin(it * Math.PI / 180.0) }.average()
        val r = kotlin.math.sqrt(xs * xs + ys * ys)
        return (kotlin.math.acos(r.coerceIn(-1.0, 1.0)) * 2 * 180.0 / Math.PI).toFloat()
    }

    /**
     * Approximate relative bearing after a slow turn: the heading where RSSI peaked,
     * expressed relative to [heading] and limited to the phone-top field of view.
     */
    fun bearing(
        samples: List<Pair<Float, Int>>,
        heading: Float,
        minSamples: Int = 10,
        minSpanDeg: Float = 35f
    ): Float? {
        if (samples.size < minSamples) return null
        val span = circularSpan(samples.map { it.first })
        if (span < minSpanDeg) return null
        val maxRssi = samples.maxOf { it.second }
        val weighted = samples.map { (deg, rssi) ->
            deg to 10f.pow((rssi - maxRssi) / 8f)
        }
        val peak = Angles.weightedCircularMean(weighted) ?: return null
        return Angles.wrap180(peak - heading).coerceIn(-90f, 90f)
    }
}
