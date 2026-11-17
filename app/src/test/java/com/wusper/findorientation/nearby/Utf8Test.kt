package com.wusper.findorientation.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Utf8Test {
    @Test
    fun asciiFits() {
        val bytes = Utf8.truncate("hello", 7)
        assertEquals("hello", bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun asciiCut() {
        assertEquals("hel", Utf8.truncate("hello", 3).toString(Charsets.UTF_8))
    }

    @Test
    fun chineseNotSplit() {
        // 方向尋找 is 5 CJK chars, 15 bytes. 7 bytes can hold only 2 chars.
        val text = "方向尋找"
        val bytes = Utf8.truncate(text, 7)
        val roundTrip = bytes.toString(Charsets.UTF_8)
        assertEquals("方向", roundTrip)
        assertTrue(bytes.size <= 7)
        // 7 would split the third character; we must stop at 6.
        assertEquals(6, bytes.size)
    }

    @Test
    fun zeroMaxIsEmpty() {
        assertEquals(0, Utf8.truncate("abc", 0).size)
    }
}
