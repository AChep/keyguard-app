package db_key_value.shared_prefs.encrypted

import com.google.crypto.tink.shaded.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.test.runTest
import java.io.CharConversionException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecureSharedPrefsRetryTest {
    @Test
    fun `malformed keyset is cleared before retrying`() =
        runTest {
            var attempts = 0
            var clearCalls = 0

            val result =
                retrySecureSharedPrefs(
                    delayForRetry = { error("corruption retry must not be delayed") },
                    clear = { clearCalls += 1 },
                    block = {
                        attempts += 1
                        if (attempts == 1) {
                            throw CharConversionException("invalid keyset")
                        }
                        "ready"
                    },
                )

            assertEquals("ready", result)
            assertEquals(2, attempts)
            assertEquals(1, clearCalls)
        }

    @Test
    fun `malformed keyset protobuf is cleared before retrying`() =
        runTest {
            var attempts = 0
            var clearCalls = 0

            val result =
                retrySecureSharedPrefs(
                    delayForRetry = { error("corruption retry must not be delayed") },
                    clear = { clearCalls += 1 },
                    block = {
                        attempts += 1
                        if (attempts == 1) {
                            throw InvalidProtocolBufferException("keyset protobuf is truncated")
                        }
                        "ready"
                    },
                )

            assertEquals("ready", result)
            assertEquals(2, attempts)
            assertEquals(1, clearCalls)
        }

    @Test
    fun `transient io failure delays without clearing data`() =
        runTest {
            var attempts = 0
            var clearCalls = 0
            val delays = mutableListOf<Long>()

            val failure =
                assertFailsWith<IOException> {
                    retrySecureSharedPrefs(
                        delayForRetry = { delays += it },
                        clear = { clearCalls += 1 },
                        block = {
                            attempts += 1
                            throw IOException("storage unavailable")
                        },
                    )
                }

            assertEquals("storage unavailable", failure.message)
            assertEquals(3, attempts)
            assertEquals(2, delays.size)
            assertEquals(0, clearCalls)
        }
}
