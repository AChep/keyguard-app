package com.artemchep.keyguard.util.zxcvbn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the public API against the real native library on every platform
 * whose tests run: desktop, the Android host, iOS simulator and macOS.
 */
class ZxcvbnTest {
    @Test
    fun aTopTenPasswordScoresZeroAndSaysWhy() {
        val result = Zxcvbn.estimate("password")

        assertEquals(0, result.score)
        assertEquals(ZxcvbnWarning.ThisIsATop10Password, result.warning)
        assertTrue(result.guesses > 0L, "guesses=${result.guesses}")
        assertTrue(result.guessesLog10 >= 0.0, "guessesLog10=${result.guessesLog10}")
    }

    @Test
    fun aPassphraseScoresWellAndSurvivesOfflineSlowHashing() {
        val result = Zxcvbn.estimate("correcthorsebatterystaple")

        assertTrue(result.score >= 3, "score=${result.score}")
        assertTrue(
            result.crackTimes.offlineSlowHashing1e4PerSecond > 1e6,
            "offlineSlowHashing1e4PerSecond=" +
                "${result.crackTimes.offlineSlowHashing1e4PerSecond}",
        )
    }

    @Test
    fun userInputsLowerTheGuessCount() {
        val password = "zqxjvmklp"

        val anonymous = Zxcvbn.estimate(password)
        val known = Zxcvbn.estimate(password, userInputs = listOf(password))

        assertTrue(
            known.guesses < anonymous.guesses,
            "known=${known.guesses}, anonymous=${anonymous.guesses}",
        )
    }

    @Test
    fun anOverLongPasswordIsRejected() {
        val password = "a".repeat(NATIVE_MAX_PASSWORD_BYTES + 1)

        assertFailsWith<ZxcvbnException> {
            Zxcvbn.estimate(password)
        }
    }

    @Test
    fun aPasswordAtTheLengthLimitIsAccepted() {
        val password = "a".repeat(NATIVE_MAX_PASSWORD_BYTES)

        // The limit is inclusive; only one more byte is rejected. The score
        // itself is upstream's business — a long repeat is a repeat match, not
        // necessarily the weakest possible password.
        val result = Zxcvbn.estimate(password)

        assertTrue(result.score in 0..MAX_SCORE, "score=${result.score}")
    }

    @Test
    fun anEmptyPasswordIsTheWeakestPossible() {
        val result = Zxcvbn.estimate("")

        assertEquals(0, result.score)
        assertNull(result.warning)
    }

    @Test
    fun theLibraryIsAvailable() {
        ensureZxcvbnAvailable()
    }

    @Test
    fun concurrentEstimatesAgreeWithTheSequentialOne() = runTest {
        val expected = Zxcvbn.estimate("Tr0ub4dour&3")

        // The estimator is a pure function and takes no lock; run it from
        // several threads at once so a regression that introduces shared
        // mutable state shows up as a mismatch or a crash.
        val results = withContext(Dispatchers.Default) {
            coroutineScope {
                List(CONCURRENT_ESTIMATES) {
                    async { Zxcvbn.estimate("Tr0ub4dour&3") }
                }.awaitAll()
            }
        }

        assertEquals(CONCURRENT_ESTIMATES, results.size)
        results.forEach { result ->
            assertEquals(expected, result)
        }
    }
}

private const val CONCURRENT_ESTIMATES = 16

/** Highest score the estimator reports. */
private const val MAX_SCORE = 4

/** `KEYGUARD_ZXCVBN_MAX_PASSWORD_BYTES` of the native ABI. */
private const val NATIVE_MAX_PASSWORD_BYTES = 256
