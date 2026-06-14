package com.artemchep.keyguard.common.service.similarity.util

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class JaroWinklerStrategyTest {
    @Test
    fun `scores known compatibility examples`() {
        assertScore(0.9611111111, "MARTHA", "MARHTA")
        assertScore(0.8133333333, "DIXON", "DICKSONX")
        assertScore(0.84, "DWAYNE", "DUANE")
        assertScore(0.8666666667, "CRATE", "TRACE")
    }

    @Test
    fun `scores identical strings and case differences as exact matches`() {
        assertScore(1.0, "password", "password")
        assertScore(1.0, "abc", "ABC")
    }

    @Test
    fun `scores empty and disjoint strings as no match`() {
        assertScore(0.0, "", "")
        assertScore(0.0, "", "password")
        assertScore(0.0, "abc", "xyz")
    }

    @Test
    fun `score is symmetric`() {
        listOf(
            "MARTHA" to "MARHTA",
            "DIXON" to "DICKSONX",
            "password" to "Password",
            "abc" to "xyz",
        ).forEach { (first, second) ->
            assertScore(
                expected = JaroWinklerStrategy.score(first, second),
                first = second,
                second = first,
            )
        }
    }
}

private const val SCORE_TOLERANCE = 0.0000000001

private fun assertScore(
    expected: Double,
    first: String,
    second: String,
) {
    val actual = JaroWinklerStrategy.score(first, second)
    assertTrue(
        actual = abs(actual - expected) <= SCORE_TOLERANCE,
        message = "Expected score($first, $second) to be $expected, but was $actual.",
    )
}
