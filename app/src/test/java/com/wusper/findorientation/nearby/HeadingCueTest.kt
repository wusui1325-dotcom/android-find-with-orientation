package com.wusper.findorientation.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingCueTest {
    @Test
    fun lockWindow() {
        assertTrue(HeadingCue.locked(0f))
        assertTrue(HeadingCue.locked(18f))
        assertTrue(HeadingCue.locked(-17f))
        assertFalse(HeadingCue.locked(19f))
        assertFalse(HeadingCue.locked(-30f))
    }

    @Test
    fun closerAndStraighterIsFasterAndStronger() {
        val tight = HeadingCue.intervalMs(0f, 0.5f)
        val loose = HeadingCue.intervalMs(18f, 10f)
        assertTrue("$tight vs $loose", tight < loose)
        assertTrue(HeadingCue.amplitude(0f, 0.5f) > HeadingCue.amplitude(18f, 10f))
        assertTrue(HeadingCue.pulseMs(0f, 0.5f) >= HeadingCue.pulseMs(18f, 10f))
    }

    @Test
    fun rangesAreBounded() {
        listOf(0f to 0.3f, 9f to 5f, 18f to 10f, 90f to 40f).forEach { (err, m) ->
            val interval = HeadingCue.intervalMs(err, m)
            val amp = HeadingCue.amplitude(err, m)
            val pulse = HeadingCue.pulseMs(err, m)
            assertTrue(interval in 200..1400)
            assertTrue(amp in 80..255)
            assertTrue(pulse in 40..120)
            assertEquals(interval, HeadingCue.intervalMs(-err, m))
        }
    }
}
