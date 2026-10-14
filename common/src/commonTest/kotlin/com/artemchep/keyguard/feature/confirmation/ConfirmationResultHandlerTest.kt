package com.artemchep.keyguard.feature.confirmation

import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfirmationResultHandlerTest {
    @Test
    fun `dismissal reports denial once`() {
        val results = mutableListOf<ConfirmationResult>()
        val handler = resultHandler(results)

        handler.complete(ConfirmationResult.Deny)
        handler.complete(ConfirmationResult.Deny)

        assertEquals(listOf<ConfirmationResult>(ConfirmationResult.Deny), results)
    }

    @Test
    fun `confirmation wins when closing the dialog also reports dismissal`() {
        val results = mutableListOf<ConfirmationResult>()
        val handler = resultHandler(results)
        val confirmed = ConfirmationResult.Confirm(mapOf("passphrase" to "password"))

        handler.complete(confirmed) {
            handler.complete(ConfirmationResult.Deny)
            assertEquals(emptyList(), results)
        }

        assertEquals(listOf<ConfirmationResult>(confirmed), results)
    }

    @Test
    fun `dismissed dialog cannot later confirm`() {
        val results = mutableListOf<ConfirmationResult>()
        val handler = resultHandler(results)

        handler.complete(ConfirmationResult.Deny)
        handler.complete(ConfirmationResult.Confirm(emptyMap()))

        assertEquals(listOf<ConfirmationResult>(ConfirmationResult.Deny), results)
    }
}

private fun resultHandler(results: MutableList<ConfirmationResult>) = ConfirmationResultHandler(
    transmitter = object : RouteResultTransmitter<ConfirmationResult> {
        override fun invoke(unit: ConfirmationResult) {
            results += unit
        }
    },
)
