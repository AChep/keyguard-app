package com.artemchep.keyguard.util.yubikey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeYubiKeyTest {
    @Test
    fun fullSizeChallengeIsRejectedBeforeDeviceAccess() {
        val handle = NativeYubiKey.create()
        assertTrue(handle > 0)
        try {
            NativeYubiKey.cancel(handle)
            for ((length, expected) in listOf(63 to YubiKeyFailure.CANCELED, 64 to YubiKeyFailure.INVALID_ARGUMENT)) {
                val request = byteArrayOf(2, 2, 0, length.toByte()) + ByteArray(length) { 1 }
                val result = NativeYubiKey.execute(handle, request)
                assertEquals(expected.code, result.single().toInt())
            }
        } finally {
            NativeYubiKey.close(handle)
        }
    }

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
