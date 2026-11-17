package com.wusper.findorientation.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpinEstimatorTest {
    @Test
    fun spanOfSingleHeadingIsZero() {
        assertEquals(0f, SpinEstimator.circularSpan(listOf(40f, 40f, 40f)), 0.5f)
    }

    @Test
    fun spanGrowsWithSpread() {
        val tight = SpinEstimator.circularSpan(listOf(0f, 5f, -5f))
        val wide = SpinEstimator.circularSpan((0..180 step 10).map { it.toFloat() })
        assertTrue("$tight < $wide", tight < wide)
        assertTrue(wide > 35f)
    }

    @Test
    fun needsTurnAndSamples() {
        val short = List(5) { it.toFloat() to -70 }
        assertNull(SpinEstimator.bearing(short, heading = 0f))
        val noTurn = List(12) { 10f to -70 }
        assertNull(SpinEstimator.bearing(noTurn, heading = 10f))
    }

    @Test
    fun peakHeadingBecomesRelativeBearing() {
        // Walk a 60° arc; strongest RSSI at heading 30 while current heading is 0.
        val samples = (0..12).map { i ->
            val heading = i * 5f
            val rssi = if (heading == 30f) -40 else -80
            heading to rssi
        }
        val bearing = SpinEstimator.bearing(samples, heading = 0f)
        assertNotNull(bearing)
        assertEquals(30f, bearing!!, 8f)
    }

    @Test
    fun rearPeakIsFullCircle() {
        // Walk a full turn; strongest RSSI is behind the user (heading 180, current 0).
        val samples = (0..36).map { i ->
            val heading = i * 10f
            val rssi = if (heading == 180f) -40 else -80
            heading to rssi
        }
        val bearing = SpinEstimator.bearing(samples, heading = 0f)
        assertNotNull(bearing)
        val wrapped = Angles.wrap180(bearing!!)
        assertTrue("rear peak must not be coerced into ±90, got $wrapped", abs(wrapped) > 90f)
        assertEquals(180f, abs(wrapped), 15f)
    }

    @Test
    fun rearPeakRelativeToTurnedPhone() {
        val samples = (0..36).map { i ->
            val heading = i * 10f
            val rssi = if (heading == 180f) -40 else -80
            heading to rssi
        }
        val bearing = SpinEstimator.bearing(samples, heading = 90f)
        assertNotNull(bearing)
        assertEquals(90f, bearing!!, 15f)
    }
}
