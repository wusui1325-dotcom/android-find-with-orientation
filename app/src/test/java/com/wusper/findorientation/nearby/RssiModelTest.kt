package com.wusper.findorientation.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssiModelTest {
    @Test
    fun oneMeterReference() {
        assertEquals(1f, RssiModel.meters(-59), 0.05f)
    }

    @Test
    fun strongerSignalIsCloser() {
        assertTrue(RssiModel.meters(-45) < RssiModel.meters(-59))
        assertTrue(RssiModel.meters(-59) < RssiModel.meters(-75))
    }

    @Test
    fun isClamped() {
        assertEquals(0.3f, RssiModel.meters(0), 0.001f)
        assertEquals(40f, RssiModel.meters(-120), 0.001f)
    }
}
