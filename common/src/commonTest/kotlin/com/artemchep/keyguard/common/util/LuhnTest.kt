package com.artemchep.keyguard.common.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LuhnTest {
    @Test
    fun `accepts known valid Luhn fixtures`() {
        val numbers = listOf(
            "48721484",
            "4242424242424242",
            "4111111111111111",
            "5555555555554444",
            "2223003122003222",
            "378282246310005",
            "6011111111111117",
            "36227206271667",
            "3566002020360505",
            "6304000000000000",
            "4013250000000000006",
            "135410014004955",
        )

        numbers.forEach { number ->
            assertTrue(
                actual = validLuhn(number),
                message = "$number should pass Luhn validation",
            )
        }
    }

    @Test
    fun `rejects invalid Luhn fixtures`() {
        val numbers = listOf(
            "4242424242424241",
            "4111111111111112",
            "5555555555554445",
            "378282246310006",
            "6011111111111118",
            "36227206271668",
            "3566002020360506",
            "4013250000000000007",
            "135410014004956",
        )

        numbers.forEach { number ->
            assertFalse(
                actual = validLuhn(number),
                message = "$number should fail Luhn validation",
            )
        }
    }

    @Test
    fun `rejects numbers accepted by inverted parity validation`() {
        val numbers = listOf(
            "7111111111111111",
            "0242424242424242",
            "69927398713",
        )

        numbers.forEach { number ->
            assertFalse(
                actual = validLuhn(number),
                message = "$number should fail Luhn validation",
            )
        }
    }

    @Test
    fun `keeps existing empty input contract`() {
        assertTrue(validLuhn(""))
    }
}
