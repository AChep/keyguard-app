package com.artemchep.keyguard.util.fido2

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals

class NativeFido2Test {
    @Test
    fun assertionWireIsAcceptedBeforeCancellationWithoutAccessingHardware() {
        val handle = NativeFido2.create()
        val request = Fido2AssertionRequest(
            "vault.example.com", "https://vault.example.com", ByteArray(32),
            listOf(Fido2AllowedCredential(byteArrayOf(1))),
        )
        try {
            NativeFido2.cancel(handle)
            assertContentEquals(
                byteArrayOf(Fido2Failure.CANCELED.code.toByte()),
                NativeFido2.execute(handle, encodeFido2Request(Fido2Operation.Assert(request), "é123")),
            )
        } finally {
            NativeFido2.close(handle)
        }
    }

    @Test
    fun canceledAndClosedHandlesNeverOpenADevice() {
        val handle = NativeFido2.create()
        assertNotEquals(0L, handle)
        val request =
            encodeFido2Request(Fido2Operation.Register(ByteArray(32), ByteArray(32)), null)
        try {
            NativeFido2.cancel(handle)
            assertContentEquals(
                byteArrayOf(Fido2Failure.CANCELED.code.toByte()),
                NativeFido2.execute(handle, request),
            )
        } finally {
            NativeFido2.close(handle)
        }
        assertContentEquals(
            byteArrayOf(Fido2Failure.INVALID_ARGUMENT.code.toByte()),
            NativeFido2.execute(handle, request),
        )
    }
}
