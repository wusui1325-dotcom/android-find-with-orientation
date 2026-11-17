package com.wusper.findorientation.nearby

import com.wusper.findorientation.model.BearingSource
import com.wusper.findorientation.model.PeerSighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionCueTest {
    @Test
    fun directionBuckets() {
        assertEquals("正前方", DirectionCue.direction(0f))
        assertEquals("正前方", DirectionCue.direction(-25f))
        assertEquals("正前方", DirectionCue.direction(25f))
        assertEquals("偏右", DirectionCue.direction(26f))
        assertEquals("偏左", DirectionCue.direction(-26f))
        assertEquals("偏右", DirectionCue.direction(134f))
        assertEquals("偏左", DirectionCue.direction(-134f))
        assertEquals("在後方", DirectionCue.direction(135f))
        assertEquals("在後方", DirectionCue.direction(-180f))
        assertEquals("在後方", DirectionCue.direction(200f))
    }

    @Test
    fun unlockedCueWhenAzimuthMissing() {
        val peer = peer("a", meters = 3f, azimuth = null, source = BearingSource.NONE)
        assertEquals(DirectionCue.UNLOCKED, DirectionCue.liveLine(peer))
        assertEquals("慢慢轉身以鎖定方向", DirectionCue.UNLOCKED)
    }

    @Test
    fun liveLineIsHonestAboutSource() {
        val spin = peer("s", meters = 2.5f, azimuth = 8f, source = BearingSource.SPIN)
        val line = DirectionCue.liveLine(spin)
        assertTrue(line, line.startsWith("正前方"))
        assertTrue(line, line.contains("旋轉估計"))
        assertFalse(line, line.contains("UWB"))

        val uwb = peer("u", meters = 1.2f, azimuth = 40f, source = BearingSource.UWB)
        val uwbLine = DirectionCue.liveLine(uwb)
        assertTrue(uwbLine, uwbLine.startsWith("偏右"))
        assertTrue(uwbLine, uwbLine.contains("UWB"))
        assertFalse(uwbLine, uwbLine.contains("旋轉估計"))

        val geo = peer("g", meters = 4f, azimuth = -40f, source = BearingSource.GEO, geoAccuracy = 8f)
        val geoLine = DirectionCue.liveLine(geo)
        assertTrue(geoLine, geoLine.startsWith("偏左"))
        assertTrue(geoLine, geoLine.contains("融合定位"))
        assertFalse(geoLine, geoLine.contains("UWB"))
    }

    @Test
    fun focusPrefersSelectionThenNearestWithBearing() {
        val nearNoAz = peer("n", meters = 1f, azimuth = null, source = BearingSource.NONE)
        val farAz = peer("f", meters = 6f, azimuth = 30f, source = BearingSource.SPIN)
        val midAz = peer("m", meters = 3f, azimuth = -10f, source = BearingSource.GEO)
        val peers = listOf(nearNoAz, farAz, midAz)

        assertSame(farAz, FindFocus.focusedPeer(peers, "f"))
        assertSame(midAz, FindFocus.focusedPeer(peers, null))
        assertSame(nearNoAz, FindFocus.focusedPeer(listOf(nearNoAz), null))
        assertNull(FindFocus.focusedPeer(emptyList(), null))
        // Unknown selection falls back to nearest bearing, then nearest peer.
        assertSame(midAz, FindFocus.focusedPeer(peers, "missing"))
    }

    private fun peer(
        id: String,
        meters: Float?,
        azimuth: Float?,
        source: BearingSource,
        geoAccuracy: Float? = null
    ) = PeerSighting(
        id = id,
        name = id,
        rssi = -60,
        estimatedMeters = if (source == BearingSource.UWB || source == BearingSource.GEO) null else meters,
        geoMeters = if (source == BearingSource.GEO) meters else null,
        geoAccuracy = geoAccuracy,
        uwbMeters = if (source == BearingSource.UWB) meters else null,
        azimuthDeg = azimuth,
        elevationDeg = null,
        bearingSource = source,
        uwbCapable = source == BearingSource.UWB,
        lastSeenElapsedMs = 0L,
        bleAddress = id
    )
}
