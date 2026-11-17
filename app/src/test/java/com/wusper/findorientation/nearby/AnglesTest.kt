package com.wusper.findorientation.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnglesTest {
    @Test
    fun wrap180Identity() {
        assertEquals(0f, Angles.wrap180(0f), 0.001f)
        assertEquals(90f, Angles.wrap180(90f), 0.001f)
        assertEquals(180f, Angles.wrap180(180f), 0.001f)
        assertEquals(-180f, Angles.wrap180(-180f), 0.001f)
    }

    @Test
    fun wrap180Overflow() {
        assertEquals(-179f, Angles.wrap180(181f), 0.001f)
        assertEquals(179f, Angles.wrap180(-181f), 0.001f)
        assertEquals(0f, Angles.wrap180(360f), 0.001f)
        assertEquals(10f, Angles.wrap180(370f), 0.001f)
        assertEquals(-20f, Angles.wrap180(-380f), 0.001f)
    }

    @Test
    fun weightedMeanEmpty() {
        assertNull(Angles.weightedCircularMean(emptyList()))
    }

    @Test
    fun weightedMeanSimple() {
        val mean = Angles.weightedCircularMean(listOf(0f to 1f, 0f to 1f))
        assertEquals(0f, mean!!, 0.01f)
    }

    @Test
    fun weightedMeanSymmetric() {
        val mean = Angles.weightedCircularMean(listOf(10f to 1f, -10f to 1f))
        assertEquals(0f, mean!!, 0.01f)
    }

    @Test
    fun weightedMeanWrapsAroundNorth() {
        val mean = Angles.weightedCircularMean(listOf(170f to 1f, -170f to 1f))!!
        val abs = kotlin.math.abs(mean)
        assertEquals(180f, abs, 1f)
    }
}
