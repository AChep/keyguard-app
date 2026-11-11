package com.artemchep.keyguard.provider.bitwarden.transport

import java.io.IOException
import java.io.InterruptedIOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The HTTP client survives only an [IOException] from the code that
 * loads the certificate, so each failure of the key chain must become one.
 */
class MutualTlsSystemStoreAndroidTest {
    @Test
    fun `runtime failure of the key chain is an input-output failure`() {
        // Such as the check that rejects the main thread.
        val failure = IllegalStateException("calling this from your main thread can lead to deadlock")

        val e = assertFailsWith<IOException> {
            loadKeyChainValue<String> { throw failure }
        }
        assertEquals(failure, e.cause)
    }

    @Test
    fun `failed binding to the key chain is an input-output failure`() {
        val failure = AssertionError("could not bind to KeyChainService")

        val e = assertFailsWith<IOException> {
            loadKeyChainValue<String> { throw failure }
        }
        assertEquals(failure, e.cause)
    }

    @Test
    fun `interruption keeps the thread interrupted`() {
        try {
            val e = assertFailsWith<IOException> {
                loadKeyChainValue<String> { throw InterruptedException("interrupted") }
            }
            assertIs<InterruptedIOException>(e)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            // Do not leak the flag into the next test of this thread.
            Thread.interrupted()
        }
    }
}
