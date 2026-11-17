package com.wusper.findorientation.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class IdentityStoreTest {
    @Test
    fun sanitizeTrimsAndCaps() {
        assertEquals("客廳手機", IdentityStore.sanitizeName("  客廳手機  ", "裝置-ABCD"))
        assertEquals("abcdefghijklmnopqrstuvwx", IdentityStore.sanitizeName("abcdefghijklmnopqrstuvwxyz", "x"))
    }

    @Test
    fun sanitizeEmptyUsesFallback() {
        assertEquals("裝置-ABCD", IdentityStore.sanitizeName("   ", "裝置-ABCD"))
        assertEquals("裝置-ABCD", IdentityStore.sanitizeName("", "裝置-ABCD"))
    }

    @Test
    fun defaultNameUsesShortId() {
        val id = UUID.fromString("abcd1234-0000-4000-8000-000000000000")
        assertEquals("裝置-ABCD", IdentityStore.defaultName(id))
    }
}
