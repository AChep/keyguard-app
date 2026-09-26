package com.artemchep.keyguard

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.BiometricAuthException
import kotlinx.coroutines.test.runTest
import platform.LocalAuthentication.LAContext
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.errSecUserCanceled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class KeychainRepositoryAppleTest {
    @Test
    fun protectedOperationsUseAuthenticatedContextAndNeverUseGenericStorage() = runTest {
        withBridge { bridge ->
            val repository = repository()
            val context = LAContext()
            repository.putBiometric("unlock", "wrapped-key", context)
            assertEquals("unlock", bridge.writtenAccount)
            assertEquals("wrapped-key", bridge.writtenValue)
            assertSame(context, bridge.writtenContext)

            bridge.readResult = KeychainBiometricResult("wrapped-key", errSecSuccess)
            assertEquals("wrapped-key", repository.getBiometric("unlock", context))
            assertEquals("unlock", bridge.readAccount)
            assertSame(context, bridge.readContext)
        }
    }

    @Test
    fun missingProtectedKeyDoesNotFallBackToGenericKey() = runTest {
        withBridge { bridge ->
            bridge.readResult = KeychainBiometricResult(null, errSecItemNotFound)
            val exception = assertFailsWith<BiometricAuthException> {
                repository().getBiometric("unlock", LAContext())
            }
            assertEquals(BiometricAuthException.ERROR_KEY_INVALIDATED, exception.code)
        }
    }

    @Test
    fun canceledAndTemporarilyUnavailableReadsDoNotInvalidateEnrollment() = runTest {
        withBridge { bridge ->
            for ((status, code) in listOf(
                errSecUserCanceled to BiometricAuthException.ERROR_USER_CANCELED,
                errSecInteractionNotAllowed to BiometricAuthException.ERROR_HW_UNAVAILABLE,
            )) {
                bridge.readResult = KeychainBiometricResult(null, status)
                val exception = assertFailsWith<BiometricAuthException> {
                    repository().getBiometric("unlock", LAContext())
                }
                assertEquals(code, exception.code)
            }
        }
    }

    @Test
    fun failedProtectedWriteDoesNotFallBackToGenericStorage() = runTest {
        withBridge { bridge ->
            bridge.writeResult = KeychainBiometricResult(null, errSecUserCanceled)
            val exception = assertFailsWith<BiometricAuthException> {
                repository().putBiometric("unlock", "wrapped-key", LAContext())
            }
            assertEquals(BiometricAuthException.ERROR_USER_CANCELED, exception.code)
        }
    }

    @Test
    fun protectedReadWithoutValueFailsClosed() = runTest {
        withBridge { bridge ->
            bridge.readResult = KeychainBiometricResult(null, errSecSuccess)
            val exception = assertFailsWith<BiometricAuthException> {
                repository().getBiometric("unlock", LAContext())
            }
            assertEquals(BiometricAuthException.ERROR_KEY_INVALIDATED, exception.code)
        }
    }

    @Test
    fun genericUserPresenceRequestRefusesInsecureFallback() = runTest {
        assertFailsWith<IllegalArgumentException> {
            repository().put("unlock", "wrapped-key", requireUserPresence = true).bind()
        }
    }

    private fun repository() = KeychainRepositoryApple(errorMessage = { "Key unavailable" })

    private suspend fun withBridge(block: suspend (FakeBridge) -> Unit) {
        val bridge = FakeBridge()
        val previous = KeychainBridgeRegistry.bridge
        KeychainBridgeRegistry.bridge = bridge
        try {
            block(bridge)
        } finally {
            KeychainBridgeRegistry.bridge = previous
        }
    }

    private class FakeBridge : KeychainBridge {
        var readResult = KeychainBiometricResult(null, errSecSuccess)
        var writeResult = KeychainBiometricResult(null, errSecSuccess)
        var writtenAccount: String? = null
        var writtenValue: String? = null
        var writtenContext: LAContext? = null
        var readAccount: String? = null
        var readContext: LAContext? = null

        override fun setBiometric(account: String, value: String, context: LAContext): KeychainBiometricResult {
            writtenAccount = account
            writtenValue = value
            writtenContext = context
            return writeResult
        }

        override fun getBiometric(account: String, context: LAContext): KeychainBiometricResult {
            readAccount = account
            readContext = context
            return readResult
        }

        override fun set(account: String, value: String): Boolean = error("Unexpected generic write")

        override fun get(account: String): String? = error("Unexpected generic read")

        override fun delete(account: String): Boolean = error("Unexpected generic delete")

        override fun contains(account: String): Boolean = error("Unexpected generic lookup")
    }
}
