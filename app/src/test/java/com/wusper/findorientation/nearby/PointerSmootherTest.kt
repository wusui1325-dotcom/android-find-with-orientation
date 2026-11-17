package com.wusper.findorientation.nearby

import com.wusper.findorientation.model.BearingSource
import com.wusper.findorientation.model.PeerSighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PointerSmootherTest {
    @Test
    fun firstAppearanceSnaps() {
        val next = PointerSmoother.step(null, 40f, 0.5f, 0.1f)
        assertEquals(40f, next.azimuthDeg!!, 0.001f)
        assertEquals(0.5f, next.radius, 0.001f)
    }

    @Test
    fun interpolatesShortArcAndRadius() {
        val start = SmoothedPointer(azimuthDeg = 10f, radius = 0.2f)
        val mid = PointerSmoother.step(start, 50f, 0.6f, 0.5f)
        assertEquals(30f, mid.azimuthDeg!!, 0.01f)
        assertEquals(0.4f, mid.radius, 0.01f)
    }

    @Test
    fun interpolatesAcross180() {
        val start = SmoothedPointer(azimuthDeg = 170f, radius = 0.5f)
        val mid = PointerSmoother.step(start, -170f, 0.5f, 0.5f)
        assertEquals(180f, abs(mid.azimuthDeg!!), 0.5f)
    }

    @Test
    fun hidesNeedleWhenAzimuthUnlocks() {
        val start = SmoothedPointer(azimuthDeg = 20f, radius = 0.4f)
        val next = PointerSmoother.step(start, null, 0.7f, 0.5f)
        assertNull(next.azimuthDeg)
        assertEquals(0.55f, next.radius, 0.01f)
    }

    @Test
    fun newlyLockedAzimuthSnapsOntoTheBearing() {
        val start = SmoothedPointer(azimuthDeg = null, radius = 0.4f)
        val next = PointerSmoother.step(start, -80f, 0.4f, 0.2f)
        assertEquals(-80f, next.azimuthDeg!!, 0.001f)
    }

    @Test
    fun stepAllDropsStalePeersAndSnapsNewOnes() {
        val current = mapOf(
            "gone" to SmoothedPointer(10f, 0.3f),
            "keep" to SmoothedPointer(0f, 0.2f)
        )
        val peers = listOf(
            peer("keep", meters = 4f, azimuth = 20f),
            peer("new", meters = 5f, azimuth = 90f)
        )
        val next = PointerSmoother.stepAll(current, peers, 0.5f)
        assertEquals(setOf("keep", "new"), next.keys)
        assertEquals(10f, next.getValue("keep").azimuthDeg!!, 0.01f)
        assertEquals(90f, next.getValue("new").azimuthDeg!!, 0.01f)
        assertEquals(0.5f, next.getValue("new").radius, 0.01f)
    }

    @Test
    fun radiusOfClampsToDisc() {
        assertEquals(0.04f, PointerSmoother.radiusOf(0.1f), 0.0001f)
        assertEquals(1f, PointerSmoother.radiusOf(40f), 0.0001f)
        assertEquals(1f, PointerSmoother.radiusOf(null), 0.0001f)
        assertEquals(0.5f, PointerSmoother.radiusOf(5f), 0.0001f)
    }

    private fun peer(id: String, meters: Float?, azimuth: Float?) = PeerSighting(
        id = id,
        name = id,
        rssi = -60,
        estimatedMeters = meters,
        geoMeters = null,
        geoAccuracy = null,
        uwbMeters = null,
        azimuthDeg = azimuth,
        elevationDeg = null,
        bearingSource = BearingSource.SPIN,
        uwbCapable = false,
        lastSeenElapsedMs = 0L,
        bleAddress = id
    )
}
