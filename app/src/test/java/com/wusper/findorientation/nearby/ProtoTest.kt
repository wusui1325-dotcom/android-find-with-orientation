package com.wusper.findorientation.nearby

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoTest {
    private val id = ByteArray(16) { it.toByte() }

    @Test
    fun advertRoundTrip() {
        val bytes = Proto.advert(Proto.FLAG_UWB, id, "方向尋找")
        val parsed = Proto.parseAdvert(bytes)!!
        assertEquals(Proto.FLAG_UWB, parsed.flags)
        assertArrayEquals(id.copyOfRange(0, 4), parsed.shortId)
        assertEquals("方向", parsed.name)
    }

    @Test
    fun advertRejectsWrongVersion() {
        val bytes = Proto.advert(0, id, "A")
        bytes[0] = 2
        assertNull(Proto.parseAdvert(bytes))
        assertNull(Proto.parseAdvert(byteArrayOf(1, 0)))
    }

    @Test
    fun identityRoundTrip() {
        val bytes = Proto.identity(id, "客廳的手機", true)
        val parsed = Proto.parseIdentity(bytes)!!
        assertTrue(parsed.uwb)
        assertEquals("客廳的手機", parsed.name)
        assertArrayEquals(id, parsed.id)
    }

    @Test
    fun identityWithoutUwb() {
        val parsed = Proto.parseIdentity(Proto.identity(id, "X", false))!!
        assertFalse(parsed.uwb)
        assertEquals("X", parsed.name)
    }

    @Test
    fun offerAcceptRoundTrip() {
        val offer = Proto.SessionOffer(42, 9, 11, byteArrayOf(1, 2), ByteArray(8) { 3 })
        val parsed = Proto.parseSession(Proto.offer(offer)) as Proto.SessionOffer
        assertEquals(42, parsed.sessionId)
        assertEquals(9, parsed.channel)
        assertEquals(11, parsed.preamble)
        assertArrayEquals(byteArrayOf(1, 2), parsed.controllerAddress)
        val accept = Proto.SessionAccept(id, byteArrayOf(9, 8))
        val parsedAccept = Proto.parseSession(Proto.accept(accept)) as Proto.SessionAccept
        assertArrayEquals(id, parsedAccept.id)
        assertArrayEquals(byteArrayOf(9, 8), parsedAccept.controleeAddress)
    }

    @Test
    fun pingCommands() {
        val start = Proto.parseSession(Proto.ping(15)) as Proto.PingCommand
        assertTrue(start.start)
        assertEquals(15, start.timeoutSec)
        val clamped = Proto.parseSession(Proto.ping(99)) as Proto.PingCommand
        assertEquals(30, clamped.timeoutSec)
        val stop = Proto.parseSession(Proto.pingStop()) as Proto.PingCommand
        assertFalse(stop.start)
        assertNull(Proto.parseSession(byteArrayOf(99)))
    }

    @Test
    fun geoRoundTrip() {
        val fix = Proto.GeoFix(22.3, 114.17, 8.5f, -12f)
        val parsed = Proto.parseGeo(Proto.geo(fix))!!
        assertEquals(22.3, parsed.lat, 0.0000001)
        assertEquals(114.17, parsed.lng, 0.0000001)
        assertEquals(8.5f, parsed.accuracy, 0.001f)
        assertEquals(-12f, parsed.heading, 0.001f)
        assertNull(Proto.parseGeo(ByteArray(10)))
    }

    @Test
    fun hexFormat() {
        assertEquals("0001020A", Proto.hex(byteArrayOf(0, 1, 2, 10)))
        assertNotNull(Proto.SERVICE)
    }
}
