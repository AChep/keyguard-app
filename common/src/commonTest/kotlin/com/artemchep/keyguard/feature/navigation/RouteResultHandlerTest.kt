package com.artemchep.keyguard.feature.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

private const val DENY = "deny"
private const val CONFIRM = "confirm"

class RouteResultHandlerTest {
    @Test
    fun `dismissal reports denial once`() {
        val results = mutableListOf<String>()
        val handler = resultHandler(results)

        handler.complete(DENY)
        handler.complete(DENY)

        assertEquals(listOf(DENY), results)
    }

    @Test
    fun `confirmation wins when closing the dialog also reports dismissal`() {
        val results = mutableListOf<String>()
        val handler = resultHandler(results)

        handler.complete(CONFIRM) {
            handler.complete(DENY)
            assertEquals(emptyList(), results)
        }

        assertEquals(listOf(CONFIRM), results)
    }

    @Test
    fun `dismissed dialog cannot later confirm`() {
        val results = mutableListOf<String>()
        val handler = resultHandler(results)

        handler.complete(DENY)
        handler.complete(CONFIRM)

        assertEquals(listOf(DENY), results)
    }
}

private fun resultHandler(results: MutableList<String>) = RouteResultHandler(
    transmitter = object : RouteResultTransmitter<String> {
        override fun invoke(unit: String) {
            results += unit
        }
    },
)
