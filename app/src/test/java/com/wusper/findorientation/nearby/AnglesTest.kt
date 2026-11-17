package com.wusper.findorientation.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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

    @Test
    fun shortestDeltaPrefersShortArc() {
        assertEquals(20f, Angles.shortestDelta(170f, -170f), 0.01f)
        assertEquals(-20f, Angles.shortestDelta(-170f, 170f), 0.01f)
        assertEquals(90f, Angles.shortestDelta(0f, 90f), 0.01f)
        assertEquals(-10f, Angles.shortestDelta(5f, -5f), 0.01f)
    }

    @Test
    fun lerpAngleEndpointsAndMidpoint() {
        assertEquals(10f, Angles.lerpAngle(10f, 50f, 0f), 0.01f)
        assertEquals(50f, Angles.lerpAngle(10f, 50f, 1f), 0.01f)
        assertEquals(30f, Angles.lerpAngle(10f, 50f, 0.5f), 0.01f)
    }

    @Test
    fun lerpAngleCrosses180() {
        val mid = Angles.lerpAngle(170f, -170f, 0.5f)
        assertEquals(180f, abs(mid), 0.5f)
        val stepped = Angles.lerpAngle(170f, -170f, 0.25f)
        assertTrue("should advance toward south, got $stepped", stepped > 170f || stepped < -170f)
    }

    @Test
    fun lerpLinear() {
        assertEquals(4f, Angles.lerp(2f, 6f, 0.5f), 0.001f)
        assertEquals(2f, Angles.lerp(2f, 6f, 0f), 0.001f)
        assertEquals(6f, Angles.lerp(2f, 6f, 1f), 0.001f)
        assertEquals(6f, Angles.lerp(2f, 6f, 2f), 0.001f)
    }

    @Test
    fun frameAlphaGrowsWithDt() {
        assertEquals(0f, Angles.frameAlpha(0f), 0.0001f)
        assertEquals(0f, Angles.frameAlpha(-0.01f), 0.0001f)
        val slow = Angles.frameAlpha(1f / 120f)
        val mid = Angles.frameAlpha(1f / 60f)
        val fast = Angles.frameAlpha(0.2f)
        assertTrue("$slow < $mid < $fast", slow < mid && mid < fast)
        assertTrue(fast > 0.8f)
        assertEquals(1f, Angles.frameAlpha(10f), 0.001f)
    }

    @Test
    fun headingAlphaIsSmallWhenStillAndLargeWhenTurning() {
        val still = Angles.headingSmoothAlpha(0.2f)
        val modest = Angles.headingSmoothAlpha(4f)
        val fast = Angles.headingSmoothAlpha(45f)
        assertEquals(Angles.HEADING_ALPHA_MIN, still, 0.001f)
        assertTrue("$still < $modest < $fast", still < modest && modest < fast)
        assertEquals(Angles.HEADING_ALPHA_MAX, fast, 0.001f)
        assertEquals(still, Angles.headingSmoothAlpha(-0.2f), 0.001f)
    }

    @Test
    fun filterHeadingDampsJitter() {
        var heading = 10f
        repeat(40) { i ->
            val sample = 10f + if (i % 2 == 0) 0.4f else -0.4f
            heading = Angles.filterHeading(heading, sample)
        }
        assertEquals(10f, heading, 0.35f)
    }

    @Test
    fun filterHeadingFollowsAQuickTurn() {
        val out = Angles.filterHeading(0f, 90f)
        assertTrue("should nearly catch a 90° step, got $out", out > 75f)
        assertTrue(out < 90f)
    }

    @Test
    fun filterHeadingCrossesSouthOnShortArc() {
        val out = Angles.filterHeading(170f, -170f)
        assertTrue("must not go through 0, got $out", abs(out) > 160f)
        assertEquals(-171.6f, out, 2f)
    }

    @Test
    fun relativeAzimuthTracksHeadingChanges() {
        val world = 80f
        assertEquals(80f, Angles.relativeAzimuth(world, 0f), 0.01f)
        assertEquals(50f, Angles.relativeAzimuth(world, 30f), 0.01f)
        assertEquals(0f, Angles.relativeAzimuth(world, 80f), 0.01f)
        assertEquals(-90f, Angles.relativeAzimuth(world, 170f), 0.01f)
        assertEquals(-120f, Angles.relativeAzimuth(world, -160f), 0.01f)
    }

    @Test
    fun worldBearingRoundTrip() {
        val world = Angles.worldBearing(40f, -25f)
        assertEquals(15f, world, 0.01f)
        assertEquals(-25f, Angles.relativeAzimuth(world, 40f), 0.01f)
        // A later heading change keeps the same world lock.
        assertEquals(-55f, Angles.relativeAzimuth(world, 70f), 0.01f)
    }
}
