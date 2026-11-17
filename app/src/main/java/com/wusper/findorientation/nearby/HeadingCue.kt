package com.wusper.findorientation.nearby

import kotlin.math.abs

/**
 * Cadence and strength of the "you are pointing at the target" cue.
 * Error is the absolute heading error in degrees (0 = phone top faces the peer).
 */
object HeadingCue {
    const val LOCK_DEG = 18f

    fun locked(errorDeg: Float, thresholdDeg: Float = LOCK_DEG): Boolean =
        abs(errorDeg) <= thresholdDeg

    /** Time between pulses, 200 ms when close and aligned, ~1400 ms at the lock edge. */
    fun intervalMs(errorDeg: Float, meters: Float): Long {
        val err = (abs(errorDeg) / LOCK_DEG).coerceIn(0f, 1f)
        val dist = (meters / 10f).coerceIn(0.05f, 1f)
        val t = 0.5f * err + 0.5f * dist
        return (200 + t * 1200).toLong()
    }

    /** Vibration amplitude 80–255. */
    fun amplitude(errorDeg: Float, meters: Float): Int {
        val closeness = 1f - (abs(errorDeg) / LOCK_DEG).coerceIn(0f, 1f)
        val near = 1f - (meters / 10f).coerceIn(0f, 1f)
        return (80 + (closeness * 0.5f + near * 0.5f) * 175).toInt()
    }

    fun pulseMs(errorDeg: Float, meters: Float): Long {
        val strength = (amplitude(errorDeg, meters) - 80) / 175f
        return (40 + strength * 80).toLong()
    }
}
