package com.artemchep.keyguard.util.zxcvbn

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ZxcvbnDesktopTest {
    /**
     * A regression guard against per-call re-initialisation.
     *
     * The estimator builds its frequency dictionaries and regular expressions
     * once, lazily; a change that rebuilt them per call would still pass every
     * correctness test while making the vault decode and the generator badge
     * unusably slow. The bound is deliberately generous — a warm estimate of a
     * password this size takes well under a millisecond, so a thousand of them
     * finish in a fraction of a second, while re-initialising each time costs
     * tens of milliseconds a call and blows past it.
     */
    @Test
    fun aThousandEstimatesStayFarBelowTheBudget() {
        val password = "Tr0ub4dour&3-Correct-Horse-Stap!"
        ensureZxcvbnAvailable()

        val started = TimeSource.Monotonic.markNow()
        repeat(ESTIMATE_COUNT) {
            Zxcvbn.estimate(password)
        }
        val elapsed = started.elapsedNow()

        assertTrue(
            elapsed < BUDGET,
            "$ESTIMATE_COUNT estimates took $elapsed, expected less than $BUDGET",
        )
    }
}

private const val ESTIMATE_COUNT = 1000

private val BUDGET = 10.seconds
