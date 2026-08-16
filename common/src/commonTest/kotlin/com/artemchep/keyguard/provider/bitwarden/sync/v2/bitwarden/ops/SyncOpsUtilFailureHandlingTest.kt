package com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.ops

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncOpsUtilFailureHandlingTest {
    @Test
    fun `decode fallback still handles ordinary exceptions`() = runTest {
        val failure = IllegalStateException("cipher decode failed")

        val result = decodeRemoteOrFallback(
            decode = { throw failure },
            fallback = { error -> error.message.orEmpty() },
        )

        assertEquals("cipher decode failed", result)
    }

    @Test
    fun `decode fallback propagates fatal errors`() = runTest {
        val failure = AssertionError("cipher decoder runtime is broken")

        val actual = assertFailsWith<AssertionError> {
            decodeRemoteOrFallback(
                decode = { throw failure },
                fallback = { error("fatal failure must not reach fallback") },
            )
        }

        assertTrue(actual === failure)
    }

    @Test
    fun `decode fallback propagates cancellation`() = runTest {
        val failure = CancellationException("decode cancelled")

        val actual = assertFailsWith<CancellationException> {
            decodeRemoteOrFallback(
                decode = { throw failure },
                fallback = { error("cancellation must not reach fallback") },
            )
        }

        assertTrue(actual === failure)
    }
}
