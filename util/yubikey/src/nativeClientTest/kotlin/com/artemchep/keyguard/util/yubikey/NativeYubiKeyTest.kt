package com.artemchep.keyguard.util.yubikey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeYubiKeyTest {
    @Test
    fun cancellationCrossesNativeBoundaryWithoutAccessingHardware() {
        val handle = NativeYubiKey.create()
        assertTrue(handle > 0)
        try {
            NativeYubiKey.cancel(handle)
            val result = NativeYubiKey.execute(handle, byteArrayOf(1, 2, 0, 0))
            assertEquals(YubiKeyFailure.CANCELED.code, result.single().toInt())
        } finally {
            NativeYubiKey.close(handle)
        }
    }
}
