package com.artemchep.keyguard.util.windowspasskeys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsPasskeyLimitsTest {
    @Test
    fun kotlinChecksTheSameLimitsAsTheNativeBridge() {
        val native = nativeLimits()
        assertEquals(WindowsPasskeyLimits.MAX_CREDENTIAL_ID_BYTES, native.credentialIdBytes)
        assertEquals(WindowsPasskeyLimits.MAX_USER_ID_BYTES, native.userIdBytes)
        assertEquals(WindowsPasskeyLimits.MAX_TEXT_BYTES, native.textBytes)
    }

    @Test
    fun idsMustBeNonEmptyAndWithinTheirLimits() {
        assertFalse(WindowsPasskeyLimits.acceptsCredentialId(ByteArray(0)))
        assertTrue(WindowsPasskeyLimits.acceptsCredentialId(ByteArray(1023)))
        assertFalse(WindowsPasskeyLimits.acceptsCredentialId(ByteArray(1024)))
        assertFalse(WindowsPasskeyLimits.acceptsUserId(ByteArray(0)))
        assertTrue(WindowsPasskeyLimits.acceptsUserId(ByteArray(64)))
        assertFalse(WindowsPasskeyLimits.acceptsUserId(ByteArray(65)))
    }

    @Test
    fun textIsMeasuredInUtf8BytesAndCannotContainNul() {
        assertTrue(WindowsPasskeyLimits.acceptsText("a".repeat(4096)))
        assertFalse(WindowsPasskeyLimits.acceptsText("a".repeat(4097)))
        // Two UTF-8 bytes each: 2048 characters fill the limit.
        assertTrue(WindowsPasskeyLimits.acceptsText("é".repeat(2048)))
        assertFalse(WindowsPasskeyLimits.acceptsText("é".repeat(2049)))
        assertFalse(WindowsPasskeyLimits.acceptsText("example\u0000.com"))
    }
}
