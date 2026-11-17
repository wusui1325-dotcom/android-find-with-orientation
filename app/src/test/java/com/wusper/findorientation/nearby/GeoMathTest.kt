package com.wusper.findorientation.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    @Test
    fun samePointIsZeroMeters() {
        assertEquals(0f, GeoMath.meters(22.3, 114.17, 22.3, 114.17), 0.01f)
    }

    @Test
    fun oneDegreeLatitudeIsAbout111km() {
        val meters = GeoMath.meters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195f, meters, 200f)
    }

    @Test
    fun shortOffsetIsReasonable() {
        // ~0.00009 deg lat ≈ 10 m
        val meters = GeoMath.meters(22.30000, 114.17000, 22.30009, 114.17000)
        assertEquals(10f, meters, 1.5f)
    }

    @Test
    fun bearingNorthIsNearZero() {
        val bearing = GeoMath.bearing(22.3, 114.17, 22.4, 114.17)
        assertEquals(0f, Angles.wrap180(bearing), 1f)
    }

    @Test
    fun bearingEastIsNearNinety() {
        val bearing = GeoMath.bearing(0.0, 0.0, 0.0, 1.0)
        assertEquals(90f, Angles.wrap180(bearing), 1f)
    }

    @Test
    fun bearingSouthIs180() {
        val bearing = GeoMath.bearing(22.4, 114.17, 22.3, 114.17)
        val wrapped = Angles.wrap180(bearing)
        assertTrue("got $wrapped", kotlin.math.abs(kotlin.math.abs(wrapped) - 180f) < 1f)
    }
}
